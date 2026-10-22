package com.sizylift.backend.publication.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1.Block;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1.Exercise;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1.PlanSetSnapshot;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1.RecordEvent;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1.Set;
import com.sizylift.backend.publication.repository.Sql.Child;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.children;
import static com.sizylift.backend.publication.repository.Sql.date;
import static com.sizylift.backend.publication.repository.Sql.instant;
import static com.sizylift.backend.publication.repository.Sql.integer;
import static com.sizylift.backend.publication.repository.Sql.timestamp;
import static com.sizylift.backend.publication.repository.Sql.uuid;

/** Завершённые тренировки; дочерние строки удаляет ON DELETE CASCADE. */
@Repository
public class WorkoutDataRepository {

    private static final String INSERT_WORKOUT = """
            INSERT INTO sizylift.workout (user_id, id, workout_date, title, description, started_at, completed_at,
                                          quality, planned_workout_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_BLOCK = """
            INSERT INTO sizylift.workout_block (user_id, id, workout_id, position, block_type, group_label,
                planned_rounds, current_round, completed_rounds, interval_work_ms, interval_rest_ms, free_kind,
                title, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_EXERCISE = """
            INSERT INTO sizylift.workout_exercise (user_id, id, workout_id, block_id, position, exercise_id,
                exercise_name, exercise_short_name, result_type, original_exercise_id, original_exercise_name,
                actual_comment, plan_comment)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_SET = """
            INSERT INTO sizylift.workout_set (user_id, id, workout_id, workout_exercise_id, position, result_type,
                set_type, completion_status, weight_grams, repetitions, duration_ms, distance_m, rpe_tenths,
                average_heart_rate_bpm, maximum_heart_rate_bpm, power_watts, elevation_gain_m, note,
                completed_at, source_planned_set_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_PLAN_SNAPSHOT = """
            INSERT INTO sizylift.workout_plan_set_snapshot (user_id, id, workout_id, actual_set_id,
                source_planned_exercise_id, source_planned_set_id, position, result_type, set_type,
                target_weight_grams, repetitions, duration_ms, target_distance_m, target_rpe_tenths, rest_ms, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_RECORD_EVENT = """
            INSERT INTO sizylift.workout_record_event (user_id, id, workout_id, workout_exercise_id, actual_set_id,
                record_type, value, previous_value, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_WORKOUT = """
            SELECT workout_date, title, description, started_at, completed_at, quality, planned_workout_id,
                updated_at
            FROM sizylift.workout WHERE user_id = ? AND id = ?
            """;
    private static final String SELECT_BLOCKS = """
            SELECT id, position, block_type, group_label, planned_rounds, current_round, completed_rounds,
                interval_work_ms, interval_rest_ms, free_kind, title, description
            FROM sizylift.workout_block WHERE user_id = ? AND workout_id = ? ORDER BY position, id
            """;
    private static final String SELECT_EXERCISES = """
            SELECT id, block_id, position, exercise_id, exercise_name, exercise_short_name, result_type,
                original_exercise_id, original_exercise_name, actual_comment, plan_comment
            FROM sizylift.workout_exercise WHERE user_id = ? AND workout_id = ? ORDER BY position, id
            """;
    private static final String SELECT_SETS = """
            SELECT id, workout_exercise_id, position, set_type, completion_status, weight_grams, repetitions,
                duration_ms, distance_m, rpe_tenths, average_heart_rate_bpm, maximum_heart_rate_bpm, power_watts,
                elevation_gain_m, note, completed_at, source_planned_set_id
            FROM sizylift.workout_set WHERE user_id = ? AND workout_id = ? ORDER BY position, id
            """;
    private static final String SELECT_PLAN_SNAPSHOTS = """
            SELECT id, actual_set_id, source_planned_exercise_id, source_planned_set_id, position, result_type,
                set_type, target_weight_grams, repetitions, duration_ms, target_distance_m, target_rpe_tenths,
                rest_ms, note
            FROM sizylift.workout_plan_set_snapshot WHERE user_id = ? AND workout_id = ? ORDER BY position, id
            """;
    private static final String SELECT_RECORD_EVENTS = """
            SELECT id, workout_exercise_id, actual_set_id, record_type, value, previous_value, created_at
            FROM sizylift.workout_record_event WHERE user_id = ? AND workout_id = ? ORDER BY created_at, id
            """;

    private final JdbcTemplate jdbc;

    public WorkoutDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID userId, UUID workoutId, WorkoutPayloadV1 workout) {
        jdbc.update(INSERT_WORKOUT, userId, workoutId, workout.workoutDate(), workout.title(), workout.description(),
                timestamp(workout.startedAt()), timestamp(workout.completedAt()), workout.quality(),
                workout.plannedWorkoutId(), timestamp(workout.updatedAt()));
        Sql.batch(jdbc, INSERT_BLOCK, blockRows(userId, workoutId, workout));
        Sql.batch(jdbc, INSERT_EXERCISE, exerciseRows(userId, workoutId, workout));
        Sql.batch(jdbc, INSERT_SET, setRows(userId, workoutId, workout));
        Sql.batch(jdbc, INSERT_PLAN_SNAPSHOT, planSnapshotRows(userId, workoutId, workout));
        Sql.batch(jdbc, INSERT_RECORD_EVENT, recordEventRows(userId, workoutId, workout));
    }

    /** Тренировка в формате payload v1, как её публикует приложение. */
    public Optional<WorkoutPayloadV1> find(UUID userId, UUID workoutId) {
        var sets = Sql.byParent(jdbc.query(SELECT_SETS, WorkoutDataRepository::set, userId, workoutId));
        var exercises = Sql.byParent(jdbc.query(SELECT_EXERCISES, (row, n) -> exercise(row, sets), userId,
                workoutId));
        var blocks = jdbc.query(SELECT_BLOCKS, (row, n) -> block(row, exercises), userId, workoutId);
        var snapshots = jdbc.query(SELECT_PLAN_SNAPSHOTS, WorkoutDataRepository::planSnapshot, userId, workoutId);
        var events = jdbc.query(SELECT_RECORD_EVENTS, WorkoutDataRepository::recordEvent, userId, workoutId);
        return Sql.first(jdbc.query(SELECT_WORKOUT, (row, n) -> new WorkoutPayloadV1(date(row, "workout_date"),
                row.getString("title"), row.getString("description"), instant(row, "started_at"),
                instant(row, "completed_at"), row.getString("quality"), uuid(row, "planned_workout_id"),
                instant(row, "updated_at"), blocks, snapshots, events), userId, workoutId));
    }

    public void delete(UUID userId, UUID workoutId) {
        jdbc.update("DELETE FROM sizylift.workout WHERE user_id = ? AND id = ?", userId, workoutId);
    }

    private static List<Object[]> blockRows(UUID userId, UUID workoutId, WorkoutPayloadV1 workout) {
        return workout.blocks().stream()
                .map(block -> new Object[] {userId, block.id(), workoutId, block.position(), block.blockType(),
                        block.groupLabel(), block.plannedRounds(), block.currentRound(), block.completedRounds(),
                        block.intervalWorkMs(), block.intervalRestMs(), block.freeKind(), block.title(),
                        block.description()})
                .toList();
    }

    private static List<Object[]> exerciseRows(UUID userId, UUID workoutId, WorkoutPayloadV1 workout) {
        return workout.blocks().stream()
                .flatMap(block -> block.exercises().stream()
                        .map(exercise -> exerciseRow(userId, workoutId, block, exercise)))
                .toList();
    }

    private static Object[] exerciseRow(UUID userId, UUID workoutId, Block block, Exercise exercise) {
        return new Object[] {userId, exercise.id(), workoutId, block.id(), exercise.position(), exercise.exerciseId(),
                exercise.exerciseName(), exercise.exerciseShortName(), exercise.resultType(),
                exercise.originalExerciseId(), exercise.originalExerciseName(), exercise.actualComment(),
                exercise.planComment()};
    }

    private static List<Object[]> setRows(UUID userId, UUID workoutId, WorkoutPayloadV1 workout) {
        return workout.blocks().stream()
                .flatMap(block -> block.exercises().stream())
                .flatMap(exercise -> exercise.sets().stream().map(set -> new Object[] {userId, set.id(), workoutId,
                        exercise.id(), set.position(), exercise.resultType(), set.setType(), set.completionStatus(),
                        set.weightGrams(), set.repetitions(), set.durationMs(), set.distanceM(), set.rpeTenths(),
                        set.averageHeartRateBpm(), set.maximumHeartRateBpm(), set.powerWatts(),
                        set.elevationGainM(), set.note(), timestamp(set.completedAt()), set.sourcePlannedSetId()}))
                .toList();
    }

    private static List<Object[]> planSnapshotRows(UUID userId, UUID workoutId, WorkoutPayloadV1 workout) {
        return workout.planSetSnapshots().stream()
                .map(snapshot -> new Object[] {userId, snapshot.id(), workoutId, snapshot.actualSetId(),
                        snapshot.sourcePlannedExerciseId(), snapshot.sourcePlannedSetId(), snapshot.position(),
                        snapshot.resultType(), snapshot.setType(), snapshot.targetWeightGrams(),
                        snapshot.repetitions(), snapshot.durationMs(), snapshot.targetDistanceM(),
                        snapshot.targetRpeTenths(), snapshot.restMs(), snapshot.note()})
                .toList();
    }

    private static List<Object[]> recordEventRows(UUID userId, UUID workoutId, WorkoutPayloadV1 workout) {
        return workout.recordEvents().stream()
                .map(event -> new Object[] {userId, event.id(), workoutId, event.workoutExerciseId(),
                        event.actualSetId(), event.recordType(), event.value(), event.previousValue(),
                        timestamp(event.createdAt())})
                .toList();
    }

    private static Block block(ResultSet row, Map<UUID, List<Exercise>> exercises) throws SQLException {
        var id = uuid(row, "id");
        return new Block(id, row.getInt("position"), row.getString("block_type"), row.getString("group_label"),
                integer(row, "planned_rounds"), integer(row, "current_round"), integer(row, "completed_rounds"),
                integer(row, "interval_work_ms"), integer(row, "interval_rest_ms"), row.getString("free_kind"),
                row.getString("title"), row.getString("description"), children(exercises, id));
    }

    private static Child<UUID, Exercise> exercise(ResultSet row, Map<UUID, List<Set>> sets) throws SQLException {
        var id = uuid(row, "id");
        return new Child<>(uuid(row, "block_id"), new Exercise(id, row.getInt("position"), uuid(row, "exercise_id"),
                row.getString("exercise_name"), row.getString("exercise_short_name"), row.getString("result_type"),
                uuid(row, "original_exercise_id"), row.getString("original_exercise_name"),
                row.getString("actual_comment"), row.getString("plan_comment"), children(sets, id)));
    }

    private static Child<UUID, Set> set(ResultSet row, int rowNumber) throws SQLException {
        return new Child<>(uuid(row, "workout_exercise_id"), new Set(uuid(row, "id"), row.getInt("position"),
                row.getString("set_type"), row.getString("completion_status"), integer(row, "weight_grams"),
                integer(row, "repetitions"), integer(row, "duration_ms"), integer(row, "distance_m"),
                integer(row, "rpe_tenths"), integer(row, "average_heart_rate_bpm"),
                integer(row, "maximum_heart_rate_bpm"), integer(row, "power_watts"), integer(row, "elevation_gain_m"),
                row.getString("note"), instant(row, "completed_at"), uuid(row, "source_planned_set_id")));
    }

    private static PlanSetSnapshot planSnapshot(ResultSet row, int rowNumber) throws SQLException {
        return new PlanSetSnapshot(uuid(row, "id"), uuid(row, "actual_set_id"),
                uuid(row, "source_planned_exercise_id"), uuid(row, "source_planned_set_id"), row.getInt("position"),
                row.getString("result_type"), row.getString("set_type"), integer(row, "target_weight_grams"),
                integer(row, "repetitions"), integer(row, "duration_ms"), integer(row, "target_distance_m"),
                integer(row, "target_rpe_tenths"), integer(row, "rest_ms"), row.getString("note"));
    }

    private static RecordEvent recordEvent(ResultSet row, int rowNumber) throws SQLException {
        return new RecordEvent(uuid(row, "id"), uuid(row, "workout_exercise_id"), uuid(row, "actual_set_id"),
                row.getString("record_type"), row.getInt("value"), integer(row, "previous_value"),
                instant(row, "created_at"));
    }
}
