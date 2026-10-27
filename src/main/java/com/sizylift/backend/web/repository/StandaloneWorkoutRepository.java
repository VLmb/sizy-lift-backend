package com.sizylift.backend.web.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.PlannedWorkout;
import com.sizylift.backend.web.dto.StandaloneWorkoutDtos.Response;
import com.sizylift.backend.web.dto.WebDtos.PlannedSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class StandaloneWorkoutRepository {
    private static final String READ = """
            SELECT revision, payload::text FROM sizylift.standalone_workout
            WHERE user_id = ? AND id = ? AND NOT deleted
            """;
    private static final String CREATE = """
            INSERT INTO sizylift.standalone_workout (user_id, id, revision, original_date, workout_date, payload)
            VALUES (?, ?, 1, ?, ?, ?::jsonb) ON CONFLICT (user_id, id) DO NOTHING
            """;
    private static final String UPDATE = """
            UPDATE sizylift.standalone_workout SET revision = revision + 1, workout_date = ?,
                payload = ?::jsonb, updated_at = now() WHERE user_id = ? AND id = ? AND revision = ? AND NOT deleted
            """;
    private static final String DELETE = """
            UPDATE sizylift.standalone_workout SET revision = revision + 1, deleted = true, payload = NULL,
                updated_at = now() WHERE user_id = ? AND id = ? AND revision = ? AND NOT deleted
            """;
    private static final String PLANNED = """
            SELECT s.id, s.original_date, s.workout_date, s.payload->>'title' AS title,
                CASE WHEN s.original_date <> s.workout_date THEN s.updated_at END AS rescheduled_at,
                w.id AS workout_id, w.workout_date AS linked_workout_date
            FROM sizylift.standalone_workout s LEFT JOIN LATERAL (
                SELECT id, workout_date FROM sizylift.workout WHERE user_id = s.user_id AND planned_workout_id = s.id
                ORDER BY completed_at DESC, id LIMIT 1
            ) w ON true
            WHERE s.user_id = ? AND NOT s.deleted AND s.workout_date BETWEEN ? AND ?
            ORDER BY s.workout_date, s.id
            """;
    private static final String PROGRAM_ID_EXISTS = "SELECT EXISTS (SELECT 1 FROM sizylift.planned_workout WHERE user_id = ? AND id = ?)";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public StandaloneWorkoutRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public Optional<Response> find(UUID owner, UUID id) {
        return jdbc.query(READ, (row, n) -> new Response(owner, id, row.getLong("revision"),
                json.readValue(row.getString("payload"), PlannedWorkout.class)), owner, id).stream().findFirst();
    }

    public boolean save(UUID owner, UUID id, long revision, PlannedWorkout workout) {
        var payload = json.writeValueAsString(workout);
        if (revision == 0) {
            return jdbc.update(CREATE, owner, id, workout.workoutDate(), workout.workoutDate(), payload) == 1;
        }
        return jdbc.update(UPDATE, workout.workoutDate(), payload, owner, id, revision) == 1;
    }

    public boolean delete(UUID owner, UUID id, long revision) { return jdbc.update(DELETE, owner, id, revision) == 1; }
    public boolean programIdExists(UUID owner, UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject(PROGRAM_ID_EXISTS, Boolean.class, owner, id)); }

    public List<PlannedSummary> planned(UUID owner, LocalDate from, LocalDate to) {
        return jdbc.query(PLANNED, (row, n) -> {
            var changed = row.getObject("rescheduled_at", java.time.OffsetDateTime.class);
            return new PlannedSummary(row.getObject("id", UUID.class), null, row.getString("title"),
                    row.getObject("original_date", LocalDate.class), row.getObject("workout_date", LocalDate.class),
                    changed == null ? null : changed.toInstant(), row.getObject("workout_id", UUID.class),
                    row.getObject("linked_workout_date", LocalDate.class));
        }, owner, from, to);
    }
}
