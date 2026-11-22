package com.sizylift.backend.imports.service;

import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportDraftV1.*;
import com.sizylift.backend.imports.dto.ImportDtos.Decision;
import com.sizylift.backend.imports.dto.ImportDtos.ExerciseEdit;
import com.sizylift.backend.imports.dto.ImportDtos.SetEdit;
import com.sizylift.backend.web.dto.WebDtos.CatalogExercise;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.sizylift.backend.imports.dto.ImportLimits.SETS_PER_EXERCISE;

final class ImportExerciseEdit {
    private static final Set<String> NUMERIC_FIELDS = Set.of("weightKg", "percentOneRm", "durationSeconds",
            "distanceMeters", "rpe", "rir", "restSeconds");
    private static final Set<String> SET_TYPES = Set.of("warmup", "working", "top", "backoff", "drop");

    private ImportExerciseEdit() { }

    static ImportDraftV1 apply(UUID id, ImportDraftV1 output, ExerciseEdit edit, Map<String, Decision> decisions,
                             List<CatalogExercise> catalog) {
        var original = exercises(output.programDraft().cycles(), edit.workoutKey()).stream()
                .filter(exercise -> exercise.key().equals(edit.exerciseKey()))
                .findFirst().orElseThrow(() -> ImportFailure.invalid("Упражнение в тренировке не найдено."));
        if ((edit.exerciseId() == null) == (edit.set() == null))
            throw ImportFailure.invalid("Укажите одну правку упражнения.");

        var resolutions = new ArrayList<>(output.exerciseResolutions());
        var resolutionKey = original.resolutionKey();
        var sets = original.sets();
        if (edit.set() != null) {
            var decision = decisions.get(resolutionKey);
            if (decision == null || "skip".equals(decision.kind()) || "block".equals(decision.kind()))
                throw ImportFailure.invalid("Сначала выберите упражнение.");
            sets = editSet(id, sets, edit.set());
        } else {
            var replacement = catalog.stream().filter(item -> item.id().equals(edit.exerciseId())).findFirst()
                    .orElseThrow(() -> ImportFailure.invalid("Выберите доступное упражнение из каталога."));
            resolutionKey = ProgramDraftCompiler.id(id, "preview:" + edit.workoutKey() + ":" + edit.exerciseKey()).toString();
            String key = resolutionKey;
            resolutions.removeIf(resolution -> resolution.key().equals(key));
            resolutions.add(new Resolution(key, replacement.name(), "matched", replacement.id().toString(),
                    List.of(), replacement.name(), replacement.resultType(), original.sourceRefs()));
            decisions.put(key, new Decision(key, "existing", replacement.id(), null, null, null));
        }

        var updated = new Exercise(original.key(), resolutionKey, original.comment(), original.sourceRefs(), sets);
        var draft = output.programDraft();
        var cycles = draft.cycles().stream().map(cycle -> new Cycle(cycle.key(), cycle.name(),
                cycle.weeks().stream().map(week -> new Week(week.key(), week.number(), week.name(),
                        mapMatching(week.workouts(), day -> day.key().equals(edit.workoutKey()), day ->
                                new Day(day.key(), day.title(), day.dayOffset(), day.blocks().stream().map(block ->
                                        new Block(block.key(), block.blockType(), block.groupLabel(), block.title(),
                                                block.description(), mapMatching(block.exercises(),
                                                        exercise -> exercise.key().equals(edit.exerciseKey()),
                                                        ignored -> updated))).toList())))).toList())).toList();
        if (!resolutionKey.equals(original.resolutionKey()) && exercises(cycles, null).stream()
                .noneMatch(exercise -> exercise.resolutionKey().equals(original.resolutionKey()))) {
            resolutions.removeIf(resolution -> resolution.key().equals(original.resolutionKey()));
            decisions.remove(original.resolutionKey());
        }
        return new ImportDraftV1(output.schemaVersion(), output.needsClarification(), output.clarificationMessage(),
                new Draft(draft.name(), draft.description(), cycles), resolutions, output.plan(), output.warnings(), output.rules());
    }

    private static List<Exercise> exercises(List<Cycle> cycles, String workoutKey) {
        return cycles.stream().flatMap(cycle -> cycle.weeks().stream()).flatMap(week -> week.workouts().stream())
                .filter(day -> workoutKey == null || day.key().equals(workoutKey))
                .flatMap(day -> day.blocks().stream()).flatMap(block -> block.exercises().stream()).toList();
    }

    private static <T> List<T> mapMatching(List<T> items, java.util.function.Predicate<T> matches, UnaryOperator<T> update) {
        return items.stream().map(item -> matches.test(item) ? update.apply(item) : item).toList();
    }

    private static List<Prescription> editSet(UUID id, List<Prescription> sets, SetEdit edit) {
        var result = new ArrayList<Prescription>();
        boolean found = false;
        for (var set : sets) {
            if (!set.key().equals(edit.key())) {
                result.add(set);
                continue;
            }
            found = true;
            int count = set.count().isBlank() ? 1 : Integer.parseInt(set.count());
            if (edit.index() >= count || count > SETS_PER_EXERCISE)
                throw ImportFailure.invalid("Подход в тренировке не найден.");
            edit.values().forEach(ImportExerciseEdit::validate);
            if (edit.index() > 0) result.add(copy(set, set.key(), edit.index(), Map.of()));
            var key = edit.index() == 0 ? set.key() : splitKey(id, set, edit.index());
            result.add(copy(set, key, 1, edit.values()));
            int remaining = count - edit.index() - 1;
            if (remaining > 0) result.add(copy(set, splitKey(id, set, edit.index() + 1), remaining, Map.of()));
        }
        if (!found) throw ImportFailure.invalid("Подход в тренировке не найден.");
        return result;
    }

    private static String splitKey(UUID id, Prescription set, int index) {
        return ProgramDraftCompiler.id(id, "preview-set:" + set.key() + ":" + index).toString();
    }

    private static Prescription copy(Prescription set, String key, int count, Map<String, String> values) {
        return new Prescription(key, String.valueOf(count), values.getOrDefault("repetitions", set.repetitions()),
                values.getOrDefault("weightKg", set.weightKg()), values.getOrDefault("percentOneRm", set.percentOneRm()),
                values.getOrDefault("durationSeconds", set.durationSeconds()), values.getOrDefault("distanceMeters", set.distanceMeters()),
                values.getOrDefault("rpe", set.rpe()), values.getOrDefault("rir", set.rir()),
                values.getOrDefault("restSeconds", set.restSeconds()), values.getOrDefault("setType", set.setType()),
                set.note(), set.sourceRefs());
    }

    private static void validate(String field, String value) {
        boolean valid = switch (field) {
            case "repetitions" -> value.isEmpty() || value.matches("AMRAP|[1-9]\\d{0,3}(-[1-9]\\d{0,3})?");
            case "setType" -> SET_TYPES.contains(value);
            default -> NUMERIC_FIELDS.contains(field) && (value.isEmpty() || value.matches("\\d+([.,]\\d+)?"));
        };
        if (!valid) throw ImportFailure.invalid("Проверьте значения подхода.");
    }
}
