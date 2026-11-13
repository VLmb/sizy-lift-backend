package com.sizylift.backend.voice.parser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sizylift.backend.voice.parser.ParsedWorkout.SetLine;
import com.sizylift.backend.voice.parser.Token.Kind;
import com.sizylift.backend.voice.parser.Token.Unit;

/**
 * Шаблоны параметров одного упражнения. Каждый шаблон помечает свои токены покрытыми; непокрытые
 * осмысленные токены становятся дырками. Значение попадает в текущую строку подходов, а если это поле
 * в ней уже заполнено — начинается новая строка: так «80 на 5, 85 на 3» дают две строки.
 */
final class ParameterParser {
    enum Field { COUNT, REPS, KG, PERCENT, SECONDS, METERS, RPE, RIR }

    record Result(List<SetLine> lines, Integer restSeconds, List<String> unrecognized, List<String> issues) { }

    private static final String PO = "по";
    private static final Set<String> WEIGHT_PREPOSITIONS = Set.of("на", "с", "со");
    private static final Set<String> TIMES = Set.of("х", "x");
    private static final String NA = "на";
    private static final String WARMUP = "warmup";
    private static final String WORKING = "working";
    private static final String EACH_SIDE_NOTE = "на каждую сторону";
    /** «отдых 2» — минуты, «отдых 90» — секунды. */
    private static final double REST_MINUTES_LIMIT = 10;
    private static final int SECONDS_PER_MINUTE = 60;
    /** Больше подходов одной строкой не диктуют: «12 на 5» — это вес, а не подходы. */
    private static final double MAX_SETS = 10;
    private static final Set<Field> STRENGTH = EnumSet.of(Field.COUNT, Field.REPS, Field.KG, Field.PERCENT, Field.RPE, Field.RIR);
    private static final Map<String, Set<Field>> ALLOWED = Map.of(
            "weight_reps", STRENGTH,
            "added_weight_reps", STRENGTH,
            "duration", EnumSet.of(Field.COUNT, Field.SECONDS, Field.RPE),
            "distance_time", EnumSet.of(Field.COUNT, Field.SECONDS, Field.METERS),
            "free", EnumSet.of(Field.COUNT));

    private static final class Line {
        final Double[] values = new Double[Field.values().length];
        boolean toFailure;
        /** Строка из пары «80 на 5»: без названного числа подходов это один подход. */
        boolean pair;
        String type;
        String note;
        boolean has(Field f) { return values[f.ordinal()] != null || f == Field.REPS && toFailure; }
    }

    private final List<Token> tokens;
    private final TextTokenizer.Result text;
    private final String resultType;
    private final List<Line> lines = new ArrayList<>();
    private Line current;
    private String mode;
    private Integer rest;

    private ParameterParser(List<Token> tokens, TextTokenizer.Result text, String resultType) {
        this.tokens = tokens;
        this.text = text;
        this.resultType = resultType;
    }

    static Result parse(List<Token> tokens, TextTokenizer.Result text, String resultType) {
        return new ParameterParser(tokens, text, resultType).run();
    }

    /** Есть ли в токенах значение, с которого начинаются параметры. */
    static boolean startsParameters(Token t) {
        return switch (t.kind) {
            case NUMBER, QUANTITY, UNIT, TO_FAILURE, RPE, RIR, WARMUP, WORKING, REST -> true;
            default -> false;
        };
    }

    private Result run() {
        int i = 0;
        while (i < tokens.size()) i = step(i);
        var issues = new ArrayList<String>();
        var out = lines.stream().map(l -> toSetLine(l, issues)).toList();
        return new Result(out, rest, gaps(), issues);
    }

    private int step(int i) {
        var t = tokens.get(i);
        return switch (t.kind) {
            case SEPARATOR, FILLER, PERCENT_BASE, MONTH -> cover(i, i);
            case WARMUP -> { mode = WARMUP; current = null; yield cover(i, i); }
            case WORKING -> { mode = WORKING; current = null; yield cover(i, i); }
            case CORRECTION -> { if (!lines.isEmpty()) lines.removeLast(); current = null; yield cover(i, i); }
            case TO_FAILURE -> { line(Field.REPS).toFailure = true; yield cover(i, i); }
            case EACH_SIDE -> { (current == null ? newLine() : current).note = EACH_SIDE_NOTE; yield cover(i, i); }
            case RPE, RIR -> score(i, t.is(Kind.RPE) ? Field.RPE : Field.RIR);
            case REST, PAUSE -> rest(i);
            case QUANTITY -> quantity(i);
            case NUMBER -> number(i);
            case PREPOSITION -> preposition(i);
            default -> i + 1;
        };
    }

    private int score(int i, Field field) {
        var n = at(i + 1);
        if (n == null || !n.is(Kind.NUMBER)) return i + 1;
        set(field, n.value);
        return cover(i, i + 1);
    }

    /** «отдых 2 минуты», «отдых между подходами 90», «пауза минута». */
    private int rest(int i) {
        int j = restValueIndex(i);
        var v = at(j);
        if (v != null && v.isQuantity(Unit.SECONDS)) {
            rest = (int) Math.round(v.value);
            return cover(i, j);
        }
        if (v != null && v.is(Kind.NUMBER) && tokens.get(i).is(Kind.REST) && !pairFollows(j)) {
            rest = (int) Math.round(v.value <= REST_MINUTES_LIMIT ? v.value * SECONDS_PER_MINUTE : v.value);
            return cover(i, j);
        }
        return i + 1;
    }

    private int restValueIndex(int restIndex) {
        int j = restIndex + 1;
        while (at(j) != null && (at(j).is(Kind.PREPOSITION) || at(j).is(Kind.FILLER)
                || at(j).is(Kind.UNIT) && at(j).unit == Unit.SETS)) j++;
        return j;
    }

    private boolean restHasValue(int restIndex) {
        int j = restValueIndex(restIndex);
        var v = at(j);
        return v != null && (v.isQuantity(Unit.SECONDS) || v.is(Kind.NUMBER) && !pairFollows(j));
    }

    private int quantity(int i) {
        var q = tokens.get(i);
        switch (q.unit) {
            case SETS -> {
                set(Field.COUNT, q.value);
                var p = at(i + 1);
                var v = at(i + 2);
                if (p != null && p.is(Kind.PREPOSITION) && PO.equals(p.text) && v != null) {
                    if (v.is(Kind.NUMBER)) { set(Field.REPS, v.value); return cover(i, i + 2); }
                    if (v.is(Kind.QUANTITY)) { cover(i, i + 1); return quantity(i + 2); }
                }
            }
            case SECONDS -> {
                int j = i + 1;
                while (at(j) != null && (at(j).is(Kind.PREPOSITION) || at(j).is(Kind.FILLER))) j++;
                // «две минуты отдыха» — отдых; но в «по минуте, отдых 30 секунд» у отдыха своё значение.
                if (at(j) != null && at(j).is(Kind.REST) && !restHasValue(j)) {
                    rest = (int) Math.round(q.value);
                    return cover(i, j);
                }
                set(Field.SECONDS, q.value);
            }
            case REPS -> set(Field.REPS, q.value);
            case KG -> set(Field.KG, q.value);
            case PERCENT -> set(Field.PERCENT, q.value);
            case METERS -> set(Field.METERS, q.value);
        }
        return cover(i, i);
    }

    /** «3 по 5», «3 х 5», «80 на 5»; одиночное число без шаблона остаётся дыркой. */
    private int number(int i) {
        var a = tokens.get(i);
        var p = at(i + 1);
        var b = at(i + 2);
        if (p == null || !p.is(Kind.PREPOSITION) || b == null) return i + 1;
        if ((PO.equals(p.text) || TIMES.contains(p.text)) && b.is(Kind.NUMBER)) {
            set(Field.COUNT, a.value);
            set(Field.REPS, b.value);
            return cover(i, i + 2);
        }
        if (PO.equals(p.text) && b.is(Kind.QUANTITY)) {
            set(Field.COUNT, a.value);
            cover(i, i + 1);
            return quantity(i + 2);
        }
        // «5 на 5 по 100»: за парой идёт вес, а первое число похоже на подходы — это подходы × повторы.
        if (NA.equals(p.text) && b.is(Kind.NUMBER) && a.value <= MAX_SETS && weightFollows(i + 3)) {
            set(Field.COUNT, a.value);
            set(Field.REPS, b.value);
            return cover(i, i + 2);
        }
        if (NA.equals(p.text) && (b.is(Kind.NUMBER) || b.isQuantity(Unit.REPS))) {
            if (current == null || current.has(Field.KG) || current.has(Field.REPS)) newLine();
            current.values[Field.KG.ordinal()] = a.value;
            current.values[Field.REPS.ordinal()] = b.value;
            current.pair = true;
            return cover(i, i + 2);
        }
        return i + 1;
    }

    /** «по 5» — повторы; «на 60», «с 80» — вес; перед величиной или RPE предлог просто покрывается. */
    private int preposition(int i) {
        var p = tokens.get(i);
        var n = at(i + 1);
        if (n == null) return i + 1;
        if (n.is(Kind.QUANTITY) || n.is(Kind.RPE) || n.is(Kind.RIR) || n.is(Kind.TO_FAILURE)) return cover(i, i);
        if (!n.is(Kind.NUMBER)) return i + 1;
        if (PO.equals(p.text)) {
            // «три по пять по сто»: повторы уже названы, второе «по» — вес.
            boolean weight = current != null && current.has(Field.REPS) && !current.has(Field.KG);
            set(weight ? Field.KG : Field.REPS, n.value);
            return cover(i, i + 1);
        }
        if (WEIGHT_PREPOSITIONS.contains(p.text)) {
            if (pairFollows(i + 1)) return cover(i, i);
            set(Field.KG, n.value);
            return cover(i, i + 1);
        }
        return i + 1;
    }

    private boolean weightFollows(int k) {
        var p = at(k);
        var n = at(k + 1);
        if (p != null && p.isQuantity(Unit.KG)) return true;
        return p != null && p.is(Kind.PREPOSITION) && (PO.equals(p.text) || WEIGHT_PREPOSITIONS.contains(p.text))
                && n != null && (n.isQuantity(Unit.KG) || n.is(Kind.NUMBER) && !pairFollows(k + 1));
    }

    /** За числом идёт «на N» или «х N» — это пара «вес на повторы» или «подходы х повторы». */
    private boolean pairFollows(int numberIndex) {
        var p = at(numberIndex + 1);
        var b = at(numberIndex + 2);
        return p != null && p.is(Kind.PREPOSITION) && (NA.equals(p.text) || TIMES.contains(p.text) || PO.equals(p.text))
                && b != null && b.is(Kind.NUMBER);
    }

    private void set(Field f, double value) { line(f).values[f.ordinal()] = value; }

    private Line line(Field f) {
        if (current == null || current.has(f)) newLine();
        return current;
    }

    private Line newLine() {
        current = new Line();
        current.type = mode;
        lines.add(current);
        return current;
    }

    private Token at(int i) { return i < tokens.size() ? tokens.get(i) : null; }

    private int cover(int from, int to) {
        for (int k = from; k <= to; k++) tokens.get(k).covered = true;
        return to + 1;
    }

    /** Подряд идущие непокрытые токены; кусок только из предлогов и связок дыркой не считается. */
    private List<String> gaps() {
        var out = new ArrayList<String>();
        int i = 0;
        while (i < tokens.size()) {
            if (tokens.get(i).covered) { i++; continue; }
            int j = i;
            boolean meaningful = false;
            while (j < tokens.size() && !tokens.get(j).covered) meaningful |= !tokens.get(j++).isNoise();
            if (meaningful) out.add(text.text(tokens.get(i).from, tokens.get(j - 1).to));
            i = j;
        }
        return out;
    }

    private SetLine toSetLine(Line l, List<String> issues) {
        var allowed = resultType == null ? EnumSet.allOf(Field.class) : ALLOWED.getOrDefault(resultType, EnumSet.allOf(Field.class));
        for (var f : Field.values()) {
            if (l.values[f.ordinal()] != null && !allowed.contains(f)) {
                issues.add(f.name().toLowerCase() + "=" + format(l.values[f.ordinal()]) + " не подходит типу " + resultType);
                l.values[f.ordinal()] = null;
            }
        }
        if (l.pair && l.values[Field.COUNT.ordinal()] == null) l.values[Field.COUNT.ordinal()] = 1.0;
        if (l.toFailure && !allowed.contains(Field.REPS)) { issues.add("до отказа не подходит типу " + resultType); l.toFailure = false; }
        return new SetLine(integer(l, Field.COUNT), integer(l, Field.REPS), l.toFailure, l.values[Field.KG.ordinal()],
                l.values[Field.PERCENT.ordinal()], integer(l, Field.SECONDS), integer(l, Field.METERS),
                l.values[Field.RPE.ordinal()], l.values[Field.RIR.ordinal()], l.type, l.note);
    }

    private static Integer integer(Line l, Field f) {
        var v = l.values[f.ordinal()];
        return v == null ? null : (int) Math.round(v);
    }

    static String format(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
