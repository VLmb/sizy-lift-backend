package com.sizylift.backend.imports.template;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Дни тренировок внутри недели считает сервер, а не модель: модель пишет день как в источнике («Пн», «2»).
 * День недели ставится на ближайшую такую дату от начала недели; неделя N начинается через 7·(N−1) дней от даты
 * старта. Номера тренировок без дней недели раскладываются через день от начала недели (1 → 0, 2 → 2, 3 → 4, 4 → 6),
 * а если тренировок в неделе больше четырёх — подряд.
 */
public final class DayPlacement {
    public static final int DAYS_PER_WEEK = 7;
    private static final int EVERY_OTHER_DAY_LIMIT = 4;
    private static final int GAP = 2;
    private static final int PREFIX = 2;
    private static final Map<String, DayOfWeek> WEEKDAYS = Map.ofEntries(
            Map.entry("пн", DayOfWeek.MONDAY), Map.entry("по", DayOfWeek.MONDAY), Map.entry("mo", DayOfWeek.MONDAY),
            Map.entry("вт", DayOfWeek.TUESDAY), Map.entry("tu", DayOfWeek.TUESDAY),
            Map.entry("ср", DayOfWeek.WEDNESDAY), Map.entry("we", DayOfWeek.WEDNESDAY),
            Map.entry("чт", DayOfWeek.THURSDAY), Map.entry("че", DayOfWeek.THURSDAY), Map.entry("th", DayOfWeek.THURSDAY),
            Map.entry("пт", DayOfWeek.FRIDAY), Map.entry("пя", DayOfWeek.FRIDAY), Map.entry("fr", DayOfWeek.FRIDAY),
            Map.entry("сб", DayOfWeek.SATURDAY), Map.entry("су", DayOfWeek.SATURDAY), Map.entry("sa", DayOfWeek.SATURDAY),
            Map.entry("вс", DayOfWeek.SUNDAY), Map.entry("во", DayOfWeek.SUNDAY), Map.entry("su", DayOfWeek.SUNDAY));

    private DayPlacement() { }

    /** Смещения дней от начала недели (0–6) в порядке меток. */
    public static List<Integer> offsets(List<String> labels, LocalDate startDate) {
        int numbered = (int) labels.stream().filter(label -> weekday(label).isEmpty()).count();
        var result = new ArrayList<Integer>(labels.size());
        int index = 0;
        for (var label : labels) {
            var weekday = weekday(label);
            if (weekday.isPresent()) {
                result.add(Math.floorMod(weekday.get().getValue() - startDate.getDayOfWeek().getValue(), DAYS_PER_WEEK));
            } else {
                int offset = numbered <= EVERY_OTHER_DAY_LIMIT ? index * GAP : index;
                result.add(Math.min(offset, DAYS_PER_WEEK - 1));
                index++;
            }
        }
        return result;
    }

    static Optional<DayOfWeek> weekday(String label) {
        var text = label.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (text.length() < PREFIX) return Optional.empty();
        return Optional.ofNullable(WEEKDAYS.get(text.substring(0, PREFIX)));
    }
}
