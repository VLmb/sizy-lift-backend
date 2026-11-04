package com.sizylift.backend.imports.model;

import java.time.Duration;

/** Не содержит тело запроса, ответа или исходное исключение HTTP-клиента. */
public class ModelFailure extends RuntimeException {
    private final boolean retryable;
    private final Duration retryAfter;
    private final boolean truncated;
    private final int inputTokens;
    private final int outputTokens;

    public ModelFailure(boolean retryable, Duration retryAfter) {
        this(retryable, retryAfter, false, 0, 0);
    }

    private ModelFailure(boolean retryable, Duration retryAfter, boolean truncated, int inputTokens, int outputTokens) {
        super(truncated ? "Ответ модели не поместился в лимит токенов" : "Ошибка обработки у провайдера");
        this.retryable = retryable;
        this.retryAfter = retryAfter;
        this.truncated = truncated;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    /** Ответ обрезан по max_tokens. Повтор с тем же входом детерминированно обрежется снова. */
    public static ModelFailure truncated(int inputTokens, int outputTokens) {
        return new ModelFailure(false, Duration.ZERO, true, Math.max(0, inputTokens), Math.max(0, outputTokens));
    }

    public boolean retryable() { return retryable; }
    public Duration retryAfter() { return retryAfter; }
    public boolean truncated() { return truncated; }
    public int inputTokens() { return inputTokens; }
    public int outputTokens() { return outputTokens; }
}
