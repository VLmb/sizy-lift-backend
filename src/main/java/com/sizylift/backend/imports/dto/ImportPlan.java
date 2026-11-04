package com.sizylift.backend.imports.dto;

import java.util.List;

/**
 * План большой программы после первого вызова: упражнения уже проверены и переведены в Resolution,
 * недели описаны только номером, циклом, названием и числом тренировок. Подробно недели разбираются
 * отдельными вызовами по частям. Хранится в import_session.outline до конца раунда.
 */
public record ImportPlan(String name, String description, List<ImportDraftV1.Resolution> exercises, List<Week> weeks) {
    public record Week(int week, String cycle, String name, int workouts) { }

    public int workouts() { return weeks.stream().mapToInt(Week::workouts).sum(); }
}
