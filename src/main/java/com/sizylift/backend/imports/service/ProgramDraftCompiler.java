package com.sizylift.backend.imports.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportDtos.Decision;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.entity.LiftClass;
import com.sizylift.backend.web.dto.WebDtos.CatalogExercise;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import static com.sizylift.backend.imports.dto.ImportLimits.*;

/** Собирает payload без defaultSet, auto-RPE и подстановки отсутствующих количеств. */
@Component
public class ProgramDraftCompiler {
    private static final int GRAMS_PER_KG = 1000;
    private static final int MILLIS_PER_SECOND = 1000;
    private static final int TENTHS = 10;
    private final Validator validator;

    public ProgramDraftCompiler(Validator validator) { this.validator = validator; }

    public record Compiled(ProgramPayloadV1 program, Map<UUID, ExercisePayloadV1> exercises, List<String> warnings) { }

    public Map<String, Decision> initialDecisions(UUID session, ImportDraftV1 draft) {
        var result = new LinkedHashMap<String, Decision>();
        for (var r : draft.exerciseResolutions()) {
            if (r.status().equals("matched"))
                result.put(r.key(), new Decision(r.key(), "existing", UUID.fromString(r.exerciseId()), null, null, null));
            else if (r.status().equals("new") && !r.proposedResultType().isEmpty())
                result.put(r.key(), new Decision(r.key(), "new", id(session, "exercise:" + r.key()),
                        r.proposedName().isBlank() ? r.originalName() : r.proposedName(), r.proposedResultType(), LiftClass.OTHER));
        }
        return result;
    }

    public Compiled compile(ImportSession session, Instant at) {
        return compile(session, at, session.catalog());
    }

    public Compiled compile(ImportSession session, Instant at, List<CatalogExercise> available) {
        if (session.output() == null || session.output().needsClarification()) throw ImportFailure.conflict();
        var catalog = available.stream().collect(Collectors.toMap(CatalogExercise::id, Function.identity()));
        var exercises = new LinkedHashMap<String, Resolved>();
        var created = new LinkedHashMap<UUID, ExercisePayloadV1>();
        var warnings = new ArrayList<String>();
        for (var r : session.output().exerciseResolutions()) {
            var decision = session.decisions().get(r.key());
            if (decision == null) throw ImportFailure.invalid("Выберите упражнение: " + r.originalName());
            exercises.put(r.key(), resolve(session.id(), decision, catalog, created, at));
        }
        var context = new Compilation(session.id(), session.startDate(), exercises, warnings);
        var cycles = new ArrayList<ProgramPayloadV1.Cycle>();
        for (var cycle : session.output().programDraft().cycles()) cycles.add(cycle(context, cycle, cycles.size()));
        var program = new ProgramPayloadV1(session.name(), nullable(session.output().programDraft().description()),
                session.startDate(), "draft", at, cycles);
        valid(program);
        return new Compiled(program, Map.copyOf(created), warnings.stream().distinct().toList());
    }

    private ProgramPayloadV1.Cycle cycle(Compilation context, ImportDraftV1.Cycle source, int position) {
        var weeks = new ArrayList<ProgramPayloadV1.Week>();
        for (var week : source.weeks()) weeks.add(week(context, week, weeks.size()));
        return new ProgramPayloadV1.Cycle(id(context.id(), source.key()), source.name().isBlank() ? "Цикл" : source.name(), position, weeks);
    }

    private ProgramPayloadV1.Week week(Compilation context, ImportDraftV1.Week source, int position) {
        var workouts = new ArrayList<ProgramPayloadV1.PlannedWorkout>();
        for (var day : source.workouts()) workouts.add(day(context, day, workouts.size()));
        var start = context.startDate().plusDays((long) (source.number() - 1) * DAYS_PER_WEEK);
        return new ProgramPayloadV1.Week(id(context.id(), source.key()), source.number(), nullable(source.name()),
                start, start.plusDays(DAYS_PER_WEEK - 1), position, workouts);
    }

    private ProgramPayloadV1.PlannedWorkout day(Compilation context, ImportDraftV1.Day source, int position) {
        var blocks = new ArrayList<ProgramPayloadV1.Block>();
        for (var block : source.blocks()) blocks.add(block(context, block, blocks.size()));
        return new ProgramPayloadV1.PlannedWorkout(id(context.id(), source.key()), nullable(source.title()),
                context.startDate().plusDays(source.dayOffset()), position, blocks);
    }

    private ProgramPayloadV1.Block block(Compilation context, ImportDraftV1.Block source, int position) {
        var entries = new ArrayList<ProgramPayloadV1.Exercise>();
        for (var ex : source.exercises()) entries.add(exercise(context, ex, entries.size()));
        return new ProgramPayloadV1.Block(id(context.id(), source.key()), position, source.blockType(),
                nullable(source.groupLabel()), null, nullable(source.title()), nullable(source.description()), entries);
    }

    private ProgramPayloadV1.Exercise exercise(Compilation context, ImportDraftV1.Exercise source, int position) {
        var resolved = context.exercises().get(source.resolutionKey());
        if (resolved == null) throw ImportFailure.invalid("Не выбрано упражнение.");
        var sets = new ArrayList<ProgramPayloadV1.Set>();
        var comments = new ArrayList<String>();
        comments.add(source.comment());
        for (var prescription : source.sets()) {
            if (prescription.count().isBlank()) {
                comments.add("Количество подходов не указано. " + describe(prescription));
                context.warnings().add("У упражнения «" + resolved.name() + "» не указано количество подходов.");
                continue;
            }
            int count = positiveInt(prescription.count(), SETS_PER_EXERCISE);
            if (sets.size() + count > SETS_PER_EXERCISE) throw ImportFailure.invalid("Больше 200 подходов у упражнения.");
            for (int i = 0; i < count; i++) sets.add(set(context.id(), prescription, resolved, i, sets.size(), context.warnings()));
        }
        return new ProgramPayloadV1.Exercise(id(context.id(), source.key()), position, resolved.id(), resolved.name(),
                resolved.resultType(), join(comments), sets);
    }

    public void validateDecision(UUID session, Decision decision, List<CatalogExercise> catalog) {
        resolve(session, decision, catalog.stream().collect(Collectors.toMap(CatalogExercise::id, Function.identity())),
                new LinkedHashMap<>(), Instant.EPOCH);
    }

    private Resolved resolve(UUID session, Decision decision, Map<UUID, CatalogExercise> catalog,
                             Map<UUID, ExercisePayloadV1> created, Instant at) {
        if (decision.kind().equals("existing")) {
            var exercise = catalog.get(decision.exerciseId());
            if (exercise == null) throw ImportFailure.invalid("Выберите доступное упражнение из каталога.");
            return new Resolved(exercise.id(), exercise.name(), exercise.resultType(), exercise.liftClass());
        }
        if (!decision.kind().equals("new") || decision.name() == null || decision.name().isBlank()
                || decision.resultType() == null) throw ImportFailure.invalid("Укажите название и тип нового упражнения.");
        var liftClass = decision.liftClass() == null ? LiftClass.OTHER : decision.liftClass();
        if (!liftClass.allows(decision.resultType())) throw ImportFailure.invalid("Класс не подходит типу результата.");
        var payload = ExercisePayloadV1.stored(decision.name().trim(), null, null, decision.resultType(),
                liftClass, null, at, List.of(), List.of());
        valid(payload);
        var id = id(session, "exercise:" + decision.key());
        created.put(id, payload);
        return new Resolved(id, payload.name(), payload.resultType(), liftClass);
    }

    private ProgramPayloadV1.Set set(UUID session, ImportDraftV1.Prescription source, Resolved exercise,
                                     int index, int position, List<String> warnings) {
        var notes = new ArrayList<String>();
        notes.add(source.note());
        Integer reps = null;
        if (!source.repetitions().isBlank()) {
            if (source.repetitions().matches("\\d{1,4}")) reps = positiveInt(source.repetitions(), REPETITIONS);
            else {
                if (!source.repetitions().matches("[1-9]\\d{0,3}-[1-9]\\d{0,3}|AMRAP"))
                    throw ImportFailure.invalid("Некорректное предписание повторений.");
                notes.add("Повторения: " + source.repetitions());
                warnings.add("Диапазоны повторений и AMRAP сохранены в заметках.");
            }
        }
        Integer weight = weight(source.weightKg(), notes, warnings);
        Integer duration = scaled(source.durationSeconds(), MILLIS_PER_SECOND);
        Integer distance = scaled(source.distanceMeters(), 1);
        Integer percent = scaled(source.percentOneRm(), TENTHS);
        Integer rpe = scaled(source.rpe(), TENTHS);
        if (rpe != null && (rpe < RPE_MIN || rpe > RPE_MAX || rpe % RPE_STEP != 0)) {
            notes.add("RPE: " + source.rpe());
            warnings.add("RPE вне поддерживаемой шкалы сохранён в заметке.");
            rpe = null;
        }
        if (!source.rir().isBlank()) {
            notes.add("RIR: " + source.rir());
            warnings.add("RIR сохранён в заметках.");
        }
        if (percent != null && (percent < PERCENT_MIN || percent > PERCENT_MAX || exercise.liftClass() == LiftClass.OTHER
                || !exercise.resultType().equals("weight_reps"))) {
            notes.add(source.percentOneRm() + "% ПМ");
            warnings.add("Процент, недоступный для этого упражнения, сохранён в заметке.");
            percent = null;
        }
        var type = exercise.resultType();
        if (type.equals("duration") && (reps != null || weight != null)
                || type.equals("free") && (reps != null || weight != null || duration != null || distance != null)
                || (type.equals("weight_reps") || type.equals("added_weight_reps")) && (duration != null || distance != null)
                || type.equals("distance_time") && (reps != null || weight != null))
            throw ImportFailure.invalid("Тип выбранного упражнения несовместим с подходами. Выберите другое или создайте своё.");
        if (source.setType().isBlank()) notes.add("Тип подхода в источнике не указан.");
        return new ProgramPayloadV1.Set(id(session, source.key() + ":" + index), position,
                source.setType().isBlank() ? "working" : source.setType(), weight, reps, duration, distance,
                rpe, false, percent, scaled(source.restSeconds(), MILLIS_PER_SECOND), join(notes));
    }

    private String describe(ImportDraftV1.Prescription value) {
        return "Повторения: " + value.repetitions() + "; кг: " + value.weightKg() + "; %ПМ: " + value.percentOneRm()
                + "; секунды: " + value.durationSeconds() + "; метры: " + value.distanceMeters()
                + "; RPE: " + value.rpe() + "; RIR: " + value.rir() + "; отдых, с: " + value.restSeconds() + ". " + value.note();
    }
    private Integer scaled(String value, int factor) {
        if (value.isBlank()) return null;
        try {
            var number = new BigDecimal(value.replace(',', '.'));
            if (number.signum() < 0) throw new ArithmeticException();
            return number.multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw ImportFailure.invalid("Некорректное числовое значение в программе.");
        }
    }
    private Integer weight(String value, List<String> notes, List<String> warnings) {
        if (value.isBlank()) return null;
        try {
            var grams = new BigDecimal(value.replace(',', '.')).multiply(BigDecimal.valueOf(GRAMS_PER_KG));
            if (grams.signum() < 0) throw new ArithmeticException();
            var rounded = grams.setScale(0, RoundingMode.HALF_UP);
            if (rounded.compareTo(grams) != 0) {
                notes.add("Исходный вес: " + value + " кг");
                warnings.add("Вес округлён до целого грамма.");
            }
            return rounded.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw ImportFailure.invalid("Некорректный вес в программе.");
        }
    }
    private int positiveInt(String value, int max) {
        var number = scaled(value, 1);
        if (number == null || number < 1 || number > max) throw ImportFailure.invalid("Некорректное количество подходов или повторений.");
        return number;
    }
    private void valid(Object value) {
        if (!validator.validate(value).isEmpty()) throw ImportFailure.invalid("Программа не проходит проверку ограничений.");
    }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value; }
    private String join(List<String> parts) {
        var text = parts.stream().filter(value -> value != null && !value.isBlank()).collect(Collectors.joining("\n"));
        if (text.length() > NOTE_CHARS) throw ImportFailure.invalid("Слишком длинная заметка. Сократите исходник.");
        return nullable(text);
    }
    public static UUID id(UUID session, String key) {
        return UUID.nameUUIDFromBytes(("sizylift:import:" + session + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
    private record Resolved(UUID id, String name, String resultType, LiftClass liftClass) { }
    private record Compilation(UUID id, java.time.LocalDate startDate, Map<String, Resolved> exercises, List<String> warnings) { }
}
