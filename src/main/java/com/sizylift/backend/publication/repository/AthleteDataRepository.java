package com.sizylift.backend.publication.repository;

import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.AthleteProfilePayloadV1;
import com.sizylift.backend.publication.dto.payload.ContraindicationPayloadV1;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.InjuryPayloadV1;
import com.sizylift.backend.publication.dto.payload.OneRepMaxPayloadV1;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.timestamp;

/** Небольшие агрегаты: свои упражнения, профиль, 1ПМ, травмы и противопоказания. */
@Repository
public class AthleteDataRepository {

    private static final String INSERT_EXERCISE = """
            INSERT INTO sizylift.exercise (id, user_id, name, short_name, movement_category_id, result_type, is_sbd,
                                           note, updated_at)
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

    private final JdbcTemplate jdbc;

    public AthleteDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Есть ли упражнение с таким id — системное или любого пользователя. */
    public boolean exerciseExists(UUID exerciseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sizylift.exercise WHERE id = ?)", Boolean.class, exerciseId));
    }

    public void insertExercise(UUID userId, UUID exerciseId, ExercisePayloadV1 exercise) {
        jdbc.update(INSERT_EXERCISE, exerciseId, userId, exercise.name(), exercise.shortName(),
                exercise.movementCategoryId(), exercise.resultType(), exercise.isSbd(), exercise.note(),
                timestamp(exercise.updatedAt()));
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
}
