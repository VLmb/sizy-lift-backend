package com.sizylift.backend.imports.model;

import java.time.Duration;

/**
 * Сбой вызова модели. Не содержит тело запроса, ответа или исходное исключение HTTP-клиента.
 * Обрезанный по лимиту ответ — не сбой: готовые строки используются ({@link ProgramParserModel.Result#truncated()}).
 */
public class ModelFailure extends RuntimeException {
    private final boolean retryable;
    private final Duration retryAfter;

    public ModelFailure(boolean retryable, Duration retryAfter) {
        super("Ошибка обработки у провайдера");
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public boolean retryable() { return retryable; }
    public Duration retryAfter() { return retryAfter; }
}
