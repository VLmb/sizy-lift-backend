package com.sizylift.backend.publication.service;

import com.sizylift.backend.publication.dto.payload.AthleteProfilePayloadV1;
import com.sizylift.backend.publication.dto.payload.ContraindicationPayloadV1;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.InjuryPayloadV1;
import com.sizylift.backend.publication.dto.payload.OneRepMaxPayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.StandaloneWorkoutPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutCommentPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutReschedulePayloadV1;
import com.sizylift.backend.publication.repository.AthleteDataRepository;
import com.sizylift.backend.publication.repository.BetweenDataRepository;
import com.sizylift.backend.publication.repository.ProgramDataRepository;
import com.sizylift.backend.publication.repository.StandaloneDataRepository;
import com.sizylift.backend.publication.repository.WorkoutDataRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** По одному reader на тип агрегата, те же типы, что у {@link AggregatePublishers}. */
@Configuration(proxyBeanMethods = false)
class AggregateReaders {

    @Bean
    AggregateReader<WorkoutPayloadV1> workoutReader(WorkoutDataRepository workouts) {
        return new AggregateReader<>(AggregateType.WORKOUT, workouts::find);
    }

    @Bean
    AggregateReader<ProgramPayloadV1> programReader(ProgramDataRepository programs) {
        return new AggregateReader<>(AggregateType.PROGRAM, programs::find);
    }

    @Bean
    AggregateReader<ExercisePayloadV1> exerciseReader(AthleteDataRepository athlete) {
        return new AggregateReader<>(AggregateType.EXERCISE, athlete::findExercise);
    }

    @Bean
    AggregateReader<AthleteProfilePayloadV1> athleteProfileReader(AthleteDataRepository athlete) {
        return new AggregateReader<>(AggregateType.ATHLETE_PROFILE, (userId, id) -> athlete.findProfile(userId));
    }

    @Bean
    AggregateReader<OneRepMaxPayloadV1> oneRepMaxReader(AthleteDataRepository athlete) {
        return new AggregateReader<>(AggregateType.ONE_REP_MAX, athlete::findOneRepMax);
    }

    @Bean
    AggregateReader<InjuryPayloadV1> injuryReader(AthleteDataRepository athlete) {
        return new AggregateReader<>(AggregateType.INJURY, athlete::findInjury);
    }

    @Bean
    AggregateReader<ContraindicationPayloadV1> contraindicationReader(AthleteDataRepository athlete) {
        return new AggregateReader<>(AggregateType.CONTRAINDICATION, athlete::findContraindication);
    }

    @Bean
    AggregateReader<WorkoutReschedulePayloadV1> workoutRescheduleReader(BetweenDataRepository between) {
        return new AggregateReader<>(AggregateType.WORKOUT_RESCHEDULE, between::findReschedule);
    }

    @Bean
    AggregateReader<StandaloneWorkoutPayloadV1> standaloneWorkoutReader(StandaloneDataRepository standalone) {
        return new AggregateReader<>(AggregateType.STANDALONE_WORKOUT, standalone::find);
    }

    @Bean
    AggregateReader<WorkoutCommentPayloadV1> workoutCommentReader(BetweenDataRepository between) {
        return new AggregateReader<>(AggregateType.WORKOUT_COMMENT, between::findComment);
    }
}
