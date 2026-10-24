package com.sizylift.backend.sync.entity;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonValue;

/** Что случилось с агрегатом для получателя ленты. */
public enum FeedKind {

    /** Агрегат создан или изменён; в ленте отдаётся текущий payload. */
    UPSERT("upsert"),
    /** Агрегат удалён владельцем. */
    DELETE("delete"),
    /** Получатель потерял доступ (разрыв связи, этап 3); копию на телефоне нужно удалить. */
    REVOKE("revoke");

    private final String code;

    FeedKind(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static FeedKind of(String code) {
        return Arrays.stream(values()).filter(kind -> kind.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown feed kind"));
    }
}
