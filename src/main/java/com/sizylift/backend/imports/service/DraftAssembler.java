package com.sizylift.backend.imports.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.sizylift.backend.imports.dto.ImportDtos.Draft;
import com.sizylift.backend.imports.dto.ImportDtos.Suggestion;
import com.sizylift.backend.imports.dto.ImportDtos.Warning;
import com.sizylift.backend.imports.entity.CatalogExercise;
import com.sizylift.backend.imports.model.ParsedReply;
import com.sizylift.backend.imports.model.ParsedReply.Load;
import com.sizylift.backend.imports.model.ParsedReply.SetLine;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;

/**
 * Детерминированная сборка черновика из промежуточного описания (ADR-0008, раздел 2): модель не считает
 * веса и не придумывает id. Процент от 1ПМ — вес по 1ПМ владельца программы с округлением до 2,5 кг; без 1ПМ —
 * процент в заметке подхода. «−10% от топ-сета» — от уже посчитанного веса топ-сета. Даты недель — от даты
 * старта. Не найденное упражнение в черновик не попадает, о нём — предупреждение с похожими вариантами.
 */
class DraftAssembler {

    static final String CYCLE_NAME = "Импорт";
    static final String DRAFT_STATUS = "draft";
    static final String SINGLE_BLOCK = "single";
    static final String DEFAULT_SET_TYPE = "working";
    static final String TOP_SET = "top";
    static final int PLATE_STEP_GRAMS = 2500;
    static final int GRAMS_PER_KG = 1000;
    static final double PERCENT = 100.0;
    static final int RPE_STEP_TENTHS = 5;
    static final int RPE_MIN_TENTHS = 50;
    static final int RPE_MAX_TENTHS = 100;
    static final int TENTHS = 10;
    static final int DAYS_IN_WEEK = 7;
    static final int MAX_SETS_PER_EXERCISE = 200;

    static final String EXERCISE_NOT_FOUND = "exercise_not_found";
    static final String NO_ONE_REP_MAX = "no_one_rep_max";
    static final String NO_TOP_SET = "no_top_set";
    static final String RPE_OUT_OF_RANGE = "rpe_out_of_range";
    static final String ASSUMPTION = "assumption";

    private final Function<String, ExerciseMatcher.Match> matcher;
    private final Map<UUID, Integer> oneRepMaxGrams;
    private final LocalDate startDate;
    private final Instant now;
    private final List<Warning> warnings = new ArrayList<>();

    DraftAssembler(Function<String, ExerciseMatcher.Match> matcher, Map<UUID, Integer> oneRepMaxGrams,
                   LocalDate startDate, Instant now) {
        this.matcher = matcher;
        this.oneRepMaxGrams = oneRepMaxGrams;
        this.startDate = startDate;
        this.now = now;
    }

    Result assemble(ParsedReply reply) {
        reply.assumptionsOrEmpty().forEach(text -> warnings.add(Warning.of(ASSUMPTION, text)));
        var parsed = reply.program();
        var weeks = new ArrayList<ProgramPayloadV1.Week>();
        var sortedWeeks = parsed.weeks().stream().sorted(Comparator.comparingInt(ParsedReply.Week::number)).toList();
        for (int position = 0; position < sortedWeeks.size(); position++) {
            weeks.add(week(sortedWeeks.get(position), position));
        }
        var cycle = new ProgramPayloadV1.Cycle(UUID.randomUUID(), CYCLE_NAME, 0, weeks);
        var program = new ProgramPayloadV1(parsed.name(), parsed.description(), startDate, DRAFT_STATUS, now,
                List.of(cycle));
        return new Result(new Draft(UUID.randomUUID(), program), List.copyOf(warnings));
    }

    private ProgramPayloadV1.Week week(ParsedReply.Week week, int position) {
        var weekStart = startDate.plusDays((long) (week.number() - 1) * DAYS_IN_WEEK);
        var workouts = week.workouts().stream().sorted(Comparator.comparingInt(ParsedReply.Workout::day)).toList();
        var planned = new ArrayList<ProgramPayloadV1.PlannedWorkout>();
        for (int index = 0; index < workouts.size(); index++) {
            var workout = workouts.get(index);
            planned.add(new ProgramPayloadV1.PlannedWorkout(UUID.randomUUID(), workout.title(),
                    weekStart.plusDays(workout.day() - 1), index, blocks(workout)));
        }
        return new ProgramPayloadV1.Week(UUID.randomUUID(), week.number(), week.name(), weekStart,
                weekStart.plusDays(DAYS_IN_WEEK - 1), position, planned);
    }

    /** Каждое упражнение — отдельный блок {@code single}; не найденные пропускаются. */
    private List<ProgramPayloadV1.Block> blocks(ParsedReply.Workout workout) {
        var blocks = new ArrayList<ProgramPayloadV1.Block>();
        for (var parsed : workout.exercises()) {
            var match = matcher.apply(parsed.name());
            if (match.exercise().isEmpty()) {
                warnings.add(new Warning(EXERCISE_NOT_FOUND, "Упражнение не найдено: создайте своё или выберите "
                        + "похожее", parsed.name(), match.suggestions().stream()
                        .map(found -> new Suggestion(found.id(), found.name())).toList()));
                continue;
            }
            var exercise = match.exercise().get();
            var planned = new ProgramPayloadV1.Exercise(UUID.randomUUID(), 0, exercise.id(), exercise.name(),
                    exercise.resultType(), parsed.comment(), sets(parsed, exercise));
            blocks.add(new ProgramPayloadV1.Block(UUID.randomUUID(), blocks.size(), SINGLE_BLOCK, null, null, null,
                    null, List.of(planned)));
        }
        return blocks;
    }

    private List<ProgramPayloadV1.Set> sets(ParsedReply.Exercise parsed, CatalogExercise exercise) {
        var sets = new ArrayList<ProgramPayloadV1.Set>();
        Integer topSetGrams = null;
        for (var line : parsed.sets()) {
            var type = line.type() == null ? DEFAULT_SET_TYPE : line.type();
            var load = load(line, exercise, topSetGrams);
            if (TOP_SET.equals(type) && load.weightGrams() != null) {
                topSetGrams = load.weightGrams();
            }
            for (int i = 0; i < line.count() && sets.size() < MAX_SETS_PER_EXERCISE; i++) {
                sets.add(new ProgramPayloadV1.Set(UUID.randomUUID(), sets.size(), type, load.weightGrams(),
                        line.reps(), null, null, load.rpeTenths(), null, load.note()));
            }
        }
        return sets;
    }

    private Computed load(SetLine line, CatalogExercise exercise, Integer topSetGrams) {
        var load = line.load();
        if (load == null) {
            return Computed.NONE;
        }
        if (load.rpe() != null) {
            return new Computed(null, rpeTenths(load, exercise), null);
        }
        if (!exercise.weighted()) {
            return new Computed(null, null, describe(load));
        }
        if (load.weightKg() != null) {
            return new Computed(roundToPlates(load.weightKg() * GRAMS_PER_KG), null, null);
        }
        if (load.percentOf1rm() != null) {
            var oneRepMax = oneRepMaxGrams.get(exercise.id());
            if (oneRepMax == null) {
                warnings.add(new Warning(NO_ONE_REP_MAX, "Нет 1ПМ: вес не посчитан, процент — в заметке подхода",
                        exercise.name(), List.of()));
                return new Computed(null, null, describe(load));
            }
            return new Computed(roundToPlates(oneRepMax * load.percentOf1rm() / PERCENT), null, null);
        }
        if (topSetGrams == null) {
            warnings.add(new Warning(NO_TOP_SET, "Вес топ-сета неизвестен: процент — в заметке подхода",
                    exercise.name(), List.of()));
            return new Computed(null, null, describe(load));
        }
        return new Computed(roundToPlates(topSetGrams * (1 + load.relativeToTopSet() / PERCENT)), null, null);
    }

    /** RPE в десятых, шаг 0,5 (как в приложении); вне 5–10 — предупреждение. */
    private Integer rpeTenths(Load load, CatalogExercise exercise) {
        var tenths = (int) Math.round(load.rpe() * TENTHS / RPE_STEP_TENTHS) * RPE_STEP_TENTHS;
        if (tenths < RPE_MIN_TENTHS || tenths > RPE_MAX_TENTHS) {
            warnings.add(new Warning(RPE_OUT_OF_RANGE, "RPE вне диапазона 5–10, не задан", exercise.name(),
                    List.of()));
            return null;
        }
        return tenths;
    }

    /** До ближайших 2,5 кг. */
    static int roundToPlates(double grams) {
        return (int) Math.round(grams / PLATE_STEP_GRAMS) * PLATE_STEP_GRAMS;
    }

    private static String describe(Load load) {
        if (load.percentOf1rm() != null) {
            return format(load.percentOf1rm()) + "% от 1ПМ";
        }
        if (load.relativeToTopSet() != null) {
            return format(load.relativeToTopSet()) + "% от топ-сета";
        }
        return load.weightKg() == null ? null : format(load.weightKg()) + " кг";
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Черновик и предупреждения. */
    record Result(Draft draft, List<Warning> warnings) {
    }

    private record Computed(Integer weightGrams, Integer rpeTenths, String note) {

        static final Computed NONE = new Computed(null, null, null);
    }
}
