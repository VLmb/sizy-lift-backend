package com.sizylift.backend.publication.service;

import java.util.UUID;

/**
 * Принятая запись агрегата. {@code writerId} — кто записал ({@code null} — сам сервер, например каскад или
 * копия упражнения); {@code created} — агрегата не было или он был удалён.
 */
public record AggregateChange(UUID ownerId, UUID writerId, String type, UUID aggregateId, long revision,
                              boolean deleted, boolean created) {

    /** Запись, которую сделал сервер, а не пользователь. */
    public static AggregateChange byServer(UUID ownerId, String type, UUID aggregateId, long revision,
                                           boolean deleted) {
        return new AggregateChange(ownerId, null, type, aggregateId, revision, deleted, false);
    }

    public boolean byOwner() {
        return ownerId.equals(writerId);
    }
}
