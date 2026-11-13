package com.sizylift.backend.voice.parser;

import java.util.List;

/**
 * Итог разбора фразы: упражнения по порядку и куски, которые не удалось отнести ни к одному упражнению.
 * Отсутствующее значение ({@code null}) — «не сказано»: ничего не подставляется.
 */
public record ParsedWorkout(List<Item> items, List<String> unrecognized) {

    public enum Confidence { HIGH, MEDIUM, LOW }

    /**
     * Упражнение. {@code exerciseId == null} — название не найдено в словаре, {@code spoken} — как сказано,
     * {@code candidates} — ближайшие упражнения. {@code unrecognized} — непонятые куски его параметров,
     * {@code issues} — значения, которые не подходят типу упражнения и поэтому не записаны в подходы.
     */
    public record Item(String exerciseId, String exerciseName, String resultType, String spoken, Confidence confidence,
                       List<SetLine> lines, Integer restSeconds, List<String> unrecognized, List<String> issues,
                       List<Candidate> candidates) { }

    public record Candidate(String exerciseId, String name) { }

    /**
     * Строка одинаковых подходов: {@code count} подходов (null — число не названо). {@code type} — warmup или
     * working, если это было сказано.
     */
    public record SetLine(Integer count, Integer reps, boolean toFailure, Double weightKg, Double percent,
                          Integer seconds, Integer meters, Double rpe, Double rir, String type, String note) { }
}
