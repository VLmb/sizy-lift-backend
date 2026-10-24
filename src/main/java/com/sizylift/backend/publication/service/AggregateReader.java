package com.sizylift.backend.publication.service;

import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.PayloadV1;

/** Собирает агрегат одного типа из таблиц в формате payload v1; зеркало {@link AggregatePublisher}. */
public record AggregateReader<P extends PayloadV1>(String type, Finder<P> finder) {

    @FunctionalInterface
    public interface Finder<P> {
        Optional<P> find(UUID userId, UUID id);
    }

    public Optional<P> read(UUID userId, UUID id) {
        return finder.find(userId, id);
    }
}
