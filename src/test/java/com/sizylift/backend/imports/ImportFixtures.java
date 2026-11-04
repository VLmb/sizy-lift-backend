package com.sizylift.backend.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ответы модели в компактном формате (схема program-import.compact.v1). */
final class ImportFixtures {
    static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");
    /** Заменяется alias приседа из каталога, переданного модели. */
    static final String SQUAT_ALIAS = "@SQUAT";
    static final String TEXT = "Присед со штангой 3×5 100 кг @8";
    static final String DEFAULT_SET = "n=3 r=5 kg=100 rpe=8 type=working";
    private ImportFixtures() { }

    static Map<String, Object> matched(String ref) {
        return exercise("e1", "Присед", "matched", SQUAT_ALIAS, List.of(), "", "", List.of(ref));
    }
    static Map<String, Object> ambiguous(String ref) {
        return exercise("e1", "Присед", "ambiguous", "", List.of(SQUAT_ALIAS), "", "", List.of(ref));
    }
    static Map<String, Object> custom(String id, String name, String type) {
        return exercise(id, name, "new", "", List.of(), name, type, List.of("text:1"));
    }
    static Map<String, Object> exercise(String id, String name, String status, String match, List<String> candidates,
                                        String newName, String resultType, List<String> refs) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("name", name); result.put("status", status); result.put("match", match);
        result.put("candidates", new ArrayList<>(candidates)); result.put("newName", newName);
        result.put("resultType", resultType); result.put("refs", new ArrayList<>(refs));
        return result;
    }
    static Map<String, Object> clarification(String question) {
        var result = new LinkedHashMap<String, Object>();
        result.put("question", question); result.put("name", ""); result.put("description", "");
        result.put("exercises", new ArrayList<>()); result.put("weeks", new ArrayList<>());
        return result;
    }
    @SafeVarargs
    static Map<String, Object> draft(Map<String, Object>... exercises) {
        var blocks = new ArrayList<Object>();
        for (var exercise : exercises) {
            var item = new LinkedHashMap<String, Object>();
            item.put("ex", exercise.get("id")); item.put("note", "");
            item.put("refs", new ArrayList<>((List<?>) exercise.get("refs"))); item.put("sets", new ArrayList<>(List.of(DEFAULT_SET)));
            var block = new LinkedHashMap<String, Object>();
            block.put("type", "single"); block.put("label", ""); block.put("title", ""); block.put("note", "");
            block.put("items", new ArrayList<>(List.of(item)));
            blocks.add(block);
        }
        var day = new LinkedHashMap<String, Object>();
        day.put("day", 0); day.put("title", "Тренировка"); day.put("blocks", blocks);
        var week = new LinkedHashMap<String, Object>();
        week.put("week", 1); week.put("cycle", "Цикл"); week.put("name", ""); week.put("days", new ArrayList<>(List.of(day)));
        var result = new LinkedHashMap<String, Object>();
        result.put("question", ""); result.put("name", "Программа"); result.put("description", "");
        result.put("exercises", new ArrayList<>(List.of(exercises))); result.put("weeks", new ArrayList<>(List.of(week)));
        return result;
    }
    /** Первое упражнение первой тренировки: refs, note, sets. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> firstItem(Map<String, Object> draft) {
        var week = (Map<String, Object>) ((List<?>) draft.get("weeks")).getFirst();
        var day = (Map<String, Object>) ((List<?>) week.get("days")).getFirst();
        var block = (Map<String, Object>) ((List<?>) day.get("blocks")).getFirst();
        return (Map<String, Object>) ((List<?>) block.get("items")).getFirst();
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> firstExercise(Map<String, Object> draft) {
        return (Map<String, Object>) ((List<?>) draft.get("exercises")).getFirst();
    }
}
