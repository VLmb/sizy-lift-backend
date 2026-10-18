package com.sizylift.backend.publication.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.timestamp;

/** Training programs; children are removed by ON DELETE CASCADE. */
@Repository
public class ProgramDataRepository {

    private final JdbcTemplate jdbc;

    public ProgramDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID userId, UUID programId, ProgramPayloadV1 program) {
        jdbc.update("""
                INSERT INTO sizylift.program (user_id, id, name, description, start_date, status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, programId, program.name(), program.description(), program.startDate(),
                program.status(), timestamp(program.updatedAt()));

        var cycles = new ArrayList<Object[]>();
        var weeks = new ArrayList<Object[]>();
        var workouts = new ArrayList<Object[]>();
        var blocks = new ArrayList<Object[]>();
        var exercises = new ArrayList<Object[]>();
        var sets = new ArrayList<Object[]>();
        for (var cycle : program.cycles()) {
            cycles.add(new Object[] {userId, cycle.id(), programId, cycle.name(), cycle.position()});
            for (var week : cycle.weeks()) {
                weeks.add(new Object[] {userId, week.id(), programId, cycle.id(), week.number(), week.name(),
                        week.startDate(), week.endDate(), week.position()});
                for (var workout : week.workouts()) {
                    workouts.add(new Object[] {userId, workout.id(), programId, week.id(), workout.title(),
                            workout.workoutDate(), workout.position()});
                    for (var block : workout.blocks()) {
                        blocks.add(new Object[] {userId, block.id(), programId, workout.id(), block.position(),
                                block.blockType(), block.groupLabel(), block.freeKind(), block.title(),
                                block.description()});
                        for (var exercise : block.exercises()) {
                            exercises.add(new Object[] {userId, exercise.id(), programId, block.id(),
                                    exercise.position(), exercise.exerciseId(), exercise.exerciseName(),
                                    exercise.resultType(), exercise.planComment()});
                            for (var set : exercise.sets()) {
                                sets.add(new Object[] {userId, set.id(), programId, exercise.id(), set.position(),
                                        exercise.resultType(), set.setType(), set.targetWeightGrams(),
                                        set.repetitions(), set.durationMs(), set.targetDistanceM(),
                                        set.targetRpeTenths(), set.restMs(), set.note()});
                            }
                        }
                    }
                }
            }
        }
        batch("""
                INSERT INTO sizylift.program_cycle (user_id, id, program_id, name, position) VALUES (?, ?, ?, ?, ?)
                """, cycles);
        batch("""
                INSERT INTO sizylift.training_week (user_id, id, program_id, cycle_id, number, name, start_date,
                    end_date, position)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, weeks);
        batch("""
                INSERT INTO sizylift.planned_workout (user_id, id, program_id, training_week_id, title, workout_date,
                    position)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, workouts);
        batch("""
                INSERT INTO sizylift.planned_block (user_id, id, program_id, planned_workout_id, position, block_type,
                    group_label, free_kind, title, description)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, blocks);
        batch("""
                INSERT INTO sizylift.planned_exercise (user_id, id, program_id, planned_block_id, position,
                    exercise_id, exercise_name, result_type, plan_comment)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, exercises);
        batch("""
                INSERT INTO sizylift.planned_set (user_id, id, program_id, planned_exercise_id, position, result_type,
                    set_type, target_weight_grams, repetitions, duration_ms, target_distance_m, target_rpe_tenths,
                    rest_ms, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sets);
    }

    public void delete(UUID userId, UUID programId) {
        jdbc.update("DELETE FROM sizylift.program WHERE user_id = ? AND id = ?", userId, programId);
    }

    public void deleteAll(UUID userId) {
        jdbc.update("DELETE FROM sizylift.program WHERE user_id = ?", userId);
    }

    /** Keeps only the active program, forgetting the versions of removed ones. */
    public void deleteInactive(UUID userId) {
        jdbc.update("""
                WITH removed AS (
                    DELETE FROM sizylift.program WHERE user_id = ? AND status <> 'active' RETURNING id
                )
                DELETE FROM sizylift.published_aggregate
                WHERE user_id = ? AND type = 'program' AND aggregate_id IN (SELECT id FROM removed)
                """, userId, userId);
    }

    private void batch(String sql, List<Object[]> rows) {
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(sql, rows);
        }
    }
}
