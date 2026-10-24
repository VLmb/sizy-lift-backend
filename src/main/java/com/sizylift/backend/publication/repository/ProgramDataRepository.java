package com.sizylift.backend.publication.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.Block;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.Cycle;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.Exercise;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.PlannedWorkout;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.Set;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.Week;
import com.sizylift.backend.publication.repository.Sql.Child;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.children;
import static com.sizylift.backend.publication.repository.Sql.date;
import static com.sizylift.backend.publication.repository.Sql.instant;
import static com.sizylift.backend.publication.repository.Sql.integer;
import static com.sizylift.backend.publication.repository.Sql.timestamp;
import static com.sizylift.backend.publication.repository.Sql.uuid;

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

    private static final String SELECT_PROGRAM = """
            SELECT name, description, start_date, status, updated_at
            FROM sizylift.program WHERE user_id = ? AND id = ?
            """;
    private static final String SELECT_CYCLES = """
            SELECT id, name, position FROM sizylift.program_cycle
            WHERE user_id = ? AND program_id = ? ORDER BY position, id
            """;
    private static final String SELECT_WEEKS = """
            SELECT id, cycle_id, number, name, start_date, end_date, position FROM sizylift.training_week
            WHERE user_id = ? AND program_id = ? ORDER BY position, id
            """;
    private static final String SELECT_WORKOUTS = """
            SELECT id, training_week_id, title, workout_date, position FROM sizylift.planned_workout
            WHERE user_id = ? AND program_id = ? ORDER BY position, id
            """;
    private static final String SELECT_BLOCKS = """
            SELECT id, planned_workout_id, position, block_type, group_label, free_kind, title, description
            FROM sizylift.planned_block WHERE user_id = ? AND program_id = ? ORDER BY position, id
            """;
    private static final String SELECT_EXERCISES = """
            SELECT id, planned_block_id, position, exercise_id, exercise_name, result_type, plan_comment
            FROM sizylift.planned_exercise WHERE user_id = ? AND program_id = ? ORDER BY position, id
            """;
    private static final String SELECT_SETS = """
            SELECT id, planned_exercise_id, position, set_type, target_weight_grams, repetitions, duration_ms,
                target_distance_m, target_rpe_tenths, rest_ms, note
            FROM sizylift.planned_set WHERE user_id = ? AND program_id = ? ORDER BY position, id
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

    /** Программа в формате payload v1, как её публикует приложение. */
    public Optional<ProgramPayloadV1> find(UUID userId, UUID programId) {
        var sets = Sql.byParent(jdbc.query(SELECT_SETS, ProgramDataRepository::set, userId, programId));
        var exercises = Sql.byParent(jdbc.query(SELECT_EXERCISES, (row, n) -> exercise(row, sets), userId,
                programId));
        var blocks = Sql.byParent(jdbc.query(SELECT_BLOCKS, (row, n) -> block(row, exercises), userId, programId));
        var workouts = Sql.byParent(jdbc.query(SELECT_WORKOUTS, (row, n) -> workout(row, blocks), userId,
                programId));
        var weeks = Sql.byParent(jdbc.query(SELECT_WEEKS, (row, n) -> week(row, workouts), userId, programId));
        var cycles = jdbc.query(SELECT_CYCLES, (row, n) -> cycle(row, weeks), userId, programId);
        return Sql.first(jdbc.query(SELECT_PROGRAM, (row, n) -> new ProgramPayloadV1(row.getString("name"),
                row.getString("description"), date(row, "start_date"), row.getString("status"),
                instant(row, "updated_at"), cycles), userId, programId));
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

    private static Cycle cycle(ResultSet row, Map<UUID, List<Week>> weeks) throws SQLException {
        var id = uuid(row, "id");
        return new Cycle(id, row.getString("name"), row.getInt("position"), children(weeks, id));
    }

    private static Child<UUID, Week> week(ResultSet row, Map<UUID, List<PlannedWorkout>> workouts)
            throws SQLException {
        var id = uuid(row, "id");
        return new Child<>(uuid(row, "cycle_id"), new Week(id, row.getInt("number"), row.getString("name"),
                date(row, "start_date"), date(row, "end_date"), row.getInt("position"), children(workouts, id)));
    }

    private static Child<UUID, PlannedWorkout> workout(ResultSet row, Map<UUID, List<Block>> blocks)
            throws SQLException {
        var id = uuid(row, "id");
        return new Child<>(uuid(row, "training_week_id"), new PlannedWorkout(id, row.getString("title"),
                date(row, "workout_date"), row.getInt("position"), children(blocks, id)));
    }

    private static Child<UUID, Block> block(ResultSet row, Map<UUID, List<Exercise>> exercises) throws SQLException {
        var id = uuid(row, "id");
        return new Child<>(uuid(row, "planned_workout_id"), new Block(id, row.getInt("position"),
                row.getString("block_type"), row.getString("group_label"), row.getString("free_kind"),
                row.getString("title"), row.getString("description"), children(exercises, id)));
    }

    private static Child<UUID, Exercise> exercise(ResultSet row, Map<UUID, List<Set>> sets) throws SQLException {
        var id = uuid(row, "id");
        return new Child<>(uuid(row, "planned_block_id"), new Exercise(id, row.getInt("position"),
                uuid(row, "exercise_id"), row.getString("exercise_name"), row.getString("result_type"),
                row.getString("plan_comment"), children(sets, id)));
    }

    private static Child<UUID, Set> set(ResultSet row, int rowNumber) throws SQLException {
        return new Child<>(uuid(row, "planned_exercise_id"), new Set(uuid(row, "id"), row.getInt("position"),
                row.getString("set_type"), integer(row, "target_weight_grams"), integer(row, "repetitions"),
                integer(row, "duration_ms"), integer(row, "target_distance_m"), integer(row, "target_rpe_tenths"),
                integer(row, "rest_ms"), row.getString("note")));
    }
}
