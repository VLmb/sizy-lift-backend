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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** По одному publisher на тип агрегата. */
@Configuration(proxyBeanMethods = false)
class AggregatePublishers {

    @Bean
    AggregatePublisher<WorkoutPayloadV1> workoutPublisher(WorkoutDataRepository workouts) {
        return new AggregatePublisher<>(AggregateType.WORKOUT, WorkoutPayloadV1.class,
                workouts::insert, workouts::delete);
    }

    @Bean
    AggregatePublisher<ProgramPayloadV1> programPublisher(ProgramDataRepository programs) {
        return new AggregatePublisher<>(AggregateType.PROGRAM, ProgramPayloadV1.class,
                programs::insert, programs::delete);
    }

    @Bean
    AggregatePublisher<ExercisePayloadV1> exercisePublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>(AggregateType.EXERCISE, ExercisePayloadV1.class,
                (userId, id, exercise) -> insertOwnExercise(athlete, userId, id, exercise),
                athlete::deleteExercise);
    }

    @Bean
    AggregatePublisher<AthleteProfilePayloadV1> athleteProfilePublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>(AggregateType.ATHLETE_PROFILE, AthleteProfilePayloadV1.class,
                athlete::insertProfile, (userId, id) -> athlete.deleteProfile(userId));
    }

    @Bean
    AggregatePublisher<OneRepMaxPayloadV1> oneRepMaxPublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>(AggregateType.ONE_REP_MAX, OneRepMaxPayloadV1.class,
                athlete::insertOneRepMax, athlete::deleteOneRepMax);
    }

    @Bean
    AggregatePublisher<InjuryPayloadV1> injuryPublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>(AggregateType.INJURY, InjuryPayloadV1.class,
                athlete::insertInjury, athlete::deleteInjury);
    }

    @Bean
    AggregatePublisher<ContraindicationPayloadV1> contraindicationPublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>(AggregateType.CONTRAINDICATION, ContraindicationPayloadV1.class,
                athlete::insertContraindication, athlete::deleteContraindication);
    }

    /** Своя копия пользователя уже удалена, поэтому оставшаяся строка принадлежит каталогу или другому пользователю. */
    private static void insertOwnExercise(AthleteDataRepository athlete, UUID userId, UUID id,
                                          ExercisePayloadV1 exercise) {
        if (athlete.exerciseExists(id)) {
            throw new IdConflictException("Exercise id is taken by the catalog or another user");
        }
        athlete.insertExercise(userId, id, exercise);
    }
}
