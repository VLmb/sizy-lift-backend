package com.sizylift.backend.imports.dto;

import java.util.List;

/**
 * Компактный ответ модели: схема {@code imports/program-import.compact.v1.schema.json}.
 * Сервер переводит его в {@link ImportDraftV1}, назначает ключи узлов и восстанавливает UUID каталога.
 * Подход — строка {@code "n=3 r=5 kg=100 rpe=8 | заметка"}, разбор и проверку выполняет сервер.
 */
public record ImportModelDraft(String question, String name, String description,
                               List<Exercise> exercises, List<Week> weeks) {
    public record Exercise(String id, String name, String status, String match, List<String> candidates,
                           String newName, String resultType, List<String> refs) { }
    /** week — номер недели, общий для всей программы; соседние недели с одинаковым cycle образуют цикл. */
    public record Week(int week, String cycle, String name, List<Day> days) { }
    /** day — дни от начала недели: неделя N начинается через 7·(N−1) дней от даты старта. */
    public record Day(int day, String title, List<Block> blocks) { }
    public record Block(String type, String label, String title, String note, List<Item> items) { }
    public record Item(String ex, String note, List<String> refs, List<String> sets) { }
}
