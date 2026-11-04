package com.sizylift.backend.imports.dto;

import java.util.List;

/**
 * Компактные ответы модели. Сервер переводит их в {@link ImportDraftV1}, назначает ключи узлов,
 * восстанавливает UUID каталога и вычисляет циклы и dayOffset. Подход — строка
 * {@code "n=3 r=5 kg=100 rpe=8 | заметка"}, разбор и проверку выполняет сервер.
 */
public final class ImportModelDraft {
    private ImportModelDraft() { }

    /** emit_program_plan, схема {@code imports/program-import.plan.v1.schema.json}. */
    public record Plan(String question, String name, String description, List<Exercise> exercises,
                       List<PlanWeek> plan, List<Week> weeks) { }
    /** emit_program_weeks, схема {@code imports/program-import.weeks.v1.schema.json}; exercises — только новые. */
    public record Weeks(List<Exercise> exercises, List<Week> weeks) { }

    public record Exercise(String id, String name, String status, String match, List<String> candidates,
                           String newName, String resultType, List<String> refs) { }
    /** week — номер недели, общий для всей программы; соседние недели с одинаковым cycle образуют цикл. */
    public record PlanWeek(int week, String cycle, String name, int workouts) { }
    public record Week(int week, List<Day> days) { }
    /** day — дни от начала недели: неделя N начинается через 7·(N−1) дней от даты старта. */
    public record Day(int day, String title, List<Block> blocks) { }
    public record Block(String type, String label, String title, String note, List<Item> items) { }
    public record Item(String ex, String note, List<String> refs, List<String> sets) { }
}
