package com.sizylift.backend.imports.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/** Откуда пришла программа. */
public enum ImportSource {

    TEXT("text"),
    SHEET_URL("sheet_url"),
    FILE("file");

    private final String code;

    ImportSource(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
