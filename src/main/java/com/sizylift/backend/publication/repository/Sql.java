package com.sizylift.backend.publication.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** JDBC parameter helpers; the PostgreSQL driver has no direct binding for {@link Instant}. */
final class Sql {

    private Sql() {
    }

    static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
