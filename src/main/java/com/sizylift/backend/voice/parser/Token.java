package com.sizylift.backend.voice.parser;

/**
 * Слово или число фразы после нормализации. {@code from}/{@code to} — диапазон исходных слов, из которых
 * собран токен (число «восемьдесят два с половиной» — четыре слова); по нему восстанавливается текст дырки.
 */
final class Token {
    enum Kind {
        /** Число; {@code value}. */
        NUMBER,
        /** Единица измерения без числа; {@code unit}. */
        UNIT,
        /** Число с единицей: «3 подхода», «80 кг», «2 минуты» (уже в секундах); {@code unit}, {@code value}. */
        QUANTITY,
        /** «вес», «блин»: следующее число — вес. */
        WEIGHT_WORD,
        RPE, RIR,
        /** «отдых», «пауза» (пауза — отдых только перед временем). */
        REST, PAUSE,
        /** «до отказа». */
        TO_FAILURE,
        WARMUP, WORKING,
        /** «нет», «вернее»: отменяет предыдущий подход. */
        CORRECTION,
        /** «на каждую сторону» — заметка к подходу. */
        EACH_SIDE,
        /** Заметка к подходу словами диктовки: «вес подберёт сам». */
        NOTE,
        /** «в запасе»: число рядом — RIR. */
        RESERVE,
        /** «везде»: отдых или RPE для всех. */
        EVERYWHERE,
        /** «последний», «первый» подход с отличием от остальных. */
        LAST, FIRST,
        /** «от максимума» после процентов. */
        PERCENT_BASE,
        PREPOSITION,
        /** Связки и слова-паразиты: «потом», «ну», «завтра». */
        FILLER,
        /** Знак препинания, если сервис его поставил. */
        SEPARATOR,
        /** Месяц: «13 ноября» — дата, игнорируется. */
        MONTH,
        /** Неизвестное слово: возможная часть названия или дырка. */
        WORD
    }

    enum Unit { SETS, REPS, KG, SECONDS, METERS, PERCENT }

    final int from;
    final int to;
    final String text;
    final String stem;
    Kind kind;
    double value;
    Unit unit;
    boolean covered;

    Token(int from, int to, String text, String stem, Kind kind) {
        this.from = from;
        this.to = to;
        this.text = text;
        this.stem = stem;
        this.kind = kind;
    }

    boolean is(Kind k) { return kind == k; }

    boolean isQuantity(Unit u) { return kind == Kind.QUANTITY && unit == u; }

    /** Слова без значения для тренировки: из них одних дырка не складывается. */
    boolean isNoise() { return kind == Kind.FILLER || kind == Kind.SEPARATOR || kind == Kind.PREPOSITION; }

    @Override public String toString() { return kind + "(" + text + (kind == Kind.WORD ? "" : ":" + value) + ")"; }
}
