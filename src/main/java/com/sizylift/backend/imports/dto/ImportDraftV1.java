package com.sizylift.backend.imports.dto;

import java.util.List;

/** Ответ модели. Пустая строка числового поля означает неизвестное значение, а не ноль. */
public record ImportDraftV1(int schemaVersion, boolean needsClarification, String clarificationMessage,
                            Draft programDraft, List<Resolution> exerciseResolutions) {
    public record Draft(String name, String description, List<Cycle> cycles) { }
    public record Cycle(String key, String name, List<Week> weeks) { }
    public record Week(String key, int number, String name, List<Day> workouts) { }
    /** dayOffset отсчитывается от выбранной пользователем даты старта всей программы. */
    public record Day(String key, String title, int dayOffset, List<Block> blocks) { }
    public record Block(String key, String blockType, String groupLabel, String title, String description,
                        List<Exercise> exercises) { }
    public record Exercise(String key, String resolutionKey, String comment, List<String> sourceRefs,
                           List<Prescription> sets) { }
    public record Prescription(String key, String count, String repetitions, String weightKg,
                                String percentOneRm, String durationSeconds, String distanceMeters,
                                String rpe, String rir, String restSeconds, String setType, String note,
                                List<String> sourceRefs) { }
    public record Resolution(String key, String originalName, String status, String exerciseId,
                              List<String> candidates, String proposedName, String proposedResultType,
                              List<String> sourceRefs) { }
}
