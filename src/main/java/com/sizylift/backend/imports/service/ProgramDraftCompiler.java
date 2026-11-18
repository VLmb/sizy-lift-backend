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
import com.sizylift.backend.imports.entity.ImportLanguage;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.entity.LiftClass;
import com.sizylift.backend.template.SetLine;
import com.sizylift.backend.template.TemplateVocabulary;
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
    /** Длина названия блока в программе (ProgramPayloadV1.Block.title). */
    private static final int SHORT_TEXT_CHARS = 200;
    /** Решение «не добавлять»: упражнение и его подходы не попадают в программу. */
    public static final String SKIP = "skip";
    /** Решение «сделать блоком»: строка источника («Разминка») — не упражнение, а текстовый блок тренировки. */
    public static final String BLOCK = "block";
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
        var headers = new LinkedHashMap<String, String>();
        for (var r : session.output().exerciseResolutions()) {
            var decision = session.decisions().get(r.key());
            if (decision == null) throw ImportFailure.invalid("Выберите упражнение: " + r.originalName());
            if (decision.kind().equals(BLOCK))
                headers.put(r.key(), decision.name() == null || decision.name().isBlank() ? r.originalName() : decision.name().trim());
            else if (!decision.kind().equals(SKIP)) exercises.put(r.key(), resolve(session.id(), decision, catalog, created, at));
        }
        var skipped = session.decisions().values().stream().filter(d -> d.kind().equals(SKIP)).map(Decision::key)
                .collect(Collectors.toSet());
        var context = new Compilation(session.id(), session.startDate(), exercises, skipped, headers, warnings,
                ImportLanguage.of(session.normalized() == null ? null : session.normalized().language()));
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
        return new ProgramPayloadV1.Cycle(id(context.id(), source.key()), source.name().isBlank() ? context.language().text("Цикл", "Cycle") : source.name(), position, weeks);
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
        for (var block : source.blocks()) blocks(context, block, blocks);
        return new ProgramPayloadV1.PlannedWorkout(id(context.id(), source.key()), nullable(source.title()),
                context.startDate().plusDays(source.dayOffset()), position, blocks);
    }

    /**
     * Блок источника. Упражнение, которое решили не добавлять, выпадает вместе с подходами; строка «сделать блоком»
     * становится свободным блоком на своём месте (название, а комментарий и подходы — его описанием) и делит блок
     * источника на части до и после неё. Пустые части не попадают в программу.
     */
    private void blocks(Compilation context, ImportDraftV1.Block source, List<ProgramPayloadV1.Block> out) {
        var entries = new ArrayList<ProgramPayloadV1.Exercise>();
        int part = 0;
        for (var ex : source.exercises()) {
            if (context.skipped().contains(ex.resolutionKey())) continue;
            var header = context.headers().get(ex.resolutionKey());
            if (header == null) {
                entries.add(exercise(context, ex, entries.size()));
                continue;
            }
            if (!entries.isEmpty()) {
                out.add(part(context, source, part++, out.size(), entries));
                entries = new ArrayList<>();
            }
            out.add(new ProgramPayloadV1.Block(id(context.id(), ex.key() + ":block"), out.size(), "free", null, null,
                    shortText(header), nullable(headerText(ex, context.language())), List.of()));
        }
        if (!entries.isEmpty()) out.add(part(context, source, part, out.size(), entries));
    }

    private ProgramPayloadV1.Block part(Compilation context, ImportDraftV1.Block source, int part, int position,
                                        List<ProgramPayloadV1.Exercise> entries) {
        var key = part == 0 ? source.key() : source.key() + ":" + part;
        return new ProgramPayloadV1.Block(id(context.id(), key), position, source.blockType(),
                nullable(source.groupLabel()), null, part == 0 ? nullable(source.title()) : null,
                part == 0 ? nullable(source.description()) : null, entries);
    }

    /** Описание блока из строки-заголовка: комментарий и подходы коротко («10 мин», «2 × 10»). */
    private String headerText(ImportDraftV1.Exercise ex, ImportLanguage language) {
        var parts = new ArrayList<String>();
        parts.add(ex.comment());
        for (var set : ex.sets()) parts.add(brief(set, language));
        return join(parts.stream().filter(p -> p != null && !p.isBlank()).distinct().toList());
    }

    private String brief(ImportDraftV1.Prescription set, ImportLanguage language) {
        var parts = new ArrayList<String>();
        if (!set.durationSeconds().isBlank()) {
            try {
                var seconds = new BigDecimal(set.durationSeconds().replace(',', '.'));
                parts.add(seconds.remainder(BigDecimal.valueOf(60)).signum() == 0 && seconds.intValue() >= 60
                        ? seconds.intValue() / 60 + language.text(" мин", " min")
                        : seconds.stripTrailingZeros().toPlainString() + language.text(" с", " s"));
            } catch (NumberFormatException e) {
                parts.add(set.durationSeconds() + language.text(" с", " s"));
            }
        }
        if (!set.distanceMeters().isBlank()) parts.add(set.distanceMeters() + language.text(" м", " m"));
        if (!set.weightKg().isBlank()) parts.add(set.weightKg() + language.text(" кг", " kg"));
        if (!set.repetitions().isBlank()) parts.add(set.repetitions());
        var text = String.join(" × ", parts);
        if (!set.count().isBlank() && !set.count().equals("1")) text = set.count() + " × " + (text.isEmpty() ? "" : text);
        if (!set.note().isBlank()) text = text.isBlank() ? set.note() : text + ", " + set.note();
        return text.strip();
    }

    private static String shortText(String text) {
        return text.length() <= SHORT_TEXT_CHARS ? text : text.substring(0, SHORT_TEXT_CHARS);
    }

    private ProgramPayloadV1.Exercise exercise(Compilation context, ImportDraftV1.Exercise source, int position) {
        var resolved = context.exercises().get(source.resolutionKey());
        if (resolved == null) throw ImportFailure.invalid("Не выбрано упражнение.");
        var sets = new ArrayList<ProgramPayloadV1.Set>();
        var comments = new ArrayList<String>();
        comments.add(source.comment());
        for (var prescription : source.sets()) {
            if (prescription.count().isBlank()) {
                comments.add(context.language().text("Количество подходов не указано. ", "Number of sets not specified. ") + describe(prescription, context.language()));
                context.warnings().add(context.language().text("У упражнения «", "Exercise “") + resolved.name()
                        + context.language().text("» не указано количество подходов.", "” has no set count."));
                continue;
            }
            int count = positiveInt(prescription.count(), SETS_PER_EXERCISE);
            if (sets.size() + count > SETS_PER_EXERCISE) throw ImportFailure.invalid("Больше 200 подходов у упражнения.");
            for (int i = 0; i < count; i++) sets.add(set(context.id(), prescription, resolved, i, sets.size(), context.warnings(), context.language(), comments));
        }
        return new ProgramPayloadV1.Exercise(id(context.id(), source.key()), position, resolved.id(), resolved.name(),
                resolved.resultType(), join(comments), sets);
    }

    public void validateDecision(UUID session, Decision decision, List<CatalogExercise> catalog) {
        if (decision.kind().equals(SKIP) || decision.kind().equals(BLOCK)) return;
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

    /** Подход плана. Комментариев к подходу нет: пометки подхода уходят в комментарий упражнения ({@code comments}). */
    private ProgramPayloadV1.Set set(UUID session, ImportDraftV1.Prescription source, Resolved exercise,
                                     int index, int position, List<String> warnings, ImportLanguage language,
                                     List<String> comments) {
        var notes = new ArrayList<String>();
        notes.add(source.note());
        Integer reps = null;
        if (!source.repetitions().isBlank()) {
            if (source.repetitions().matches("\\d{1,4}")) reps = positiveInt(source.repetitions(), REPETITIONS);
            else {
                if (!source.repetitions().matches("[1-9]\\d{0,3}-[1-9]\\d{0,3}|AMRAP"))
                    throw ImportFailure.invalid("Некорректное предписание повторений.");
                notes.add(language.text("Повторения: ", "Reps: ") + source.repetitions());
                warnings.add(language.text("Диапазоны повторений и AMRAP сохранены в заметках.", "Rep ranges and AMRAP were kept in the notes."));
            }
        }
        Integer weight = weight(source.weightKg(), notes, warnings, language);
        Integer duration = scaled(source.durationSeconds(), MILLIS_PER_SECOND);
        Integer distance = scaled(source.distanceMeters(), 1);
        Integer percent = scaled(source.percentOneRm(), TENTHS);
        Integer rpe = scaled(source.rpe(), TENTHS);
        if (rpe != null && (rpe < RPE_MIN || rpe > RPE_MAX || rpe % RPE_STEP != 0)) {
            notes.add("RPE: " + source.rpe());
            warnings.add(language.text("RPE вне поддерживаемой шкалы сохранён в заметке.", "RPE outside the supported range was kept in the note."));
            rpe = null;
        }
        if (!source.rir().isBlank()) {
            notes.add("RIR: " + source.rir());
            if (rpe == null && source.rpe().isBlank()
                    && TemplateVocabulary.keysFor(exercise.resultType(), TemplateVocabulary.Scope.IMPORT).contains("rpe")) {
                var converted = SetLine.rpeFromRir(source.rir());
                if (converted != null) rpe = (int) Math.round(converted * TENTHS);
            }
            if (rpe == null) warnings.add(language.text("RIR сохранён в заметках.", "RIR was kept in the notes."));
        }
        if (percent != null && (percent < PERCENT_MIN || percent > PERCENT_MAX || exercise.liftClass() == LiftClass.OTHER
                || !exercise.resultType().equals("weight_reps"))) {
            notes.add(source.percentOneRm() + language.text("% ПМ", "% 1RM"));
            warnings.add(language.text("Процент, недоступный для этого упражнения, сохранён в заметке.", "Percentage not supported for this exercise; kept in the note."));
            percent = null;
        }
        var type = exercise.resultType();
        if (type.equals("duration") && (reps != null || weight != null)
                || type.equals("free") && (reps != null || weight != null || duration != null || distance != null)
                || (type.equals("weight_reps") || type.equals("added_weight_reps")) && (duration != null || distance != null)
                || type.equals("reps") && (weight != null || duration != null || distance != null)
                || type.equals("distance_time") && (reps != null || weight != null))
            throw ImportFailure.invalid("Тип выбранного упражнения несовместим с подходами. Выберите другое или создайте своё.");
        return new ProgramPayloadV1.Set(id(session, source.key() + ":" + index), position,
                source.setType().isBlank() ? "working" : source.setType(), weight, reps, duration, distance,
                rpe, false, percent, scaled(source.restSeconds(), MILLIS_PER_SECOND), withNotes(comments, notes));
    }

    private String describe(ImportDraftV1.Prescription value, ImportLanguage language) {
        return language.text("Повторения: ", "Reps: ") + value.repetitions()
                + language.text("; кг: ", "; kg: ") + value.weightKg() + language.text("; %ПМ: ", "; % 1RM: ") + value.percentOneRm()
                + language.text("; секунды: ", "; seconds: ") + value.durationSeconds() + language.text("; метры: ", "; meters: ") + value.distanceMeters()
                + "; RPE: " + value.rpe() + "; RIR: " + value.rir() + language.text("; отдых, с: ", "; rest, s: ") + value.restSeconds() + ". " + value.note();
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
    private Integer weight(String value, List<String> notes, List<String> warnings, ImportLanguage language) {
        if (value.isBlank()) return null;
        try {
            var grams = new BigDecimal(value.replace(',', '.')).multiply(BigDecimal.valueOf(GRAMS_PER_KG));
            if (grams.signum() < 0) throw new ArithmeticException();
            var rounded = grams.setScale(0, RoundingMode.HALF_UP);
            if (rounded.compareTo(grams) != 0) {
                notes.add(language.text("Исходный вес: ", "Original weight: ") + value + language.text(" кг", " kg"));
                warnings.add(language.text("Вес округлён до целого грамма.", "Weight rounded to the nearest gram."));
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
    private static String withNotes(List<String> comments, List<String> notes) {
        for (var note : notes) if (note != null && !note.isBlank() && !comments.contains(note)) comments.add(note);
        return null;
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
    private record Compilation(UUID id, java.time.LocalDate startDate, Map<String, Resolved> exercises, java.util.Set<String> skipped, Map<String, String> headers, List<String> warnings, ImportLanguage language) { }
}
