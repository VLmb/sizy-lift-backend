package com.sizylift.backend.imports.entity;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonValue;

/** Состояние сессии импорта; {@code pending} — идёт вызов модели. */
public enum ImportStatus {

    PENDING("pending"),
    QUESTIONS("questions"),
    DRAFT("draft"),
    FAILED("failed");

    private final String code;

    ImportStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static ImportStatus of(String code) {
        return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown import status"));
    }
}
