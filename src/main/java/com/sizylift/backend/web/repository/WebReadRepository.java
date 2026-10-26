package com.sizylift.backend.web.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.web.dto.WebDtos.CatalogExercise;
import com.sizylift.backend.web.dto.WebDtos.HistoryEntry;
import com.sizylift.backend.web.dto.WebDtos.HistorySet;
import com.sizylift.backend.web.dto.WebDtos.MuscleRef;
import com.sizylift.backend.web.dto.WebDtos.OneRepMax;
import com.sizylift.backend.web.dto.WebDtos.ProgramSummary;
import com.sizylift.backend.web.dto.WebDtos.Reference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Выборки для веба: каталог, списки и состояние агрегатов. Права проверяет сервис до вызова. */
@Repository
public class WebReadRepository {

    private static final String SELECT_CATEGORIES = "SELECT id, code, name FROM sizylift.movement_category ORDER BY name";
    private static final String SELECT_EQUIPMENT = "SELECT id, code, name FROM sizylift.equipment ORDER BY name";
    private static final String SELECT_MUSCLES = "SELECT id, code, name FROM sizylift.muscle_group ORDER BY name";
    /** Системные упражнения и собственные упражнения указанных владельцев (вызывающего и, для тренера, подопечного). */
    private static final String VISIBLE_EXERCISES = "e.user_id IS NULL OR e.user_id = ? OR e.user_id = ?";
    private static final String SELECT_EXERCISES = """
            SELECT e.id, e.user_id, e.name, e.short_name, e.movement_category_id, e.result_type, e.is_sbd, e.note,
                   a.revision
            FROM sizylift.exercise e
            LEFT JOIN sizylift.published_aggregate a
                ON a.user_id = e.user_id AND a.type = ? AND a.aggregate_id = e.id
            WHERE %s
            ORDER BY e.name, e.id
            """.formatted(VISIBLE_EXERCISES);
    private static final String SELECT_EXERCISE_MUSCLES = """
            SELECT m.exercise_id, m.muscle_group_id, m.role
            FROM sizylift.exercise_muscle m JOIN sizylift.exercise e ON e.id = m.exercise_id
            WHERE %s
            ORDER BY m.role, m.muscle_group_id
            """.formatted(VISIBLE_EXERCISES);
    private static final String SELECT_EXERCISE_EQUIPMENT = """
            SELECT q.exercise_id, q.equipment_id
            FROM sizylift.exercise_equipment q JOIN sizylift.exercise e ON e.id = q.exercise_id
            WHERE %s
            ORDER BY q.equipment_id
            """.formatted(VISIBLE_EXERCISES);
    private static final String SELECT_PROGRAMS = """
            SELECT p.id, p.name, p.description, p.start_date, p.status, p.updated_at,
                   a.revision, a.author_user_id, a.changed_at,
                   (SELECT max(w.end_date) FROM sizylift.training_week w
                    WHERE w.user_id = p.user_id AND w.program_id = p.id) AS end_date,
                   (SELECT count(*) FROM sizylift.training_week w
                    WHERE w.user_id = p.user_id AND w.program_id = p.id) AS weeks,
                   (SELECT count(*) FROM sizylift.planned_workout pw
                    WHERE pw.user_id = p.user_id AND pw.program_id = p.id) AS workouts
            FROM sizylift.program p
            JOIN sizylift.published_aggregate a
                ON a.user_id = p.user_id AND a.type = ? AND a.aggregate_id = p.id AND NOT a.deleted
            WHERE p.user_id = ?
            ORDER BY p.updated_at DESC, p.id
            """;
    private static final String SELECT_AGGREGATE_STATE = """
            SELECT revision, author_user_id, changed_at FROM sizylift.published_aggregate
            WHERE user_id = ? AND type = ? AND aggregate_id = ? AND NOT deleted
            """;
    private static final String SELECT_ONE_REP_MAXES = """
            SELECT o.exercise_id, o.weight_grams, o.achieved_on, o.updated_at, a.revision
            FROM sizylift.one_rep_max o
            JOIN sizylift.published_aggregate a
                ON a.user_id = o.user_id AND a.type = ? AND a.aggregate_id = o.exercise_id AND NOT a.deleted
            WHERE o.user_id = ?
            ORDER BY o.exercise_id
            """;
    private static final String SELECT_RECENT_WORKOUTS = """
            SELECT w.id, w.workout_date, w.title, w.completed_at
            FROM sizylift.workout w
            WHERE w.user_id = ? AND EXISTS (
                SELECT 1 FROM sizylift.workout_exercise we
                WHERE we.user_id = w.user_id AND we.workout_id = w.id AND we.exercise_id = ?)
            ORDER BY w.workout_date DESC, w.completed_at DESC, w.id
            LIMIT ? OFFSET ?
            """;
    private static final String SELECT_COMPLETED_SETS = """
            SELECT s.set_type, s.weight_grams, s.repetitions, s.duration_ms, s.distance_m, s.rpe_tenths
            FROM sizylift.workout_set s
            JOIN sizylift.workout_exercise we ON we.user_id = s.user_id AND we.id = s.workout_exercise_id
            WHERE s.user_id = ? AND s.workout_id = ? AND we.exercise_id = ? AND s.completion_status = ?
            ORDER BY we.position, s.position
            """;
    private static final String COMPLETED = "completed";

    private final JdbcTemplate jdbc;

    public WebReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Reference> categories() {
        return jdbc.query(SELECT_CATEGORIES, WebReadRepository::reference);
    }

    public List<Reference> equipment() {
        return jdbc.query(SELECT_EQUIPMENT, WebReadRepository::reference);
    }

    public List<Reference> muscles() {
        return jdbc.query(SELECT_MUSCLES, WebReadRepository::reference);
    }

    /** Каталог и свои упражнения {@code caller} и {@code owner} (могут совпадать). */
    public List<CatalogExercise> exercises(UUID caller, UUID owner) {
        var muscles = new HashMap<UUID, List<MuscleRef>>();
        jdbc.query(SELECT_EXERCISE_MUSCLES, row -> {
            muscles.computeIfAbsent(uuid(row, "exercise_id"), id -> new ArrayList<>())
                    .add(new MuscleRef(uuid(row, "muscle_group_id"), row.getString("role")));
        }, caller, owner);
        var equipment = new HashMap<UUID, List<UUID>>();
        jdbc.query(SELECT_EXERCISE_EQUIPMENT, row -> {
            equipment.computeIfAbsent(uuid(row, "exercise_id"), id -> new ArrayList<>()).add(uuid(row, "equipment_id"));
        }, caller, owner);
        return jdbc.query(SELECT_EXERCISES, (row, n) -> exercise(row, muscles, equipment),
                AggregateType.EXERCISE, caller, owner);
    }

    public List<ProgramSummary> programs(UUID owner) {
        return jdbc.query(SELECT_PROGRAMS, (row, n) -> programSummary(row), AggregateType.PROGRAM, owner);
    }

    /** Ревизия, автор и время правки неудалённого агрегата. */
    public Optional<AggregateHead> head(UUID owner, String type, UUID aggregateId) {
        return jdbc.query(SELECT_AGGREGATE_STATE, (row, n) -> new AggregateHead(row.getLong("revision"),
                uuid(row, "author_user_id"), instant(row, "changed_at")), owner, type, aggregateId).stream().findFirst();
    }

    public List<OneRepMax> oneRepMaxes(UUID owner) {
        return jdbc.query(SELECT_ONE_REP_MAXES, (row, n) -> new OneRepMax(uuid(row, "exercise_id"),
                row.getInt("weight_grams"), row.getObject("achieved_on", LocalDate.class), instant(row, "updated_at"),
                row.getLong("revision")), AggregateType.ONE_REP_MAX, owner);
    }

    /** Последние тренировки с упражнением и его выполненные подходы, новые сначала. */
    public List<HistoryEntry> history(UUID owner, UUID exerciseId, int limit, int offset) {
        var workouts = jdbc.query(SELECT_RECENT_WORKOUTS, (row, n) -> new HistoryEntry(uuid(row, "id"),
                row.getObject("workout_date", LocalDate.class), row.getString("title"), List.of()),
                owner, exerciseId, limit, offset);
        return workouts.stream()
                .map(entry -> new HistoryEntry(entry.workoutId(), entry.workoutDate(), entry.title(),
                        jdbc.query(SELECT_COMPLETED_SETS, (row, n) -> historySet(row), owner, entry.workoutId(),
                                exerciseId, COMPLETED)))
                .toList();
    }

    /** Состояние агрегата, нужное клиенту для следующей записи. */
    public record AggregateHead(long revision, UUID authorId, Instant changedAt) {
    }

    private static Reference reference(ResultSet row, int n) throws SQLException {
        return new Reference(uuid(row, "id"), row.getString("code"), row.getString("name"));
    }

    private static CatalogExercise exercise(ResultSet row, Map<UUID, List<MuscleRef>> muscles,
                                            Map<UUID, List<UUID>> equipment) throws SQLException {
        var id = uuid(row, "id");
        return new CatalogExercise(id, uuid(row, "user_id"), row.getString("name"), row.getString("short_name"),
                uuid(row, "movement_category_id"), row.getString("result_type"), row.getBoolean("is_sbd"),
                row.getString("note"), muscles.getOrDefault(id, List.of()), equipment.getOrDefault(id, List.of()),
                row.getObject("revision", Long.class));
    }

    private static ProgramSummary programSummary(ResultSet row) throws SQLException {
        return new ProgramSummary(uuid(row, "id"), row.getString("name"), row.getString("description"),
                row.getObject("start_date", LocalDate.class), row.getObject("end_date", LocalDate.class),
                row.getString("status"), row.getInt("weeks"), row.getInt("workouts"), instant(row, "updated_at"),
                row.getLong("revision"), uuid(row, "author_user_id"), instant(row, "changed_at"));
    }

    private static HistorySet historySet(ResultSet row) throws SQLException {
        return new HistorySet(row.getString("set_type"), integer(row, "weight_grams"), integer(row, "repetitions"),
                integer(row, "duration_ms"), integer(row, "distance_m"), integer(row, "rpe_tenths"));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Integer integer(ResultSet row, String column) throws SQLException {
        return row.getObject(column, Integer.class);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
