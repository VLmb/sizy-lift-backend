package com.sizylift.backend.publication.repository;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.PlannedWorkout;
import com.sizylift.backend.publication.dto.payload.StandaloneWorkoutPayloadV1;
import com.sizylift.backend.publication.service.IdConflictException;
import com.sizylift.backend.publication.service.PayloadParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import static com.sizylift.backend.publication.repository.Sql.first;
import static com.sizylift.backend.publication.repository.Sql.instant;
import static com.sizylift.backend.publication.repository.Sql.timestamp;

/**
 * Разовые тренировки (ADR-0011): JSON плановой тренировки целиком. Замена — upsert, чтобы сохранить
 * {@code original_date} (дату создания, по ней веб показывает «перенесена»); удаляет строку только tombstone.
 */
@Repository
public class StandaloneDataRepository {

    private static final String UPSERT = """
            INSERT INTO sizylift.standalone_workout (user_id, id, original_date, workout_date, payload, updated_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (user_id, id) DO UPDATE
                SET workout_date = excluded.workout_date, payload = excluded.payload, updated_at = excluded.updated_at
            """;
    private static final String DELETE = "DELETE FROM sizylift.standalone_workout WHERE user_id = ? AND id = ?";
    private static final String SELECT = """
            SELECT payload::text, updated_at FROM sizylift.standalone_workout WHERE user_id = ? AND id = ?
            """;
    private static final String SELECT_DATE =
            "SELECT workout_date FROM sizylift.standalone_workout WHERE user_id = ? AND id = ?";
    private static final String PLANNED_ID_TAKEN =
            "SELECT EXISTS (SELECT 1 FROM sizylift.planned_workout WHERE user_id = ? AND id = ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public StandaloneDataRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @throws PayloadParser.InvalidPayloadException id тренировки не совпадает с агрегатом или вложенные id
     *                                               повторяются
     * @throws IdConflictException                   тот же id у плановой тренировки программы
     */
    public void insert(UUID userId, UUID id, StandaloneWorkoutPayloadV1 standalone) {
        var workout = standalone.workout();
        if (!id.equals(workout.id()) || !uniqueIds(workout)) {
            throw new PayloadParser.InvalidPayloadException();
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(PLANNED_ID_TAKEN, Boolean.class, userId, id))) {
            throw new IdConflictException("Standalone workout id is taken by a program workout");
        }
        jdbc.update(UPSERT, userId, id, workout.workoutDate(), workout.workoutDate(),
                json.writeValueAsString(workout), timestamp(standalone.updatedAt()));
    }

    public void delete(UUID userId, UUID id) {
        jdbc.update(DELETE, userId, id);
    }

    public Optional<StandaloneWorkoutPayloadV1> find(UUID userId, UUID id) {
        return first(jdbc.query(SELECT, (row, n) -> new StandaloneWorkoutPayloadV1(
                json.readValue(row.getString("payload"), PlannedWorkout.class), instant(row, "updated_at")),
                userId, id));
    }

    /** Дата разовой тренировки (без учёта переноса). */
    public Optional<LocalDate> workoutDate(UUID userId, UUID id) {
        return first(jdbc.query(SELECT_DATE, (row, n) -> Sql.date(row, "workout_date"), userId, id));
    }

    private static boolean uniqueIds(PlannedWorkout workout) {
        var ids = new HashSet<UUID>();
        ids.add(workout.id());
        for (var block : workout.blocks()) {
            if (!ids.add(block.id())) {
                return false;
            }
            for (var exercise : block.exercises()) {
                if (!ids.add(exercise.id())) {
                    return false;
                }
                for (var set : exercise.sets()) {
                    if (!ids.add(set.id())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
