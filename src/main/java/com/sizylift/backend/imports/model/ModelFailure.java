package com.sizylift.backend.imports.model;

import java.time.Duration;

/** Не содержит тело запроса, ответа или исходное исключение HTTP-клиента. */
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
