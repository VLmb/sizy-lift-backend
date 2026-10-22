package com.sizylift.backend.publication.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.sizylift.backend.publication.entity.AggregateState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.timestamp;

/**
 * Состояние опубликованных агрегатов: версия телефона, серверная ревизия, время и клиент последней записи.
 * Автора ({@code author_user_id}) запись не меняет: его ставят только создание и передача программы тренера.
 */
@Repository
public class PublishedAggregateRepository {

    private static final String INSERT_PLACEHOLDER = """
            INSERT INTO sizylift.published_aggregate (user_id, type, aggregate_id, version, deleted)
            VALUES (?, ?, ?, 0, true)
            ON CONFLICT (user_id, type, aggregate_id) DO NOTHING
            """;
    private static final String SELECT_FOR_UPDATE = """
            SELECT version, revision, changed_at, source_client_id, source_version, deleted
            FROM sizylift.published_aggregate
            WHERE user_id = ? AND type = ? AND aggregate_id = ?
            FOR UPDATE
            """;
    private static final String STORE_SYNCED = """
            UPDATE sizylift.published_aggregate
            SET revision = nextval('sizylift.aggregate_revision_seq'), changed_at = ?, source_client_id = ?,
                source_version = ?, deleted = ?, received_at = now()
            WHERE user_id = ? AND type = ? AND aggregate_id = ?
            RETURNING revision
            """;
    private static final String STORE_LEGACY = """
            UPDATE sizylift.published_aggregate
            SET revision = nextval('sizylift.aggregate_revision_seq'), changed_at = ?, source_client_id = NULL,
                source_version = NULL, version = ?, deleted = ?, received_at = now()
            WHERE user_id = ? AND type = ? AND aggregate_id = ?
            RETURNING revision
            """;
    private static final String SELECT_CLIENT_VERSION = """
            SELECT version FROM sizylift.aggregate_client_version
            WHERE user_id = ? AND type = ? AND aggregate_id = ? AND client_id = ?
            """;
    private static final String UPSERT_CLIENT_VERSION = """
            INSERT INTO sizylift.aggregate_client_version (user_id, type, aggregate_id, client_id, version)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id, type, aggregate_id, client_id)
            DO UPDATE SET version = GREATEST(aggregate_client_version.version, EXCLUDED.version)
            """;

    private final JdbcTemplate jdbc;

    public PublishedAggregateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Блокирует строку агрегата до конца транзакции и возвращает его состояние (ревизия {@code 0} у нового).
     * Параллельные записи одного агрегата упорядочиваются здесь.
     */
    public AggregateState lock(UUID userId, String type, UUID aggregateId) {
        jdbc.update(INSERT_PLACEHOLDER, userId, type, aggregateId);
        return jdbc.queryForObject(SELECT_FOR_UPDATE, PublishedAggregateRepository::state, userId, type,
                aggregateId);
    }

    /** Запись через sync v2; возвращает новую ревизию. */
    public long storeSynced(UUID userId, String type, UUID aggregateId, boolean deleted, Instant changedAt,
                            String clientId, long clientVersion) {
        return jdbc.queryForObject(STORE_SYNCED, Long.class, timestamp(changedAt), clientId, clientVersion,
                deleted, userId, type, aggregateId);
    }

    /** Запись через публикацию v1; возвращает новую ревизию. */
    public long storeLegacy(UUID userId, String type, UUID aggregateId, long version, boolean deleted,
                            Instant changedAt) {
        return jdbc.queryForObject(STORE_LEGACY, Long.class, timestamp(changedAt), version, deleted, userId, type,
                aggregateId);
    }

    /** Последняя обработанная правка клиента по агрегату ({@code 0}, если клиент его не писал). */
    public long clientVersion(UUID userId, String type, UUID aggregateId, String clientId) {
        return jdbc.query(SELECT_CLIENT_VERSION, (row, rowNumber) -> row.getLong(1), userId, type, aggregateId,
                clientId).stream().findFirst().orElse(0L);
    }

    public void rememberClientVersion(UUID userId, String type, UUID aggregateId, String clientId, long version) {
        jdbc.update(UPSERT_CLIENT_VERSION, userId, type, aggregateId, clientId, version);
    }

    private static AggregateState state(ResultSet row, int rowNumber) throws SQLException {
        var changedAt = row.getTimestamp("changed_at");
        var sourceVersion = row.getLong("source_version");
        var hasSourceVersion = !row.wasNull();
        return new AggregateState(row.getLong("version"), row.getLong("revision"),
                changedAt == null ? null : changedAt.toInstant(), row.getString("source_client_id"),
                hasSourceVersion ? sourceVersion : null, row.getBoolean("deleted"));
    }
}
