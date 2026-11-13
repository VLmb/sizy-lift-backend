package com.sizylift.backend.voice.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.sizylift.backend.voice.parser.ParsedWorkout.Confidence;
import com.sizylift.backend.voice.parser.ParsedWorkout.Item;
import com.sizylift.backend.voice.parser.ParsedWorkout.SetLine;

/**
 * Краткая запись результата для корпуса и консоли, по строке на упражнение:
 * {@code Присед со штангой | n=3 r=5 kg=80 | rest=120}. «~» — найдено с опечаткой, «?» — нет в словаре,
 * «! …» — непонятый кусок, «!! …» — значение не подходит типу упражнения.
 */
final class ParsedWorkoutText {
    private ParsedWorkoutText() { }

    static List<String> render(ParsedWorkout w) {
        var out = new ArrayList<String>();
        for (var item : w.items()) out.add(item(item));
        for (var u : w.unrecognized()) out.add("! " + u);
        return out;
    }

    private static String item(Item item) {
        var parts = new ArrayList<String>();
        parts.add(item.exerciseId() == null ? "? " + (item.spoken().isEmpty() ? "(без названия)" : item.spoken())
                : (item.confidence() == Confidence.HIGH ? "" : "~") + item.exerciseName());
        if (!item.lines().isEmpty()) parts.add(item.lines().stream().map(ParsedWorkoutText::line).collect(Collectors.joining(" ; ")));
        if (item.restSeconds() != null) parts.add("rest=" + item.restSeconds());
        for (var u : item.unrecognized()) parts.add("! " + u);
        for (var i : item.issues()) parts.add("!! " + i);
        return String.join(" | ", parts);
    }

    static String line(SetLine l) {
        var f = new ArrayList<String>();
        f.add("n=" + (l.count() == null ? "?" : l.count()));
        if (l.toFailure()) f.add("r=AMRAP");
        else if (l.reps() != null) f.add("r=" + l.reps());
        if (l.weightKg() != null) f.add("kg=" + ParameterParser.format(l.weightKg()));
        if (l.percent() != null) f.add("pct=" + ParameterParser.format(l.percent()));
        if (l.seconds() != null) f.add("sec=" + l.seconds());
        if (l.meters() != null) f.add("m=" + l.meters());
        if (l.rpe() != null) f.add("rpe=" + ParameterParser.format(l.rpe()));
        if (l.rir() != null) f.add("rir=" + ParameterParser.format(l.rir()));
        if (l.type() != null) f.add("type=" + l.type());
        if (l.note() != null) f.add("note=\"" + l.note() + "\"");
        return String.join(" ", f);
    }
}
