package com.sizylift.backend.voice.parser;

import java.util.ArrayList;
import java.util.List;

import com.sizylift.backend.voice.parser.ParsedWorkout.SetLine;

/**
 * Подходы под тип результата упражнения: значения, которых у типа нет, убираются и попадают в issues.
 * Нужен, когда упражнение выбрано после разбора — моделью или заменой.
 */
public final class SetLineFit {
    private static final String WEIGHT_REPS = "weight_reps";
    private static final String ADDED_WEIGHT_REPS = "added_weight_reps";
    private static final String REPS = "reps";
    private static final String DURATION = "duration";
    private static final String DISTANCE_TIME = "distance_time";
    private static final String FREE = "free";

    private SetLineFit() { }

    public static List<SetLine> fit(List<SetLine> lines, String resultType, List<String> issues) {
        if (resultType == null) return lines;
        boolean strength = WEIGHT_REPS.equals(resultType) || ADDED_WEIGHT_REPS.equals(resultType);
        boolean counted = strength || REPS.equals(resultType);
        boolean duration = DURATION.equals(resultType);
        boolean distance = DISTANCE_TIME.equals(resultType);
        if (!counted && !duration && !distance && !FREE.equals(resultType)) return lines;
        var out = new ArrayList<SetLine>();
        for (var l : lines) {
            out.add(new SetLine(l.count(),
                    keep(l.reps(), counted, "r", resultType, issues), keep(l.repsMax(), counted, "r", resultType, issues),
                    l.toFailure() && counted, keep(l.weightKg(), strength, "kg", resultType, issues),
                    keep(l.percent(), strength, "pct", resultType, issues),
                    keep(l.seconds(), duration || distance, "sec", resultType, issues),
                    keep(l.meters(), distance, "m", resultType, issues),
                    keep(l.rpe(), counted || duration, "rpe", resultType, issues),
                    keep(l.rir(), counted, "rir", resultType, issues), l.type(), l.note()));
            if (l.toFailure() && !counted) issues.add("до отказа не подходит типу " + resultType);
        }
        return out;
    }

    private static <T> T keep(T value, boolean allowed, String field, String type, List<String> issues) {
        if (value == null || allowed) return value;
        issues.add(field + "=" + value + " не подходит типу " + type);
        return null;
    }
}
