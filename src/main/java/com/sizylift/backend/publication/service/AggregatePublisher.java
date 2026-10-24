package com.sizylift.backend.publication.service;

import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.PayloadV1;

/**
 * Сохраняет агрегаты одного типа. Вызывается внутри транзакции, держащей блокировку состояния агрегата;
 * перед {@code insert} предыдущая копия уже удалена (см. {@link #replace}).
 */
public record AggregatePublisher<P extends PayloadV1>(String type, Class<P> payloadType, Inserter<P> inserter,
                                                      Deleter deleter) {

    @FunctionalInterface
    public interface Inserter<P> {
        void insert(UUID userId, UUID id, P payload);
    }

    @FunctionalInterface
    public interface Deleter {
        void delete(UUID userId, UUID id);
    }

    public void insert(UUID userId, UUID id, P payload) {
        inserter.insert(userId, id, payload);
    }

    public void delete(UUID userId, UUID id) {
        deleter.delete(userId, id);
    }

    /** Заменяет агрегат целиком: удаляет прежнюю копию и вставляет новую. */
    public void replace(UUID userId, UUID id, P payload) {
        delete(userId, id);
        insert(userId, id, payload);
    }
}
