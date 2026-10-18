package com.sizylift.backend.publication.repository;

import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.AthleteProfilePayloadV1;
import com.sizylift.backend.publication.dto.payload.ContraindicationPayloadV1;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.InjuryPayloadV1;
import com.sizylift.backend.publication.dto.payload.OneRepMaxPayloadV1;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.timestamp;

/** Smaller aggregates: own exercises, profile, one-rep maxes, injuries and contraindications. */
@Repository
public class AthleteDataRepository {

    private final JdbcTemplate jdbc;

    public AthleteDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Owner of an exercise id: empty when unknown, {@code null} inside for a system exercise. */
    public Optional<Optional<UUID>> exerciseOwner(UUID exerciseId) {
        return jdbc.query("SELECT user_id FROM sizylift.exercise WHERE id = ?",
                        (row, n) -> Optional.ofNullable(row.getObject(1, UUID.class)), exerciseId)
                .stream().findFirst();
    }

    public void insertExercise(UUID userId, UUID exerciseId, ExercisePayloadV1 exercise) {
        jdbc.update("""
                INSERT INTO sizylift.exercise (id, user_id, name, short_name, movement_category_id, result_type,
                                               is_sbd, note, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, exerciseId, userId, exercise.name(), exercise.shortName(), exercise.movementCategoryId(),
                exercise.resultType(), exercise.isSbd(), exercise.note(), timestamp(exercise.updatedAt()));
        if (!exercise.muscles().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO sizylift.exercise_muscle (exercise_id, muscle_group_id, role) VALUES (?, ?, ?)",
                    exercise.muscles().stream()
                            .map(muscle -> new Object[] {exerciseId, muscle.muscleGroupId(), muscle.role()})
                            .toList());
        }
        if (!exercise.equipmentIds().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO sizylift.exercise_equipment (exercise_id, equipment_id) VALUES (?, ?)",
                    exercise.equipmentIds().stream().map(equipmentId -> new Object[] {exerciseId, equipmentId})
                            .toList());
        }
    }

    public void deleteExercise(UUID userId, UUID exerciseId) {
        jdbc.update("DELETE FROM sizylift.exercise WHERE user_id = ? AND id = ?", userId, exerciseId);
    }

    public void deleteExercises(UUID userId) {
        jdbc.update("DELETE FROM sizylift.exercise WHERE user_id = ?", userId);
    }

    public void replaceProfile(UUID userId, UUID profileId, AthleteProfilePayloadV1 profile) {
        deleteProfile(userId);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO sizylift.athlete_profile (user_id, id, display_name, birth_date, sex, height_mm,
                        weight_grams, training_started_on, sport_directions, goals, goal_deadline, equipment_notes,
                        exercise_preferences, schedule, competition_notes, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, userId);
            statement.setObject(2, profileId);
            statement.setString(3, profile.displayName());
            statement.setObject(4, profile.birthDate());
            statement.setString(5, profile.sex());
            statement.setObject(6, profile.heightMm());
            statement.setObject(7, profile.weightGrams());
            statement.setObject(8, profile.trainingStartedOn());
            statement.setArray(9, connection.createArrayOf("text", profile.sportDirections().toArray()));
            statement.setString(10, profile.goals());
            statement.setObject(11, profile.goalDeadline());
            statement.setString(12, profile.equipmentNotes());
            statement.setString(13, profile.exercisePreferences());
            statement.setString(14, profile.schedule());
            statement.setString(15, profile.competitionNotes());
            statement.setObject(16, timestamp(profile.updatedAt()));
            return statement;
        });
    }

    public void deleteProfile(UUID userId) {
        jdbc.update("DELETE FROM sizylift.athlete_profile WHERE user_id = ?", userId);
    }

    public void replaceOneRepMax(UUID userId, UUID exerciseId, OneRepMaxPayloadV1 value) {
        deleteOneRepMax(userId, exerciseId);
        jdbc.update("""
                INSERT INTO sizylift.one_rep_max (user_id, exercise_id, weight_grams, achieved_on, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, userId, exerciseId, value.weightGrams(), value.achievedOn(), timestamp(value.updatedAt()));
    }

    public void deleteOneRepMax(UUID userId, UUID exerciseId) {
        jdbc.update("DELETE FROM sizylift.one_rep_max WHERE user_id = ? AND exercise_id = ?", userId, exerciseId);
    }

    public void deleteOneRepMaxes(UUID userId) {
        jdbc.update("DELETE FROM sizylift.one_rep_max WHERE user_id = ?", userId);
    }

    public void replaceInjury(UUID userId, UUID injuryId, InjuryPayloadV1 injury) {
        deleteInjury(userId, injuryId);
        jdbc.update("""
                INSERT INTO sizylift.injury (user_id, id, name, description, status, started_on, ended_on, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, injuryId, injury.name(), injury.description(), injury.status(), injury.startedOn(),
                injury.endedOn(), timestamp(injury.updatedAt()));
    }

    public void deleteInjury(UUID userId, UUID injuryId) {
        jdbc.update("DELETE FROM sizylift.injury WHERE user_id = ? AND id = ?", userId, injuryId);
    }

    public void replaceContraindication(UUID userId, UUID id, ContraindicationPayloadV1 contraindication) {
        deleteContraindication(userId, id);
        jdbc.update("""
                INSERT INTO sizylift.contraindication (user_id, id, name, description, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, userId, id, contraindication.name(), contraindication.description(),
                timestamp(contraindication.updatedAt()));
    }

    public void deleteContraindication(UUID userId, UUID id) {
        jdbc.update("DELETE FROM sizylift.contraindication WHERE user_id = ? AND id = ?", userId, id);
    }

    public void deleteHealth(UUID userId) {
        jdbc.update("DELETE FROM sizylift.injury WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM sizylift.contraindication WHERE user_id = ?", userId);
    }
}
