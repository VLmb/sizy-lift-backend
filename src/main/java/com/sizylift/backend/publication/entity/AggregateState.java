package com.sizylift.backend.publication.entity;

import java.time.Instant;

/**
 * Состояние агрегата на сервере. {@code version} — счётчик телефона для v1, {@code revision} — серверная
 * ревизия ({@code 0} у нового агрегата). Клиент записи известен только у записей через sync v2.
 */
public record AggregateState(long version, long revision, Instant changedAt, String sourceClientId,
                             Long sourceVersion, boolean deleted) {

    public boolean writtenBySync() {
        return sourceClientId != null;
    }
}
