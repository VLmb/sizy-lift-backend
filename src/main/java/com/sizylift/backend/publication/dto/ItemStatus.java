package com.sizylift.backend.publication.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** Итог публикации агрегата. */
public enum ItemStatus {

    /** Версия сохранена. */
    ACCEPTED("accepted"),
    /** Сохранена эта или более новая версия; повторять не нужно. */
    STALE("stale"),
    /** Агрегат не принят; повтор имеет смысл только после обновления приложения. */
    REJECTED("rejected");

    private final String code;

    ItemStatus(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
