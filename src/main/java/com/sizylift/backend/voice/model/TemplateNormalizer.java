package com.sizylift.backend.voice.model;

/**
 * Модель, которая переписывает фразу в строгий шаблон (docs/voice.md, «Разбор шаблоном»). Вход — инструкция с
 * каталогом и фраза, выход — текст шаблона. Шаблону не верим на слово: его разбирает строгий разборщик, числа
 * сверяются с фразой.
 */
public interface TemplateNormalizer {
    record Result(String template, int inputTokens, int cachedInputTokens, int outputTokens) { }

    /** При таймауте, ошибке провайдера или пустом ответе — {@link Unavailable}. */
    Result normalize(String instructions, String phrase);

    class Unavailable extends RuntimeException {
        public Unavailable() { super("Модель шаблона недоступна"); }
    }
}
