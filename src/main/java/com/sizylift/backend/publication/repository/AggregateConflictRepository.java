package com.sizylift.backend.publication.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.timestamp;

/** Журнал версий, проигравших при last-writer-wins. */
@Repository
public class AggregateConflictRepository {

    private static final String INSERT = """
            INSERT INTO sizylift.aggregate_conflict (user_id, type, aggregate_id, lost_payload, lost_changed_at,
                lost_client_id, winner_revision)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
            """;
    private static final String DELETE_OLDER = "DELETE FROM sizylift.aggregate_conflict WHERE created_at < ?";

    private final JdbcTemplate jdbc;

    public AggregateConflictRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code lostPayload} — JSON или {@code null}, когда содержимого нет (tombstone). */
    public void record(UUID userId, String type, UUID aggregateId, String lostPayload, Instant lostChangedAt,
                       String lostClientId, long winnerRevision) {
        jdbc.update(INSERT, userId, type, aggregateId, lostPayload, timestamp(lostChangedAt), lostClientId,
                winnerRevision);
    }

    public int deleteOlderThan(Instant threshold) {
        return jdbc.update(DELETE_OLDER, timestamp(threshold));
    }
}
