package com.sizylift.backend.publication.repository;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1.Block;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1.Exercise;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.timestamp;

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
}
