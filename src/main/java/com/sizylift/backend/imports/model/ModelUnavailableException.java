package com.sizylift.backend.imports.model;

/** Провайдер модели недоступен или отказал; {@code retryable} — сбой, который может пройти при повторе. */
public class ModelUnavailableException extends RuntimeException {

    private final boolean retryable;

    public ModelUnavailableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
