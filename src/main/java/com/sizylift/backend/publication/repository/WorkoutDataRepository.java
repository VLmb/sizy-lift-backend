package com.sizylift.backend.publication.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.timestamp;

/** Completed workouts; children are removed by ON DELETE CASCADE. */
@Repository
public class WorkoutDataRepository {

    private final JdbcTemplate jdbc;

    public WorkoutDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID userId, UUID workoutId, WorkoutPayloadV1 workout) {
        jdbc.update("""
                INSERT INTO sizylift.workout (user_id, id, workout_date, title, description, started_at, completed_at,
                                              quality, planned_workout_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, workoutId, workout.workoutDate(), workout.title(), workout.description(),
                timestamp(workout.startedAt()), timestamp(workout.completedAt()), workout.quality(),
                workout.plannedWorkoutId(), timestamp(workout.updatedAt()));

        var blocks = new ArrayList<Object[]>();
        var exercises = new ArrayList<Object[]>();
        var sets = new ArrayList<Object[]>();
        for (var block : workout.blocks()) {
            blocks.add(new Object[] {userId, block.id(), workoutId, block.position(), block.blockType(),
                    block.groupLabel(), block.plannedRounds(), block.currentRound(), block.completedRounds(),
                    block.intervalWorkMs(), block.intervalRestMs(), block.freeKind(), block.title(),
                    block.description()});
            for (var exercise : block.exercises()) {
                exercises.add(new Object[] {userId, exercise.id(), workoutId, block.id(), exercise.position(),
                        exercise.exerciseId(), exercise.exerciseName(), exercise.exerciseShortName(),
                        exercise.resultType(), exercise.originalExerciseId(), exercise.originalExerciseName(),
                        exercise.actualComment(), exercise.planComment()});
                for (var set : exercise.sets()) {
                    sets.add(new Object[] {userId, set.id(), workoutId, exercise.id(), set.position(),
                            exercise.resultType(), set.setType(), set.completionStatus(), set.weightGrams(),
                            set.repetitions(), set.durationMs(), set.distanceM(), set.rpeTenths(),
                            set.averageHeartRateBpm(), set.maximumHeartRateBpm(), set.powerWatts(),
                            set.elevationGainM(), set.note(), timestamp(set.completedAt()),
                            set.sourcePlannedSetId()});
                }
            }
        }
        batch("""
                INSERT INTO sizylift.workout_block (user_id, id, workout_id, position, block_type, group_label,
                    planned_rounds, current_round, completed_rounds, interval_work_ms, interval_rest_ms, free_kind,
                    title, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, blocks);
        batch("""
                INSERT INTO sizylift.workout_exercise (user_id, id, workout_id, block_id, position, exercise_id,
                    exercise_name, exercise_short_name, result_type, original_exercise_id, original_exercise_name,
                    actual_comment, plan_comment)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, exercises);
        batch("""
                INSERT INTO sizylift.workout_set (user_id, id, workout_id, workout_exercise_id, position, result_type,
                    set_type, completion_status, weight_grams, repetitions, duration_ms, distance_m, rpe_tenths,
                    average_heart_rate_bpm, maximum_heart_rate_bpm, power_watts, elevation_gain_m, note,
                    completed_at, source_planned_set_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sets);
        batch("""
                INSERT INTO sizylift.workout_plan_set_snapshot (user_id, id, workout_id, actual_set_id,
                    source_planned_exercise_id, source_planned_set_id, position, result_type, set_type,
                    target_weight_grams, repetitions, duration_ms, target_distance_m, target_rpe_tenths, rest_ms, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, workout.planSetSnapshots().stream().map(snapshot -> new Object[] {userId, snapshot.id(),
                workoutId, snapshot.actualSetId(), snapshot.sourcePlannedExerciseId(), snapshot.sourcePlannedSetId(),
                snapshot.position(), snapshot.resultType(), snapshot.setType(), snapshot.targetWeightGrams(),
                snapshot.repetitions(), snapshot.durationMs(), snapshot.targetDistanceM(), snapshot.targetRpeTenths(),
                snapshot.restMs(), snapshot.note()}).toList());
        batch("""
                INSERT INTO sizylift.workout_record_event (user_id, id, workout_id, workout_exercise_id, actual_set_id,
                    record_type, value, previous_value, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, workout.recordEvents().stream().map(event -> new Object[] {userId, event.id(), workoutId,
                event.workoutExerciseId(), event.actualSetId(), event.recordType(), event.value(),
                event.previousValue(), timestamp(event.createdAt())}).toList());
    }

    public void delete(UUID userId, UUID workoutId) {
        jdbc.update("DELETE FROM sizylift.workout WHERE user_id = ? AND id = ?", userId, workoutId);
    }

    private void batch(String sql, List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows);
        }
    }
}
