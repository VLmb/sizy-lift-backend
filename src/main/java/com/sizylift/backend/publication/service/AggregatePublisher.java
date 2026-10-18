package com.sizylift.backend.publication.service;

import java.util.UUID;

/** Stores one aggregate type. Called inside the transaction that holds the aggregate's version lock. */
public interface AggregatePublisher<P> {

    /** Type code in the batch, e.g. {@code workout}. */
    String type();

    Class<P> payloadType();

    boolean inScope(PublicationScope scope, P payload);

    /** Inserts the aggregate; the previous copy has already been deleted. */
    void insert(UUID userId, UUID id, P payload);

    void delete(UUID userId, UUID id);
}
