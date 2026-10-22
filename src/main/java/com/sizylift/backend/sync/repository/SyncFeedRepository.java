package com.sizylift.backend.sync.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.sync.entity.FeedEntry;
import com.sizylift.backend.sync.entity.FeedKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ленты изменений получателей и их курсоры. */
@Repository
public class SyncFeedRepository {

    private static final String ENSURE_CURSOR = """
            INSERT INTO sizylift.sync_cursor (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING
            """;
    private static final String NEXT_SEQ = """
            UPDATE sizylift.sync_cursor SET last_seq = last_seq + 1 WHERE user_id = ? RETURNING last_seq
            """;
    private static final String UPSERT_ENTRY = """
            INSERT INTO sizylift.sync_feed (user_id, seq, owner_user_id, type, aggregate_id, revision, kind)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT sync_feed_aggregate_key
            DO UPDATE SET seq = EXCLUDED.seq, revision = EXCLUDED.revision, kind = EXCLUDED.kind
            """;
    private static final String SELECT_PAGE = """
            SELECT f.seq, f.owner_user_id, f.type, f.aggregate_id, f.revision, f.kind, a.changed_at
            FROM sizylift.sync_feed f
            LEFT JOIN sizylift.published_aggregate a
                ON a.user_id = f.owner_user_id AND a.type = f.type AND a.aggregate_id = f.aggregate_id
            WHERE f.user_id = ? AND f.seq > ?
            ORDER BY f.seq
            LIMIT ?
            """;

    private static final String ADVANCE = """
            UPDATE sizylift.sync_cursor SET last_seq = last_seq + ? WHERE user_id = ? RETURNING last_seq
            """;
    private static final String COUNT_SEEDABLE = """
            SELECT count(*) FROM sizylift.published_aggregate
            WHERE user_id = ? AND type = ANY (?) AND revision > 0 AND NOT deleted
            """;
    private static final String SEED = """
            INSERT INTO sizylift.sync_feed (user_id, seq, owner_user_id, type, aggregate_id, revision, kind)
            SELECT ?, ? + row_number() OVER (ORDER BY revision, aggregate_id), user_id, type, aggregate_id,
                revision, 'upsert'
            FROM sizylift.published_aggregate
            WHERE user_id = ? AND type = ANY (?) AND revision > 0 AND NOT deleted
            ON CONFLICT ON CONSTRAINT sync_feed_aggregate_key
            DO UPDATE SET seq = EXCLUDED.seq, revision = EXCLUDED.revision, kind = EXCLUDED.kind
            """;
    private static final String COUNT_REVOCABLE = """
            SELECT count(*) FROM sizylift.sync_feed
            WHERE user_id = ? AND owner_user_id = ? AND type = ANY (?) AND kind <> 'revoke'
            """;
    private static final String REVOKE = """
            UPDATE sizylift.sync_feed f SET seq = ? + m.n, kind = 'revoke'
            FROM (SELECT seq, row_number() OVER (ORDER BY seq) AS n FROM sizylift.sync_feed
                  WHERE user_id = ? AND owner_user_id = ? AND type = ANY (?) AND kind <> 'revoke') m
            WHERE f.user_id = ? AND f.seq = m.seq
            """;

    private final JdbcTemplate jdbc;

    public SyncFeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Следующая позиция в ленте получателя. Блокирует строку курсора до конца транзакции: записи в одну ленту
     * сериализуются, и порядок позиций совпадает с порядком коммитов.
     */
    public long nextSeq(UUID recipientId) {
        jdbc.update(ENSURE_CURSOR, recipientId);
        return jdbc.queryForObject(NEXT_SEQ, Long.class, recipientId);
    }

    /** Строка ленты на агрегат одна: новая запись переносит её на новую позицию. */
    public void upsert(UUID recipientId, long seq, UUID ownerId, String type, UUID aggregateId, long revision,
                       FeedKind kind) {
        jdbc.update(UPSERT_ENTRY, recipientId, seq, ownerId, type, aggregateId, revision, kind.code());
    }

    /**
     * Добавляет получателю все существующие агрегаты владельца этих типов (новая связь, разрешение на травмы).
     * Владелец должен быть заблокирован: иначе между подсчётом и вставкой появятся новые агрегаты.
     */
    public void seed(UUID recipientId, UUID ownerId, String[] types) {
        var count = jdbc.queryForObject(COUNT_SEEDABLE, Long.class, ownerId, types);
        if (count > 0) {
            jdbc.update(SEED, recipientId, advance(recipientId, count) - count, ownerId, types);
        }
    }

    /** Переводит в {@code revoke} все записи получателя о данных владельца этих типов. */
    public void revoke(UUID recipientId, UUID ownerId, String[] types) {
        var count = jdbc.queryForObject(COUNT_REVOCABLE, Long.class, recipientId, ownerId, types);
        if (count > 0) {
            jdbc.update(REVOKE, advance(recipientId, count) - count, recipientId, ownerId, types, recipientId);
        }
    }

    /** Резервирует {@code count} позиций под блокировкой курсора; возвращает последнюю. */
    private long advance(UUID recipientId, long count) {
        jdbc.update(ENSURE_CURSOR, recipientId);
        return jdbc.queryForObject(ADVANCE, Long.class, count, recipientId);
    }

    public List<FeedEntry> page(UUID recipientId, long after, int limit) {
        return jdbc.query(SELECT_PAGE, SyncFeedRepository::entry, recipientId, after, limit);
    }

    private static FeedEntry entry(ResultSet row, int rowNumber) throws SQLException {
        var changedAt = row.getObject("changed_at", OffsetDateTime.class);
        return new FeedEntry(row.getLong("seq"), row.getObject("owner_user_id", UUID.class),
                row.getString("type"), row.getObject("aggregate_id", UUID.class), row.getLong("revision"),
                FeedKind.of(row.getString("kind")), changedAt == null ? null : changedAt.toInstant());
    }
}
