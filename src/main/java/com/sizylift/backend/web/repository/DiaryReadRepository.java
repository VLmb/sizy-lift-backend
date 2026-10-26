package com.sizylift.backend.web.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.web.dto.WebDtos.PlannedSummary;
import com.sizylift.backend.web.dto.WebDtos.WorkoutSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Выборки дневника с ограничением периода; права проверяет сервис. */
@Repository
public class DiaryReadRepository {
    private static final String WORKOUTS = """
            SELECT w.id, w.workout_date, w.title, w.started_at, w.completed_at, w.quality, w.planned_workout_id,
                   (SELECT count(*) FROM sizylift.workout_record_event e
                    WHERE e.user_id = w.user_id AND e.workout_id = w.id) AS record_count
            FROM sizylift.workout w WHERE user_id = ? AND workout_date BETWEEN ? AND ?
            ORDER BY workout_date DESC, completed_at DESC, id
            """;
    private static final String PLANNED = """
            SELECT pw.id, pw.program_id, pw.title, pw.workout_date AS original_date,
                   coalesce(r.scheduled_date, pw.workout_date) AS scheduled_date,
                   r.updated_at AS rescheduled_at,
                   actual.id AS workout_id, actual.workout_date AS linked_workout_date
            FROM sizylift.planned_workout pw
            JOIN sizylift.program p ON p.user_id = pw.user_id AND p.id = pw.program_id
            LEFT JOIN sizylift.workout_reschedule r ON r.user_id = pw.user_id AND r.planned_workout_id = pw.id
            LEFT JOIN LATERAL (
                SELECT w.id, w.workout_date FROM sizylift.workout w WHERE w.user_id = pw.user_id
                  AND w.planned_workout_id = pw.id ORDER BY w.completed_at DESC, w.id LIMIT 1
            ) actual ON true
            WHERE pw.user_id = ? AND p.status = 'active'
              AND coalesce(r.scheduled_date, pw.workout_date) BETWEEN ? AND ?
            ORDER BY scheduled_date, pw.position, pw.id
            """;
    private static final String IDS = """
            SELECT aggregate_id FROM sizylift.published_aggregate
            WHERE user_id = ? AND type = ? AND NOT deleted ORDER BY revision DESC, aggregate_id
            """;
    private final JdbcTemplate jdbc;

    public DiaryReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkoutSummary> workouts(UUID owner, LocalDate from, LocalDate to) {
        return jdbc.query(WORKOUTS, (row, n) -> new WorkoutSummary(uuid(row, "id"), date(row, "workout_date"),
                row.getString("title"), instant(row, "started_at"), instant(row, "completed_at"),
                row.getString("quality"), uuid(row, "planned_workout_id"), row.getInt("record_count")), owner, from, to);
    }

    public List<PlannedSummary> planned(UUID owner, LocalDate from, LocalDate to) {
        return jdbc.query(PLANNED, (row, n) -> new PlannedSummary(uuid(row, "id"), uuid(row, "program_id"),
                row.getString("title"), date(row, "original_date"), date(row, "scheduled_date"),
                instant(row, "rescheduled_at"), uuid(row, "workout_id"), date(row, "linked_workout_date")), owner, from, to);
    }

    public List<UUID> ids(UUID owner, String type) {
        return jdbc.query(IDS, (row, n) -> uuid(row, "aggregate_id"), owner, type);
    }
    private static UUID uuid(ResultSet row, String name) throws SQLException {
        return row.getObject(name, UUID.class);
    }

    private static LocalDate date(ResultSet row, String name) throws SQLException {
        return row.getObject(name, LocalDate.class);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        var value = row.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
