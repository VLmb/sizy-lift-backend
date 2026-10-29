package com.sizylift.backend.publication.service;

import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.PayloadV1;

/**
 * Сохраняет агрегаты одного типа. Вызывается внутри транзакции, держащей блокировку состояния агрегата;
 * перед {@code insert} предыдущая копия уже удалена (см. {@link #replace}). {@code preparer} получает payload
 * до удаления прежней копии — для полей, значение которых зависит от сохранённого. {@code purger} удаляет
 * только при tombstone то, что переживает замену (история 1ПМ).
 */
public record AggregatePublisher<P extends PayloadV1>(String type, Class<P> payloadType, Preparer<P> preparer,
                                                      Inserter<P> inserter, Deleter deleter, Deleter purger) {

    private static final Deleter NOTHING = (userId, id) -> {
    };

    public AggregatePublisher(String type, Class<P> payloadType, Inserter<P> inserter, Deleter deleter) {
        this(type, payloadType, (userId, id, payload) -> payload, inserter, deleter);
    }

    public AggregatePublisher(String type, Class<P> payloadType, Preparer<P> preparer, Inserter<P> inserter,
                              Deleter deleter) {
        this(type, payloadType, preparer, inserter, deleter, NOTHING);
    }

    public AggregatePublisher(String type, Class<P> payloadType, Inserter<P> inserter, Deleter deleter,
                              Deleter purger) {
        this(type, payloadType, (userId, id, payload) -> payload, inserter, deleter, purger);
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

    /** Tombstone: агрегат удалён целиком. */
    public void delete(UUID userId, UUID id) {
        deleter.delete(userId, id);
        purger.delete(userId, id);
    }

    /** Заменяет агрегат целиком: удаляет прежнюю копию и вставляет новую. */
    public void replace(UUID userId, UUID id, P payload) {
        var prepared = preparer.prepare(userId, id, payload);
        deleter.delete(userId, id);
        insert(userId, id, prepared);
    }
}
