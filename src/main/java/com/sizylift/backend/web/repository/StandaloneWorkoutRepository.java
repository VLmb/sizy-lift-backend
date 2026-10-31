package com.sizylift.backend.web.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.PlannedWorkout;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.web.dto.StandaloneWorkoutDtos.Response;
import com.sizylift.backend.web.dto.WebDtos.PlannedSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Чтение разовых тренировок для веба; запись идёт через sync v2 (ADR-0011). */
@Repository
public class StandaloneWorkoutRepository {
    private static final String STATE = """
            SELECT pa.revision, pa.deleted, pa.author_user_id, s.payload::text AS payload
            FROM sizylift.published_aggregate pa
            LEFT JOIN sizylift.standalone_workout s ON s.user_id = pa.user_id AND s.id = pa.aggregate_id
            WHERE pa.user_id = ? AND pa.type = ? AND pa.aggregate_id = ? AND pa.revision > 0
            """;
    /** Дата с учётом переноса подопечного; «перенесена» — и сдвиг даты владельцем, как раньше. */
    private static final String PLANNED = """
            SELECT s.id, pa.author_user_id, s.original_date, coalesce(r.scheduled_date, s.workout_date) AS scheduled_date,
                s.payload->>'title' AS title,
                CASE WHEN r.planned_workout_id IS NOT NULL THEN r.updated_at
                     WHEN s.original_date <> s.workout_date THEN s.updated_at END AS rescheduled_at,
                w.id AS workout_id, w.workout_date AS linked_workout_date
            FROM sizylift.standalone_workout s
            JOIN sizylift.published_aggregate pa ON pa.user_id = s.user_id AND pa.aggregate_id = s.id
                AND pa.type = 'standalone_workout' AND NOT pa.deleted
            LEFT JOIN sizylift.workout_reschedule r ON r.user_id = s.user_id AND r.planned_workout_id = s.id
            LEFT JOIN LATERAL (
                SELECT id, workout_date FROM sizylift.workout WHERE user_id = s.user_id AND planned_workout_id = s.id
                ORDER BY completed_at DESC, id LIMIT 1
            ) w ON true
            WHERE s.user_id = ? AND coalesce(r.scheduled_date, s.workout_date) BETWEEN ? AND ?
            ORDER BY scheduled_date, s.id
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public StandaloneWorkoutRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    /** Состояние агрегата: ревизия, tombstone, автор и тренировка (у tombstone — пусто). */
    public Optional<State> state(UUID owner, UUID id) {
        return jdbc.query(STATE, (row, n) -> {
            var payload = row.getString("payload");
            return new State(row.getLong("revision"), row.getBoolean("deleted"),
                    row.getObject("author_user_id", UUID.class),
                    payload == null ? null : json.readValue(payload, PlannedWorkout.class));
        }, owner, AggregateType.STANDALONE_WORKOUT, id).stream().findFirst();
    }

    public Optional<Response> find(UUID owner, UUID id) {
        return state(owner, id).filter(state -> !state.deleted() && state.workout() != null)
                .map(state -> new Response(owner, id, state.revision(), state.authorId(), state.workout()));
    }

    public List<PlannedSummary> planned(UUID owner, LocalDate from, LocalDate to) {
        return jdbc.query(PLANNED, (row, n) -> {
            var changed = row.getObject("rescheduled_at", java.time.OffsetDateTime.class);
            return new PlannedSummary(row.getObject("id", UUID.class), null, row.getObject("author_user_id", UUID.class), row.getString("title"),
                    row.getObject("original_date", LocalDate.class), row.getObject("scheduled_date", LocalDate.class),
                    changed == null ? null : changed.toInstant(), row.getObject("workout_id", UUID.class),
                    row.getObject("linked_workout_date", LocalDate.class));
        }, owner, from, to);
    }

    public record State(long revision, boolean deleted, UUID authorId, PlannedWorkout workout) { }
}
