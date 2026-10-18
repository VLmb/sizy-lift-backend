package com.sizylift.backend.publication.service;

import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.AthleteProfilePayloadV1;
import com.sizylift.backend.publication.dto.payload.ContraindicationPayloadV1;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.InjuryPayloadV1;
import com.sizylift.backend.publication.dto.payload.OneRepMaxPayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;
import com.sizylift.backend.publication.repository.AthleteDataRepository;
import com.sizylift.backend.publication.repository.ProgramDataRepository;
import com.sizylift.backend.publication.repository.WorkoutDataRepository;
import com.sizylift.backend.publication.service.PublicationScope.Programs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One publisher per aggregate type (docs/publication.md, "Агрегаты"). */
@Configuration(proxyBeanMethods = false)
class AggregatePublishers {

    @Bean
    AggregatePublisher<WorkoutPayloadV1> workoutPublisher(WorkoutDataRepository workouts) {
        return new AggregatePublisher<>() {
            public String type() {
                return "workout";
            }

            public Class<WorkoutPayloadV1> payloadType() {
                return WorkoutPayloadV1.class;
            }

            public boolean inScope(PublicationScope scope, WorkoutPayloadV1 workout) {
                return scope.workouts()
                        && (scope.workoutsSince() == null || !workout.workoutDate().isBefore(scope.workoutsSince()));
            }

            public void insert(UUID userId, UUID id, WorkoutPayloadV1 workout) {
                workouts.insert(userId, id, workout);
            }

            public void delete(UUID userId, UUID id) {
                workouts.delete(userId, id);
            }
        };
    }

    @Bean
    AggregatePublisher<ProgramPayloadV1> programPublisher(ProgramDataRepository programs) {
        return new AggregatePublisher<>() {
            public String type() {
                return "program";
            }

            public Class<ProgramPayloadV1> payloadType() {
                return ProgramPayloadV1.class;
            }

            public boolean inScope(PublicationScope scope, ProgramPayloadV1 program) {
                return scope.programs() == Programs.ALL
                        || (scope.programs() == Programs.ACTIVE && program.status().equals("active"));
            }

            public void insert(UUID userId, UUID id, ProgramPayloadV1 program) {
                programs.insert(userId, id, program);
            }

            public void delete(UUID userId, UUID id) {
                programs.delete(userId, id);
            }
        };
    }

    @Bean
    AggregatePublisher<ExercisePayloadV1> exercisePublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>() {
            public String type() {
                return "exercise";
            }

            public Class<ExercisePayloadV1> payloadType() {
                return ExercisePayloadV1.class;
            }

            public boolean inScope(PublicationScope scope, ExercisePayloadV1 exercise) {
                return scope.allowsExercises();
            }

            public void insert(UUID userId, UUID id, ExercisePayloadV1 exercise) {
                // The user's own copy was deleted just before, so any remaining row belongs to someone else.
                if (athlete.exerciseOwner(id).isPresent()) {
                    throw new IdConflictException("Exercise id is taken by the catalog or another user");
                }
                athlete.insertExercise(userId, id, exercise);
            }

            public void delete(UUID userId, UUID id) {
                athlete.deleteExercise(userId, id);
            }
        };
    }

    @Bean
    AggregatePublisher<AthleteProfilePayloadV1> athleteProfilePublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>() {
            public String type() {
                return "athlete_profile";
            }

            public Class<AthleteProfilePayloadV1> payloadType() {
                return AthleteProfilePayloadV1.class;
            }

            public boolean inScope(PublicationScope scope, AthleteProfilePayloadV1 profile) {
                return scope.profile();
            }

            public void insert(UUID userId, UUID id, AthleteProfilePayloadV1 profile) {
                athlete.replaceProfile(userId, id, profile);
            }

            public void delete(UUID userId, UUID id) {
                athlete.deleteProfile(userId);
            }
        };
    }

    @Bean
    AggregatePublisher<OneRepMaxPayloadV1> oneRepMaxPublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>() {
            public String type() {
                return "one_rep_max";
            }

            public Class<OneRepMaxPayloadV1> payloadType() {
                return OneRepMaxPayloadV1.class;
            }

            public boolean inScope(PublicationScope scope, OneRepMaxPayloadV1 value) {
                return scope.records();
            }

            public void insert(UUID userId, UUID exerciseId, OneRepMaxPayloadV1 value) {
                athlete.replaceOneRepMax(userId, exerciseId, value);
            }

            public void delete(UUID userId, UUID exerciseId) {
                athlete.deleteOneRepMax(userId, exerciseId);
            }
        };
    }

    @Bean
    AggregatePublisher<InjuryPayloadV1> injuryPublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>() {
            public String type() {
                return "injury";
            }

            public Class<InjuryPayloadV1> payloadType() {
                return InjuryPayloadV1.class;
            }

            public boolean inScope(PublicationScope scope, InjuryPayloadV1 injury) {
                return scope.health();
            }

            public void insert(UUID userId, UUID id, InjuryPayloadV1 injury) {
                athlete.replaceInjury(userId, id, injury);
            }

            public void delete(UUID userId, UUID id) {
                athlete.deleteInjury(userId, id);
            }
        };
    }

    @Bean
    AggregatePublisher<ContraindicationPayloadV1> contraindicationPublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>() {
            public String type() {
                return "contraindication";
            }

            public Class<ContraindicationPayloadV1> payloadType() {
                return ContraindicationPayloadV1.class;
            }

            public boolean inScope(PublicationScope scope, ContraindicationPayloadV1 contraindication) {
                return scope.health();
            }

            public void insert(UUID userId, UUID id, ContraindicationPayloadV1 contraindication) {
                athlete.replaceContraindication(userId, id, contraindication);
            }

            public void delete(UUID userId, UUID id) {
                athlete.deleteContraindication(userId, id);
            }
        };
    }
}
