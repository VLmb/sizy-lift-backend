package com.sizylift.backend.publication.service;

import java.util.UUID;

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
import com.sizylift.backend.publication.repository.OneRepMaxHistoryRepository;
import com.sizylift.backend.publication.repository.ProgramDataRepository;
import com.sizylift.backend.publication.repository.StandaloneDataRepository;
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
                (userId, id, exercise) -> withLiftClass(athlete, userId, id, exercise),
                (userId, id, exercise) -> insertOwnExercise(athlete, userId, id, exercise),
                athlete::deleteExercise);
    }

    @Bean
    AggregatePublisher<AthleteProfilePayloadV1> athleteProfilePublisher(AthleteDataRepository athlete) {
        return new AggregatePublisher<>(AggregateType.ATHLETE_PROFILE, AthleteProfilePayloadV1.class,
                athlete::insertProfile, (userId, id) -> athlete.deleteProfile(userId));
    }

    @Bean
    AggregatePublisher<OneRepMaxPayloadV1> oneRepMaxPublisher(AthleteDataRepository athlete,
                                                              OneRepMaxHistoryRepository history) {
        return new AggregatePublisher<>(AggregateType.ONE_REP_MAX, OneRepMaxPayloadV1.class,
                (userId, id, value) -> {
                    athlete.insertOneRepMax(userId, id, value);
                    history.record(userId, id, value);
                },
                athlete::deleteOneRepMax, history::delete);
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

    @Bean
    AggregatePublisher<WorkoutReschedulePayloadV1> workoutReschedulePublisher(BetweenDataRepository between) {
        return new AggregatePublisher<>(AggregateType.WORKOUT_RESCHEDULE, WorkoutReschedulePayloadV1.class,
                between::insertReschedule, between::deleteReschedule);
    }

    /**
     * Разовая тренировка: замена — upsert без удаления (сохраняется дата создания), строку удаляет только
     * tombstone.
     */
    @Bean
    AggregatePublisher<StandaloneWorkoutPayloadV1> standaloneWorkoutPublisher(StandaloneDataRepository standalone) {
        return new AggregatePublisher<>(AggregateType.STANDALONE_WORKOUT, StandaloneWorkoutPayloadV1.class,
                standalone::insert, (userId, id) -> {
                }, standalone::delete);
    }

    @Bean
    AggregatePublisher<WorkoutCommentPayloadV1> workoutCommentPublisher(BetweenDataRepository between) {
        return new AggregatePublisher<>(AggregateType.WORKOUT_COMMENT, WorkoutCommentPayloadV1.class,
                between::insertComment, between::deleteComment);
    }

    /**
     * Класс упражнения по правилам переходного периода (docs/coach-workspace.md, раздел 1); SBD и «Базовое»
     * не у «вес × повторения» — некорректный payload.
     */
    private static ExercisePayloadV1 withLiftClass(AthleteDataRepository athlete, UUID userId, UUID id,
                                                   ExercisePayloadV1 exercise) {
        var liftClass = exercise.liftClassToStore(athlete.findLiftClass(userId, id).orElse(null));
        if (!liftClass.allows(exercise.resultType())) {
            throw new PayloadParser.InvalidPayloadException();
        }
        return exercise.withLiftClass(liftClass);
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
