package com.sizylift.backend.voice.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.sizylift.backend.voice.parser.RussianNumerals.Order;
import com.sizylift.backend.voice.parser.Token.Kind;
import com.sizylift.backend.voice.parser.Token.Unit;

/**
 * Нормализация фразы: слова, числа из цифр и числительных, служебные слова. Величины с единицами
 * ({@link #quantities}) собираются после поиска названий, чтобы «подтягивания с весом» остались названием.
 */
final class TextTokenizer {
    private static final Pattern RAW = Pattern.compile("\\d+(?:[.,]\\d+)?|[a-zа-я]+|%|[,.;:!?…—–]");
    private static final Pattern DIGITS = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern SEPARATORS = Pattern.compile("[,.;:!?…—–]");
    private static final String HALF_PREFIX = "пол";
    private static final String WITH = "с";
    private static final int MAX_PHRASE = 3;

    /** Исходные слова фразы: по ним собирается текст дырок. */
    record Result(List<String> words, List<Token> tokens) {
        String text(int from, int to) {
            var b = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (SEPARATORS.matcher(words.get(i)).matches()) continue;
                if (!b.isEmpty()) b.append(' ');
                b.append(words.get(i));
            }
            return b.toString();
        }
    }

    private TextTokenizer() { }

    static Result tokenize(String text) {
        var words = words(text);
        return new Result(words, classify(words));
    }

    static List<String> words(String text) {
        var normalized = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        var m = RAW.matcher(normalized);
        var words = new ArrayList<String>();
        while (m.find()) {
            var w = m.group();
            var rest = w.startsWith(HALF_PREFIX) ? w.substring(HALF_PREFIX.length()) : "";
            // «полминуты» — половина и единица.
            if (!rest.isEmpty() && isUnit(rest)) { words.add(HALF_PREFIX); words.add(rest); }
            else words.add(w);
        }
        return words;
    }

    private static boolean isUnit(String w) {
        var e = Lexicon.find(w, RussianStemmer.stem(w));
        return e != null && e.kind() == Kind.UNIT;
    }

    private static List<Token> classify(List<String> words) {
        var out = new ArrayList<Token>();
        int i = 0;
        while (i < words.size()) {
            int next = phrase(words, i, out);
            if (next == i) next = number(words, i, out);
            if (next == i) next = word(words, i, out);
            i = next;
        }
        return out;
    }

    private static int phrase(List<String> words, int i, List<Token> out) {
        for (int len = MAX_PHRASE; len >= 2; len--) {
            if (i + len > words.size()) continue;
            var e = Lexicon.PHRASES.get(words.subList(i, i + len));
            if (e != null) {
                out.add(new Token(i, i + len, String.join(" ", words.subList(i, i + len)), null, e.kind()));
                return i + len;
            }
        }
        return i;
    }

    /** Цифры или группа числительных со склейкой разрядов, тысячами и «с половиной». */
    private static int number(List<String> words, int i, List<Token> out) {
        var w = words.get(i);
        double value;
        int j;
        if (DIGITS.matcher(w).matches()) {
            value = Double.parseDouble(w.replace(',', '.'));
            j = i + 1;
        } else {
            var first = RussianNumerals.find(w);
            if (first == null) return i;
            value = first.value();
            var last = first.order();
            j = i + 1;
            while (j < words.size()) {
                var n = RussianNumerals.find(words.get(j));
                if (n == null || !RussianNumerals.joins(last, n.order())) break;
                value += n.value();
                last = n.order();
                j++;
            }
            if (last != Order.THOUSAND && last != Order.FRACTION && j < words.size()) {
                var t = RussianNumerals.find(words.get(j));
                if (t != null && t.order() == Order.THOUSAND) {
                    value *= t.value();
                    j++;
                }
            }
        }
        if (j + 1 < words.size() && words.get(j).equals(WITH) && RussianNumerals.addition(words.get(j + 1)) > 0) {
            value += RussianNumerals.addition(words.get(j + 1));
            j += 2;
        }
        var t = new Token(i, j, String.join(" ", words.subList(i, j)), null, Kind.NUMBER);
        t.value = value;
        out.add(t);
        return j;
    }

    private static int word(List<String> words, int i, List<Token> out) {
        var w = words.get(i);
        if (SEPARATORS.matcher(w).matches()) {
            out.add(new Token(i, i + 1, w, null, Kind.SEPARATOR));
            return i + 1;
        }
        var stem = RussianStemmer.stem(w);
        var e = Lexicon.find(w, stem);
        var t = new Token(i, i + 1, w, stem, e == null ? Kind.WORD : e.kind());
        if (e != null) { t.unit = e.unit(); t.value = e.factor(); }
        out.add(t);
        return i + 1;
    }

    /** Число + единица → величина; «по минуте» → 60 с; «минут восемь» → 480 с; «с блином 20» → 20 кг; «13 ноября» — дата. */
    static List<Token> quantities(List<Token> in) {
        var out = new ArrayList<Token>();
        for (int i = 0; i < in.size(); i++) {
            var t = in.get(i);
            var next = i + 1 < in.size() ? in.get(i + 1) : null;
            var after = i + 2 < in.size() ? in.get(i + 2) : null;
            if (t.is(Kind.NUMBER) && next != null && next.is(Kind.UNIT)) {
                out.add(quantity(t, next, next.unit, t.value * next.value));
                i++;
            } else if (t.is(Kind.NUMBER) && next != null && next.is(Kind.MONTH)) {
                out.add(merged(t, next, Kind.FILLER));
                i++;
            } else if (t.is(Kind.UNIT) && measured(t.unit)) {
                boolean inverted = next != null && next.is(Kind.NUMBER) && (after == null || !after.is(Kind.UNIT));
                out.add(inverted ? quantity(t, next, t.unit, t.value * next.value) : quantity(t, t, t.unit, t.value));
                if (inverted) i++;
            } else if (t.is(Kind.WEIGHT_WORD) && next != null && next.is(Kind.NUMBER)) {
                boolean kg = after != null && after.is(Kind.UNIT) && after.unit == Unit.KG;
                out.add(quantity(t, kg ? after : next, Unit.KG, next.value));
                i += kg ? 2 : 1;
            } else {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean measured(Unit unit) { return unit == Unit.SECONDS || unit == Unit.METERS; }

    private static Token quantity(Token first, Token last, Unit unit, double value) {
        var q = merged(first, last, Kind.QUANTITY);
        q.unit = unit;
        q.value = value;
        return q;
    }

    private static Token merged(Token first, Token last, Kind kind) {
        return new Token(first.from, last.to, first == last ? first.text : first.text + " " + last.text, null, kind);
    }
}
