package com.sizylift.backend.tasks.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Задачи тренера (changeset 021). Всё — только через активную связь вызывающего тренера. */
@Repository
public class CoachTaskRepository {

    private static final String COLUMNS = """
            SELECT t.id, l.athlete_user_id, ap.display_name, t.text, t.due_on, t.done_at, t.created_at, t.updated_at
            FROM sizylift.coach_task t
            JOIN sizylift.coaching_link l ON l.id = t.link_id
            LEFT JOIN sizylift.athlete_profile ap ON ap.user_id = l.athlete_user_id
            WHERE l.coach_user_id = ? AND l.ended_at IS NULL
            """;
    /** Открытые — по сроку (без срока в конце), затем по созданию; сделанные — от последних. */
    private static final String LIST = COLUMNS + """
              AND (?::uuid IS NULL OR l.athlete_user_id = ?::uuid)
              AND (? OR t.done_at IS NULL) AND (? OR t.done_at IS NOT NULL)
            ORDER BY t.done_at IS NOT NULL,
                     CASE WHEN t.done_at IS NULL THEN t.due_on END ASC NULLS LAST,
                     CASE WHEN t.done_at IS NULL THEN t.created_at END ASC,
                     t.done_at DESC, t.id
            """;
    private static final String ONE = COLUMNS + " AND t.id = ?";
    private static final String ACTIVE_LINK = """
            SELECT id FROM sizylift.coaching_link
            WHERE coach_user_id = ? AND athlete_user_id = ? AND ended_at IS NULL
            """;
    private static final String INSERT = """
            INSERT INTO sizylift.coach_task (link_id, text, due_on, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
            RETURNING id
            """;
    private static final String UPDATE = """
            UPDATE sizylift.coach_task t
            SET text = ?, due_on = ?, done_at = ?, updated_at = ?
            FROM sizylift.coaching_link l
            WHERE l.id = t.link_id AND l.coach_user_id = ? AND l.ended_at IS NULL AND t.id = ?
            """;
    private static final String DELETE = """
            DELETE FROM sizylift.coach_task t
            USING sizylift.coaching_link l
            WHERE l.id = t.link_id AND l.coach_user_id = ? AND l.ended_at IS NULL AND t.id = ?
            """;

    private final JdbcTemplate jdbc;

    public CoachTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TaskRow(UUID id, UUID athleteId, String athleteName, String text, LocalDate dueOn,
                          Instant doneAt, Instant createdAt, Instant updatedAt) {
    }

    public List<TaskRow> list(UUID coachId, UUID athleteId, boolean withOpen, boolean withDone) {
        return jdbc.query(LIST, CoachTaskRepository::task, coachId, athleteId, athleteId, withDone, withOpen);
    }

    public Optional<TaskRow> find(UUID coachId, UUID taskId) {
        return jdbc.query(ONE, CoachTaskRepository::task, coachId, taskId).stream().findFirst();
    }

    public Optional<UUID> activeLink(UUID coachId, UUID athleteId) {
        return jdbc.query(ACTIVE_LINK, (row, n) -> row.getObject("id", UUID.class), coachId, athleteId).stream()
                .findFirst();
    }

    public UUID insert(UUID linkId, String text, LocalDate dueOn, Instant now) {
        return jdbc.queryForObject(INSERT, UUID.class, linkId, text, dueOn, Timestamp.from(now), Timestamp.from(now));
    }

    public boolean update(UUID coachId, TaskRow task, Instant now) {
        return jdbc.update(UPDATE, task.text(), task.dueOn(), task.doneAt() == null ? null : Timestamp.from(task.doneAt()),
                Timestamp.from(now), coachId, task.id()) > 0;
    }

    public boolean delete(UUID coachId, UUID taskId) {
        return jdbc.update(DELETE, coachId, taskId) > 0;
    }

    private static TaskRow task(ResultSet row, int rowNumber) throws SQLException {
        return new TaskRow(row.getObject("id", UUID.class), row.getObject("athlete_user_id", UUID.class),
                row.getString("display_name"), row.getString("text"), row.getObject("due_on", LocalDate.class),
                instant(row, "done_at"), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
