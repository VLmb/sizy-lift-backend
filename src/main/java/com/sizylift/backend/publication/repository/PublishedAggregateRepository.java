package com.sizylift.backend.publication.repository;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Принятые версии опубликованных агрегатов. */
@Repository
public class PublishedAggregateRepository {

    private final JdbcTemplate jdbc;

    public PublishedAggregateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Блокирует строку версии агрегата до конца транзакции и возвращает сохранённую версию
     * ({@code 0} для нового агрегата). Параллельные пачки одного пользователя упорядочиваются здесь.
     */
    public long lockVersion(UUID userId, String type, UUID aggregateId) {
        jdbc.update("""
                INSERT INTO sizylift.published_aggregate (user_id, type, aggregate_id, version, deleted)
                VALUES (?, ?, ?, 0, true)
                ON CONFLICT (user_id, type, aggregate_id) DO NOTHING
                """, userId, type, aggregateId);
        return jdbc.queryForObject("""
                SELECT version FROM sizylift.published_aggregate
                WHERE user_id = ? AND type = ? AND aggregate_id = ?
                FOR UPDATE
                """, Long.class, userId, type, aggregateId);
    }

    public void store(UUID userId, String type, UUID aggregateId, long version, boolean deleted) {
        jdbc.update("""
                UPDATE sizylift.published_aggregate
                SET version = ?, deleted = ?, received_at = now()
                WHERE user_id = ? AND type = ? AND aggregate_id = ?
                """, version, deleted, userId, type, aggregateId);
    }
}
