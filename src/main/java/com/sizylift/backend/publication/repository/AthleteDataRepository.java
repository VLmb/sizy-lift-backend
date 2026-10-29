package com.sizylift.backend.publication.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.AthleteProfilePayloadV1;
import com.sizylift.backend.publication.dto.payload.ContraindicationPayloadV1;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.InjuryPayloadV1;
import com.sizylift.backend.publication.dto.payload.OneRepMaxPayloadV1;
import com.sizylift.backend.publication.entity.LiftClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.date;
import static com.sizylift.backend.publication.repository.Sql.first;
import static com.sizylift.backend.publication.repository.Sql.instant;
import static com.sizylift.backend.publication.repository.Sql.integer;
import static com.sizylift.backend.publication.repository.Sql.timestamp;
import static com.sizylift.backend.publication.repository.Sql.uuid;

/** Небольшие агрегаты: свои упражнения, профиль, 1ПМ, травмы и противопоказания. */
@Repository
public class AthleteDataRepository {

    private static final String INSERT_EXERCISE = """
            INSERT INTO sizylift.exercise (id, user_id, name, short_name, movement_category_id, result_type,
                                           lift_class, note, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_EXERCISE_MUSCLE =
            "INSERT INTO sizylift.exercise_muscle (exercise_id, muscle_group_id, role) VALUES (?, ?, ?)";
    private static final String INSERT_EXERCISE_EQUIPMENT =
            "INSERT INTO sizylift.exercise_equipment (exercise_id, equipment_id) VALUES (?, ?)";
    private static final String INSERT_PROFILE = """
            INSERT INTO sizylift.athlete_profile (user_id, id, display_name, birth_date, sex, height_mm,
                weight_grams, training_started_on, sport_directions, goals, goal_deadline, equipment_notes,
                exercise_preferences, schedule, competition_notes, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_ONE_REP_MAX = """
            INSERT INTO sizylift.one_rep_max (user_id, exercise_id, weight_grams, achieved_on, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String INSERT_INJURY = """
            INSERT INTO sizylift.injury (user_id, id, name, description, status, started_on, ended_on, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_CONTRAINDICATION = """
            INSERT INTO sizylift.contraindication (user_id, id, name, description, updated_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SELECT_EXERCISE = """
            SELECT name, short_name, movement_category_id, result_type, lift_class, note, updated_at
            FROM sizylift.exercise WHERE user_id = ? AND id = ?
            """;
    private static final String SELECT_EXERCISE_LIFT_CLASS =
            "SELECT lift_class FROM sizylift.exercise WHERE user_id = ? AND id = ?";
    private static final String SELECT_EXERCISE_MUSCLES = """
            SELECT muscle_group_id, role FROM sizylift.exercise_muscle
            WHERE exercise_id = ? ORDER BY role, muscle_group_id
            """;
    private static final String SELECT_EXERCISE_EQUIPMENT =
            "SELECT equipment_id FROM sizylift.exercise_equipment WHERE exercise_id = ? ORDER BY equipment_id";
    private static final String SELECT_PROFILE = """
            SELECT display_name, birth_date, sex, height_mm, weight_grams, training_started_on, sport_directions,
                goals, goal_deadline, equipment_notes, exercise_preferences, schedule, competition_notes, updated_at
            FROM sizylift.athlete_profile WHERE user_id = ?
            """;
    private static final String SELECT_ONE_REP_MAX = """
            SELECT weight_grams, achieved_on, updated_at FROM sizylift.one_rep_max
            WHERE user_id = ? AND exercise_id = ?
            """;
    private static final String SELECT_INJURY = """
            SELECT name, description, status, started_on, ended_on, updated_at FROM sizylift.injury
            WHERE user_id = ? AND id = ?
            """;
    private static final String SELECT_CONTRAINDICATION = """
            SELECT name, description, updated_at FROM sizylift.contraindication WHERE user_id = ? AND id = ?
            """;

    private static final String SELECT_OWNED_EXERCISES =
            "SELECT id FROM sizylift.exercise WHERE user_id = ? AND id = ANY (?)";

    private final JdbcTemplate jdbc;

    public AthleteDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Есть ли упражнение с таким id — системное или любого пользователя. */
    public boolean exerciseExists(UUID exerciseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sizylift.exercise WHERE id = ?)", Boolean.class, exerciseId));
    }

    /** Какие из упражнений — собственные упражнения пользователя (не каталог и не чужие). */
    public List<UUID> ownedExercises(UUID userId, List<UUID> exerciseIds) {
        return jdbc.query(SELECT_OWNED_EXERCISES, (row, n) -> uuid(row, "id"), userId,
                exerciseIds.toArray(UUID[]::new));
    }

    /** Класс обычно уже определён publisher'ом; без него — как у нового упражнения. */
    public void insertExercise(UUID userId, UUID exerciseId, ExercisePayloadV1 exercise) {
        jdbc.update(INSERT_EXERCISE, exerciseId, userId, exercise.name(), exercise.shortName(),
                exercise.movementCategoryId(), exercise.resultType(), exercise.liftClassToStore(null).code(),
                exercise.note(), timestamp(exercise.updatedAt()));
        Sql.batch(jdbc, INSERT_EXERCISE_MUSCLE, exercise.muscles().stream()
                .map(muscle -> new Object[] {exerciseId, muscle.muscleGroupId(), muscle.role()})
                .toList());
        Sql.batch(jdbc, INSERT_EXERCISE_EQUIPMENT, exercise.equipmentIds().stream()
                .map(equipmentId -> new Object[] {exerciseId, equipmentId})
                .toList());
    }

    public void deleteExercise(UUID userId, UUID exerciseId) {
        jdbc.update("DELETE FROM sizylift.exercise WHERE user_id = ? AND id = ?", userId, exerciseId);
    }

    public void insertProfile(UUID userId, UUID profileId, AthleteProfilePayloadV1 profile) {
        jdbc.update(INSERT_PROFILE, userId, profileId, profile.displayName(), profile.birthDate(), profile.sex(),
                profile.heightMm(), profile.weightGrams(), profile.trainingStartedOn(),
                profile.sportDirections().toArray(String[]::new), profile.goals(), profile.goalDeadline(),
                profile.equipmentNotes(), profile.exercisePreferences(), profile.schedule(),
                profile.competitionNotes(), timestamp(profile.updatedAt()));
    }

    /** Профиль у пользователя один, поэтому удаляется по пользователю, а не по id. */
    public void deleteProfile(UUID userId) {
        jdbc.update("DELETE FROM sizylift.athlete_profile WHERE user_id = ?", userId);
    }

    public void insertOneRepMax(UUID userId, UUID exerciseId, OneRepMaxPayloadV1 value) {
        jdbc.update(INSERT_ONE_REP_MAX, userId, exerciseId, value.weightGrams(), value.achievedOn(),
                timestamp(value.updatedAt()));
    }

    public void deleteOneRepMax(UUID userId, UUID exerciseId) {
        jdbc.update("DELETE FROM sizylift.one_rep_max WHERE user_id = ? AND exercise_id = ?", userId, exerciseId);
    }

    public void insertInjury(UUID userId, UUID injuryId, InjuryPayloadV1 injury) {
        jdbc.update(INSERT_INJURY, userId, injuryId, injury.name(), injury.description(), injury.status(),
                injury.startedOn(), injury.endedOn(), timestamp(injury.updatedAt()));
    }

    public void deleteInjury(UUID userId, UUID injuryId) {
        jdbc.update("DELETE FROM sizylift.injury WHERE user_id = ? AND id = ?", userId, injuryId);
    }

    public void insertContraindication(UUID userId, UUID id, ContraindicationPayloadV1 contraindication) {
        jdbc.update(INSERT_CONTRAINDICATION, userId, id, contraindication.name(), contraindication.description(),
                timestamp(contraindication.updatedAt()));
    }

    public void deleteContraindication(UUID userId, UUID id) {
        jdbc.update("DELETE FROM sizylift.contraindication WHERE user_id = ? AND id = ?", userId, id);
    }

    public Optional<ExercisePayloadV1> findExercise(UUID userId, UUID exerciseId) {
        var muscles = jdbc.query(SELECT_EXERCISE_MUSCLES, (row, n) -> new ExercisePayloadV1.Muscle(
                uuid(row, "muscle_group_id"), row.getString("role")), exerciseId);
        var equipment = jdbc.query(SELECT_EXERCISE_EQUIPMENT, (row, n) -> uuid(row, "equipment_id"), exerciseId);
        return first(jdbc.query(SELECT_EXERCISE, (row, n) -> ExercisePayloadV1.stored(row.getString("name"),
                row.getString("short_name"), uuid(row, "movement_category_id"), row.getString("result_type"),
                LiftClass.of(row.getString("lift_class")), row.getString("note"), instant(row, "updated_at"),
                muscles, equipment), userId, exerciseId));
    }

    /** Класс собственного упражнения пользователя, если оно уже сохранено. */
    public Optional<LiftClass> findLiftClass(UUID userId, UUID exerciseId) {
        return first(jdbc.query(SELECT_EXERCISE_LIFT_CLASS, (row, n) -> LiftClass.of(row.getString("lift_class")),
                userId, exerciseId));
    }

    /** Профиль у пользователя один, поэтому ищется по пользователю, а не по id. */
    public Optional<AthleteProfilePayloadV1> findProfile(UUID userId) {
        return first(jdbc.query(SELECT_PROFILE, AthleteDataRepository::profile, userId));
    }

    public Optional<OneRepMaxPayloadV1> findOneRepMax(UUID userId, UUID exerciseId) {
        return first(jdbc.query(SELECT_ONE_REP_MAX, (row, n) -> new OneRepMaxPayloadV1(row.getInt("weight_grams"),
                date(row, "achieved_on"), instant(row, "updated_at")), userId, exerciseId));
    }

    public Optional<InjuryPayloadV1> findInjury(UUID userId, UUID injuryId) {
        return first(jdbc.query(SELECT_INJURY, (row, n) -> new InjuryPayloadV1(row.getString("name"),
                row.getString("description"), row.getString("status"), date(row, "started_on"),
                date(row, "ended_on"), instant(row, "updated_at")), userId, injuryId));
    }

    public Optional<ContraindicationPayloadV1> findContraindication(UUID userId, UUID id) {
        return first(jdbc.query(SELECT_CONTRAINDICATION, (row, n) -> new ContraindicationPayloadV1(
                row.getString("name"), row.getString("description"), instant(row, "updated_at")), userId, id));
    }

    private static AthleteProfilePayloadV1 profile(ResultSet row, int rowNumber) throws SQLException {
        return new AthleteProfilePayloadV1(row.getString("display_name"), date(row, "birth_date"),
                row.getString("sex"), integer(row, "height_mm"), integer(row, "weight_grams"),
                date(row, "training_started_on"), sportDirections(row), row.getString("goals"),
                date(row, "goal_deadline"), row.getString("equipment_notes"), row.getString("exercise_preferences"),
                row.getString("schedule"), row.getString("competition_notes"), instant(row, "updated_at"));
    }

    private static List<String> sportDirections(ResultSet row) throws SQLException {
        var array = row.getArray("sport_directions");
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
