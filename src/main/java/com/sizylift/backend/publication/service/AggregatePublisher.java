package com.sizylift.backend.publication.service;

import java.util.UUID;

/**
 * Сохраняет агрегаты одного типа. Вызывается внутри транзакции, держащей блокировку версии агрегата;
 * перед {@code insert} предыдущая копия уже удалена.
 */
public record AggregatePublisher<P>(String type, Class<P> payloadType, Inserter<P> inserter, Deleter deleter) {

    @FunctionalInterface
    public interface Inserter<P> {
        void insert(UUID userId, UUID id, P payload);
    }

    @FunctionalInterface
    public interface Deleter {
        void delete(UUID userId, UUID id);
    }

    void insert(UUID userId, UUID id, P payload) {
        inserter.insert(userId, id, payload);
    }

    void delete(UUID userId, UUID id) {
        deleter.delete(userId, id);
    }
}
