package com.sizylift.backend.publication.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.sizylift.backend.publication.dto.payload.WorkoutCommentPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutReschedulePayloadV1;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.date;
import static com.sizylift.backend.publication.repository.Sql.first;
import static com.sizylift.backend.publication.repository.Sql.instant;
import static com.sizylift.backend.publication.repository.Sql.timestamp;
import static com.sizylift.backend.publication.repository.Sql.uuid;

/** Переносы тренировок и комментарии — данные, которые пишут обе стороны связи. */
@Repository
public class BetweenDataRepository {

    private static final String INSERT_RESCHEDULE = """
            INSERT INTO sizylift.workout_reschedule (user_id, planned_workout_id, program_id, scheduled_date,
                updated_at)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String DELETE_RESCHEDULE =
            "DELETE FROM sizylift.workout_reschedule WHERE user_id = ? AND planned_workout_id = ?";
    private static final String SELECT_RESCHEDULE = """
            SELECT program_id, scheduled_date, updated_at FROM sizylift.workout_reschedule
            WHERE user_id = ? AND planned_workout_id = ?
            """;
    private static final String RESCHEDULES_OF_PROGRAM = """
            SELECT planned_workout_id FROM sizylift.workout_reschedule WHERE user_id = ? AND program_id = ?
            """;
    private static final String PLANNED_WORKOUT_DATES = """
            SELECT id, workout_date FROM sizylift.planned_workout WHERE user_id = ? AND program_id = ?
            """;
    private static final String PLANNED_WORKOUT_DATE = """
            SELECT workout_date FROM sizylift.planned_workout WHERE user_id = ? AND program_id = ? AND id = ?
            """;
    private static final String INSERT_COMMENT = """
            INSERT INTO sizylift.workout_comment (user_id, id, workout_id, text, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String DELETE_COMMENT = "DELETE FROM sizylift.workout_comment WHERE user_id = ? AND id = ?";
    private static final String SELECT_COMMENT = """
            SELECT workout_id, text, created_at, updated_at FROM sizylift.workout_comment WHERE user_id = ? AND id = ?
            """;
    private static final String COMMENTS_OF_WORKOUT =
            "SELECT id FROM sizylift.workout_comment WHERE user_id = ? AND workout_id = ?";

    private final JdbcTemplate jdbc;

    public BetweenDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertReschedule(UUID userId, UUID plannedWorkoutId, WorkoutReschedulePayloadV1 reschedule) {
        jdbc.update(INSERT_RESCHEDULE, userId, plannedWorkoutId, reschedule.programId(), reschedule.scheduledDate(),
                timestamp(reschedule.updatedAt()));
    }

    public void deleteReschedule(UUID userId, UUID plannedWorkoutId) {
        jdbc.update(DELETE_RESCHEDULE, userId, plannedWorkoutId);
    }

    public Optional<WorkoutReschedulePayloadV1> findReschedule(UUID userId, UUID plannedWorkoutId) {
        return first(jdbc.query(SELECT_RESCHEDULE, (row, n) -> new WorkoutReschedulePayloadV1(
                uuid(row, "program_id"), date(row, "scheduled_date"), instant(row, "updated_at")),
                userId, plannedWorkoutId));
    }

    /** Id плановых тренировок программы, у которых есть перенос. */
    public List<UUID> reschedulesOf(UUID userId, UUID programId) {
        return jdbc.query(RESCHEDULES_OF_PROGRAM, (row, n) -> uuid(row, "planned_workout_id"), userId, programId);
    }

    /** Даты плановых тренировок программы в таблицах сейчас. */
    public Map<UUID, LocalDate> plannedWorkoutDates(UUID userId, UUID programId) {
        return jdbc.query(PLANNED_WORKOUT_DATES, (row, n) -> Map.entry(uuid(row, "id"), date(row, "workout_date")),
                        userId, programId).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Дата плановой тренировки в программе (без учёта переноса). */
    public Optional<LocalDate> plannedWorkoutDate(UUID userId, UUID programId, UUID plannedWorkoutId) {
        return first(jdbc.query(PLANNED_WORKOUT_DATE, (row, n) -> date(row, "workout_date"), userId, programId,
                plannedWorkoutId));
    }

    public void insertComment(UUID userId, UUID commentId, WorkoutCommentPayloadV1 comment) {
        jdbc.update(INSERT_COMMENT, userId, commentId, comment.workoutId(), comment.text(),
                timestamp(comment.createdAt()), timestamp(comment.updatedAt()));
    }

    public void deleteComment(UUID userId, UUID commentId) {
        jdbc.update(DELETE_COMMENT, userId, commentId);
    }

    public Optional<WorkoutCommentPayloadV1> findComment(UUID userId, UUID commentId) {
        return first(jdbc.query(SELECT_COMMENT, (row, n) -> new WorkoutCommentPayloadV1(uuid(row, "workout_id"),
                row.getString("text"), instant(row, "created_at"), instant(row, "updated_at")), userId, commentId));
    }

    public List<UUID> commentsOf(UUID userId, UUID workoutId) {
        return jdbc.query(COMMENTS_OF_WORKOUT, (row, n) -> uuid(row, "id"), userId, workoutId);
    }
}
