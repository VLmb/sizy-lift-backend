package com.sizylift.backend.publication.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/** Общие помощники JDBC для репозиториев публикации. */
final class Sql {

    private Sql() {
    }

    /** Драйвер PostgreSQL не связывает {@link Instant} напрямую. */
    static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    static void batch(JdbcTemplate jdbc, String sql, List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows);
        }
    }
}
