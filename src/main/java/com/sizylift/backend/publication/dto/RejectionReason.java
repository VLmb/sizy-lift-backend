package com.sizylift.backend.publication.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RejectionReason {

    UNSUPPORTED_TYPE("unsupported_type"),
    UNSUPPORTED_SCHEMA("unsupported_schema"),
    INVALID_PAYLOAD("invalid_payload"),
    /** Id занят системным упражнением или другим пользователем. */
    ID_CONFLICT("id_conflict"),
    /** Запись чужих данных, на которую у вызывающего нет права (sync v2). */
    FORBIDDEN("forbidden");

    private final String code;

    RejectionReason(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
