package com.sizylift.backend.publication.repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.Block;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.Exercise;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.PlannedWorkout;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.Week;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.timestamp;

/** Тренировочные программы; дочерние строки удаляет ON DELETE CASCADE. */
@Repository
public class ProgramDataRepository {

    private static final String INSERT_PROGRAM = """
            INSERT INTO sizylift.program (user_id, id, name, description, start_date, status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_CYCLE = """
            INSERT INTO sizylift.program_cycle (user_id, id, program_id, name, position) VALUES (?, ?, ?, ?, ?)
            """;
    private static final String INSERT_WEEK = """
            INSERT INTO sizylift.training_week (user_id, id, program_id, cycle_id, number, name, start_date,
                end_date, position)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_WORKOUT = """
            INSERT INTO sizylift.planned_workout (user_id, id, program_id, training_week_id, title, workout_date,
                position)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_BLOCK = """
            INSERT INTO sizylift.planned_block (user_id, id, program_id, planned_workout_id, position, block_type,
                group_label, free_kind, title, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_EXERCISE = """
            INSERT INTO sizylift.planned_exercise (user_id, id, program_id, planned_block_id, position,
                exercise_id, exercise_name, result_type, plan_comment)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_SET = """
            INSERT INTO sizylift.planned_set (user_id, id, program_id, planned_exercise_id, position, result_type,
                set_type, target_weight_grams, repetitions, duration_ms, target_distance_m, target_rpe_tenths,
                rest_ms, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public ProgramDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID userId, UUID programId, ProgramPayloadV1 program) {
        jdbc.update(INSERT_PROGRAM, userId, programId, program.name(), program.description(), program.startDate(),
                program.status(), timestamp(program.updatedAt()));
        Sql.batch(jdbc, INSERT_CYCLE, cycleRows(userId, programId, program));
        Sql.batch(jdbc, INSERT_WEEK, weekRows(userId, programId, program));
        Sql.batch(jdbc, INSERT_WORKOUT, workoutRows(userId, programId, program));
        Sql.batch(jdbc, INSERT_BLOCK, blockRows(userId, programId, program));
        Sql.batch(jdbc, INSERT_EXERCISE, exerciseRows(userId, programId, program));
        Sql.batch(jdbc, INSERT_SET, setRows(userId, programId, program));
    }

    public void delete(UUID userId, UUID programId) {
        jdbc.update("DELETE FROM sizylift.program WHERE user_id = ? AND id = ?", userId, programId);
    }

    private static List<Object[]> cycleRows(UUID userId, UUID programId, ProgramPayloadV1 program) {
        return program.cycles().stream()
                .map(cycle -> new Object[] {userId, cycle.id(), programId, cycle.name(), cycle.position()})
                .toList();
    }

    private static List<Object[]> weekRows(UUID userId, UUID programId, ProgramPayloadV1 program) {
        return program.cycles().stream()
                .flatMap(cycle -> cycle.weeks().stream().map(week -> new Object[] {userId, week.id(), programId,
                        cycle.id(), week.number(), week.name(), week.startDate(), week.endDate(), week.position()}))
                .toList();
    }

    private static List<Object[]> workoutRows(UUID userId, UUID programId, ProgramPayloadV1 program) {
        return weeks(program)
                .flatMap(week -> week.workouts().stream().map(workout -> new Object[] {userId, workout.id(),
                        programId, week.id(), workout.title(), workout.workoutDate(), workout.position()}))
                .toList();
    }

    private static List<Object[]> blockRows(UUID userId, UUID programId, ProgramPayloadV1 program) {
        return workouts(program)
                .flatMap(workout -> workout.blocks().stream().map(block -> new Object[] {userId, block.id(),
                        programId, workout.id(), block.position(), block.blockType(), block.groupLabel(),
                        block.freeKind(), block.title(), block.description()}))
                .toList();
    }

    private static List<Object[]> exerciseRows(UUID userId, UUID programId, ProgramPayloadV1 program) {
        return blocks(program)
                .flatMap(block -> block.exercises().stream().map(exercise -> new Object[] {userId, exercise.id(),
                        programId, block.id(), exercise.position(), exercise.exerciseId(), exercise.exerciseName(),
                        exercise.resultType(), exercise.planComment()}))
                .toList();
    }

    private static List<Object[]> setRows(UUID userId, UUID programId, ProgramPayloadV1 program) {
        return exercises(program)
                .flatMap(exercise -> exercise.sets().stream().map(set -> new Object[] {userId, set.id(), programId,
                        exercise.id(), set.position(), exercise.resultType(), set.setType(),
                        set.targetWeightGrams(), set.repetitions(), set.durationMs(), set.targetDistanceM(),
                        set.targetRpeTenths(), set.restMs(), set.note()}))
                .toList();
    }

    private static Stream<Week> weeks(ProgramPayloadV1 program) {
        return program.cycles().stream().flatMap(cycle -> cycle.weeks().stream());
    }

    private static Stream<PlannedWorkout> workouts(ProgramPayloadV1 program) {
        return weeks(program).flatMap(week -> week.workouts().stream());
    }

    private static Stream<Block> blocks(ProgramPayloadV1 program) {
        return workouts(program).flatMap(workout -> workout.blocks().stream());
    }

    private static Stream<Exercise> exercises(ProgramPayloadV1 program) {
        return blocks(program).flatMap(block -> block.exercises().stream());
    }
}
