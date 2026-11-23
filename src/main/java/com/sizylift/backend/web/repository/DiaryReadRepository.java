package com.sizylift.backend.web.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
                   actual.id AS workout_id, actual.workout_date AS linked_workout_date,
                   actual.id IS NULL AND sk.planned_workout_id IS NOT NULL AS skipped
            FROM sizylift.planned_workout pw
            JOIN sizylift.program p ON p.user_id = pw.user_id AND p.id = pw.program_id
            LEFT JOIN sizylift.coach_program_decision d ON d.user_id = p.user_id AND d.program_id = p.id
            LEFT JOIN sizylift.workout_reschedule r ON r.user_id = pw.user_id AND r.planned_workout_id = pw.id
            LEFT JOIN sizylift.planned_workout_skip sk ON sk.user_id = pw.user_id AND sk.planned_workout_id = pw.id
            LEFT JOIN LATERAL (
                SELECT w.id, w.workout_date FROM sizylift.workout w WHERE w.user_id = pw.user_id
                  AND w.planned_workout_id = pw.id ORDER BY w.completed_at DESC, w.id LIMIT 1
            ) actual ON true
            WHERE pw.user_id = ? AND p.status = 'active'
              AND (d.decision IS NULL OR d.decision = 'accepted')
              AND coalesce(r.scheduled_date, pw.workout_date) BETWEEN ? AND ?
            ORDER BY scheduled_date, pw.position, pw.id
            """;
    /**
     * Что можно отметить пропущенным (PWA-09): плановая тренировка активной программы или разовая, без
     * выполненной тренировки по ней. Дата — текущая, с учётом переноса; ревизия — агрегата переноса.
     */
    private static final String SKIP_TARGET = """
            SELECT t.program_id, t.scheduled_date, t.skipped,
                   coalesce((SELECT a.revision FROM sizylift.published_aggregate a
                             WHERE a.user_id = ? AND a.type = 'workout_reschedule' AND a.aggregate_id = ?), 0)
                       AS revision
            FROM (
                SELECT pw.program_id, coalesce(r.scheduled_date, pw.workout_date) AS scheduled_date,
                       coalesce(r.skipped, false) AS skipped
                FROM sizylift.planned_workout pw
                JOIN sizylift.program p ON p.user_id = pw.user_id AND p.id = pw.program_id AND p.status = 'active'
                LEFT JOIN sizylift.coach_program_decision d ON d.user_id = p.user_id AND d.program_id = p.id
                LEFT JOIN sizylift.workout_reschedule r ON r.user_id = pw.user_id AND r.planned_workout_id = pw.id
                WHERE pw.user_id = ? AND pw.id = ? AND (d.decision IS NULL OR d.decision = 'accepted')
                UNION ALL
                SELECT NULL::uuid, coalesce(r.scheduled_date, s.workout_date), coalesce(r.skipped, false)
                FROM sizylift.standalone_workout s
                LEFT JOIN sizylift.workout_reschedule r ON r.user_id = s.user_id AND r.planned_workout_id = s.id
                WHERE s.user_id = ? AND s.id = ?
            ) t
            WHERE NOT EXISTS (SELECT 1 FROM sizylift.workout w WHERE w.user_id = ? AND w.planned_workout_id = ?)
            LIMIT 1
            """;
    /** Отметка, поставленная вебом до синхронизации пропуска: агрегата у неё нет. */
    private static final String UNSKIP = """
            DELETE FROM sizylift.planned_workout_skip WHERE user_id = ? AND planned_workout_id = ?
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
        return jdbc.query(PLANNED, (row, n) -> new PlannedSummary(uuid(row, "id"), uuid(row, "program_id"), null,
                row.getString("title"), date(row, "original_date"), date(row, "scheduled_date"),
                instant(row, "rescheduled_at"), uuid(row, "workout_id"), date(row, "linked_workout_date"),
                row.getBoolean("skipped")), owner, from, to);
    }

    /** Плановая тренировка, которую можно отметить пропущенной или снять отметку. */
    public record SkipTarget(UUID programId, LocalDate scheduledDate, boolean skipped, long revision) {
    }

    public Optional<SkipTarget> skipTarget(UUID owner, UUID plannedId) {
        return jdbc.query(SKIP_TARGET, (row, n) -> new SkipTarget(uuid(row, "program_id"),
                        date(row, "scheduled_date"), row.getBoolean("skipped"), row.getLong("revision")),
                owner, plannedId, owner, plannedId, owner, plannedId, owner, plannedId).stream().findFirst();
    }

    public void removeLegacySkip(UUID owner, UUID plannedId) {
        jdbc.update(UNSKIP, owner, plannedId);
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
