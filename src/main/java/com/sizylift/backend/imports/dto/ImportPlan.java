package com.sizylift.backend.imports.dto;

import java.util.List;

/**
 * Каркас программы после первого вызова: упражнения уже сопоставлены с каталогом, недели описаны номером, циклом,
 * названием и днями. Подробно недели разбираются частями по этому каркасу. Хранится в import_session.outline, пока
 * сессия обрабатывается: после перезапуска worker и при повторном разборе недель план не запрашивается заново.
 * {@code rules} — правила чтения источника, которые модель вывела по всей программе: части следуют им.
 * {@code copyOf} — неделя повторяет другую («Неделя: 2-4 | как=1») и моделью не разбирается.
 */
public record ImportPlan(String name, String description, List<ImportDraftV1.Resolution> exercises, List<Week> weeks,
                         List<String> rules) {
    public ImportPlan {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public ImportPlan(String name, String description, List<ImportDraftV1.Resolution> exercises, List<Week> weeks) {
        this(name, description, exercises, weeks, List.of());
    }

    public record Week(int week, String cycle, String name, int workouts, List<String> days, Integer copyOf) {
        public Week {
            days = days == null ? List.of() : List.copyOf(days);
        }
    }

    public int workouts() { return weeks.stream().filter(w -> w.copyOf() == null).mapToInt(w -> Math.max(1, w.workouts())).sum(); }
}
