package com.sizylift.backend.voice.parser;

import java.util.List;

import com.sizylift.backend.voice.parser.Token.Kind;

/** Факты о куске текста для сверки с ответом модели: какие в нём основы слов и какие числа. */
public final class TextFacts {
    /** Одна исправленная буква на столько букв основы. */
    private static final int SIMILAR_LETTERS_PER_EDIT = 3;
    private TextFacts() { }

    /** Основы слов без предлогов: «Сгибание на скамье Скотта» → сгибан, скам, скотт. */
    public static List<String> stems(String text) { return ExerciseDictionary.stems(text); }

    /** Ключ личного синонима: одинаков для «в приседе со штангой» и «присед штанга». */
    public static String aliasKey(String text) { return String.join(" ", stems(text)); }

    /** Похожие основы: модель может поправить ошибку распознавания в слове («гонтели» → «гантели»). */
    public static boolean similar(String a, String b) {
        return ExerciseDictionary.distance(a, b) <= Math.max(1, Math.min(a.length(), b.length()) / SIMILAR_LETTERS_PER_EDIT);
    }

    /** Числа куска, словами и цифрами: «восемьдесят два с половиной на 5» → 82.5, 5. */
    public static List<Double> numbers(String text) {
        return TextTokenizer.tokenize(text).tokens().stream().filter(t -> t.is(Kind.NUMBER)).map(t -> t.value).toList();
    }
}
