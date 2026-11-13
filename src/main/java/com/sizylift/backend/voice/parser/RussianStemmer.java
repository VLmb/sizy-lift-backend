package com.sizylift.backend.voice.parser;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Русский стеммер Snowball: отрезает окончания по фиксированным правилам, без словаря.
 * Нужен, чтобы «приседе», «приседа» и «присед» сравнивались как одна основа.
 * Ожидает нижний регистр и «е» вместо «ё».
 */
final class RussianStemmer {
    private static final String VOWELS = "аеиоуыэюя";
    private static final String A_OR_YA = "ая";
    private static final String[] PERFECTIVE_AFTER_A = sorted("в", "вши", "вшись");
    private static final String[] PERFECTIVE = sorted("ив", "ивши", "ившись", "ыв", "ывши", "ывшись");
    private static final String[] REFLEXIVE = sorted("ся", "сь");
    private static final String[] ADJECTIVE = sorted("ее", "ие", "ые", "ое", "ими", "ыми", "ей", "ий", "ый", "ой", "ем",
            "им", "ым", "ом", "его", "ого", "ему", "ому", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею");
    private static final String[] PARTICIPLE_AFTER_A = sorted("ем", "нн", "вш", "ющ", "щ");
    private static final String[] PARTICIPLE = sorted("ивш", "ывш", "ующ");
    private static final String[] VERB_AFTER_A = sorted("ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но",
            "ет", "ют", "ны", "ть", "ешь", "нно");
    private static final String[] VERB = sorted("ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил",
            "ыл", "им", "ым", "ен", "ило", "ыло", "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь", "ую", "ю");
    private static final String[] NOUN = sorted("а", "ев", "ов", "ие", "ье", "е", "иями", "ями", "ами", "еи", "ии", "и",
            "ией", "ей", "ой", "ий", "й", "иям", "ям", "ием", "ем", "ам", "ом", "о", "у", "ах", "иях", "ях", "ы", "ь", "ию",
            "ью", "ю", "ия", "ья", "я");
    private static final String[] SUPERLATIVE = sorted("ейш", "ейше");
    private static final String[] DERIVATIONAL = sorted("ост", "ость");

    private RussianStemmer() { }

    static String stem(String word) {
        int rv = regionAfterVowel(word, 0);
        if (rv >= word.length()) return word;
        int r1 = regionAfterConsonant(word, 0);
        int r2 = regionAfterConsonant(word, r1);
        var w = new StringBuilder(word);
        if (!removeGrouped(w, rv, PERFECTIVE_AFTER_A, PERFECTIVE)) {
            remove(w, rv, REFLEXIVE);
            if (!removeAdjectival(w, rv) && !removeGrouped(w, rv, VERB_AFTER_A, VERB)) remove(w, rv, NOUN);
        }
        if (endsIn(w, rv, "и")) w.setLength(w.length() - 1);
        remove(w, r2, DERIVATIONAL);
        if (endsIn(w, rv, "нн")) w.setLength(w.length() - 1);
        else if (remove(w, rv, SUPERLATIVE)) { if (endsIn(w, rv, "нн")) w.setLength(w.length() - 1); }
        else if (endsIn(w, rv, "ь")) w.setLength(w.length() - 1);
        return w.toString();
    }

    private static boolean removeAdjectival(StringBuilder w, int rv) {
        if (!remove(w, rv, ADJECTIVE)) return false;
        removeGrouped(w, rv, PARTICIPLE_AFTER_A, PARTICIPLE);
        return true;
    }

    /** Окончание первой группы должно стоять после «а» или «я» внутри RV; сама буква остаётся. */
    private static boolean removeGrouped(StringBuilder w, int rv, String[] afterA, String[] plain) {
        String best = null;
        for (var s : afterA) {
            int at = w.length() - s.length();
            if (endsIn(w, rv, s) && at - 1 >= rv && A_OR_YA.indexOf(w.charAt(at - 1)) >= 0) { best = s; break; }
        }
        for (var s : plain) {
            if (endsIn(w, rv, s) && (best == null || s.length() > best.length())) { best = s; break; }
        }
        if (best == null) return false;
        w.setLength(w.length() - best.length());
        return true;
    }

    private static boolean remove(StringBuilder w, int region, String[] suffixes) {
        for (var s : suffixes) {
            if (endsIn(w, region, s)) { w.setLength(w.length() - s.length()); return true; }
        }
        return false;
    }

    private static boolean endsIn(StringBuilder w, int region, String suffix) {
        int at = w.length() - suffix.length();
        return at >= region && w.indexOf(suffix, at) == at;
    }

    private static int regionAfterVowel(String w, int from) {
        for (int i = from; i < w.length(); i++) if (vowel(w.charAt(i))) return i + 1;
        return w.length();
    }

    private static int regionAfterConsonant(String w, int from) {
        for (int i = from + 1; i < w.length(); i++) {
            if (!vowel(w.charAt(i)) && vowel(w.charAt(i - 1))) return i + 1;
        }
        return w.length();
    }

    private static boolean vowel(char c) { return VOWELS.indexOf(c) >= 0; }

    private static String[] sorted(String... suffixes) {
        var copy = suffixes.clone();
        Arrays.sort(copy, Comparator.comparingInt(String::length).reversed());
        return copy;
    }
}
