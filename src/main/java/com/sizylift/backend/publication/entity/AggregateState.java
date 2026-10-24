package com.sizylift.backend.publication.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Состояние агрегата на сервере. {@code version} — счётчик телефона для v1, {@code revision} — серверная
 * ревизия ({@code 0} у нового агрегата). Клиент записи известен только у записей через sync v2.
 * {@code authorUserId} — кто единственный пишет агрегат; {@code null} — владелец.
 */
public record AggregateState(long version, long revision, Instant changedAt, String sourceClientId,
                             Long sourceVersion, boolean deleted, UUID authorUserId) {

    public boolean exists() {
        return revision > 0;
    }

    public boolean writtenBySync() {
        return sourceClientId != null;
    }
}
