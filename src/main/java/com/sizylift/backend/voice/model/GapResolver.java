package com.sizylift.backend.voice.model;

/**
 * Запасная модель для кусков, которые не разобрал алгоритм. Вход — текст с разделами (см. GapPrompt),
 * выход — аргументы инструмента resolve_gaps в JSON. Ответ проверяется вызывающим: модели не верим на слово.
 */
public interface GapResolver {
    record Result(String arguments, int inputTokens, int outputTokens) { }

    /** При таймауте, ошибке провайдера или нарушении контракта — {@link Unavailable}. */
    Result resolve(String input);

    /** Выключенная модель не вызывается: непонятые куски остаются непонятыми. */
    default boolean enabled() { return true; }

    class Unavailable extends RuntimeException {
        public Unavailable() { super("Запасная модель недоступна"); }
    }
}
