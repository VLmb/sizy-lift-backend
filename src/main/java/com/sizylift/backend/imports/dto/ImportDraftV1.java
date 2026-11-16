package com.sizylift.backend.imports.dto;

import java.util.List;

/**
 * Черновик импорта в API и хранении; сервер строит его из строк шаблона модели (docs/imports.md, «Шаблон»).
 * Пустая строка числового поля означает неизвестное значение, а не ноль.
 * {@code plan} — каркас программы: все недели с днями и состоянием разбора, пока недели заполняются и после;
 * {@code warnings} — что сервер пропустил или поправил при разборе ответа модели;
 * {@code rules} — правила чтения источника из каркаса («Правило:»), которым следуют все части разбора.
 */
public record ImportDraftV1(int schemaVersion, boolean needsClarification, String clarificationMessage,
                            Draft programDraft, List<Resolution> exerciseResolutions, List<PlannedWeek> plan,
                            List<String> warnings, List<String> rules) {
    public static final String PENDING = "pending";
    public static final String ACTIVE = "active";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    public ImportDraftV1 {
        plan = plan == null ? List.of() : List.copyOf(plan);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public ImportDraftV1(int schemaVersion, boolean needsClarification, String clarificationMessage,
                         Draft programDraft, List<Resolution> exerciseResolutions) {
        this(schemaVersion, needsClarification, clarificationMessage, programDraft, exerciseResolutions, List.of(), List.of(), List.of());
    }

    /**
     * Неделя каркаса. {@code status}: pending — ещё не разобрана, active — разбирается сейчас, done — готова,
     * failed — разобрать не удалось (её можно разобрать заново). {@code copyOf} — номер недели, которую она повторяет.
     */
    public record PlannedWeek(int number, String cycle, String name, List<String> days, Integer copyOf, String status) { }

    public boolean hasFailedWeeks() { return plan.stream().anyMatch(week -> FAILED.equals(week.status())); }

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
