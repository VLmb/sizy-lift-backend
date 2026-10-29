package com.sizylift.backend.publication.service;

import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.PayloadV1;

/**
 * Сохраняет агрегаты одного типа. Вызывается внутри транзакции, держащей блокировку состояния агрегата;
 * перед {@code insert} предыдущая копия уже удалена (см. {@link #replace}). {@code preparer} получает payload
 * до удаления прежней копии — для полей, значение которых зависит от сохранённого.
 */
public record AggregatePublisher<P extends PayloadV1>(String type, Class<P> payloadType, Preparer<P> preparer,
                                                      Inserter<P> inserter, Deleter deleter) {

    public AggregatePublisher(String type, Class<P> payloadType, Inserter<P> inserter, Deleter deleter) {
        this(type, payloadType, (userId, id, payload) -> payload, inserter, deleter);
    }

    @FunctionalInterface
    public interface Preparer<P> {
        /** @throws PayloadParser.InvalidPayloadException если payload несовместим с сохранёнными данными */
        P prepare(UUID userId, UUID id, P payload);
    }

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
        var prepared = preparer.prepare(userId, id, payload);
        delete(userId, id);
        insert(userId, id, prepared);
    }
}
