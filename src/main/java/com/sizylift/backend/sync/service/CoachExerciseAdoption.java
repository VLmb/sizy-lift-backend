package com.sizylift.backend.sync.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.StandaloneWorkoutPayloadV1;
import com.sizylift.backend.publication.repository.AthleteDataRepository;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.service.AggregateArbiter;
import com.sizylift.backend.publication.service.AggregatePublisher;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.publication.service.PayloadReader;
import org.springframework.stereotype.Component;

/**
 * Упражнения тренера в программе подопечного. Каталог упражнений у сервера общий, id упражнения уникален
 * глобально, поэтому подопечный не может хранить упражнение тренера под тем же id. Сервер копирует каждое
 * собственное упражнение тренера, на которое ссылается программа, в библиотеку подопечного под производным
 * id и переписывает ссылки программы на копию. Копия принадлежит подопечному: он её правит, и она остаётся
 * после разрыва связи.
 */
@Component
class CoachExerciseAdoption {

    /** Клиент записи копии: её создаёт сервер, а не устройство. */
    static final String SERVER_CLIENT_ID = "server:coach-exercise-copy";
    static final long SERVER_CLIENT_VERSION = 1;
    private static final String COPY_ID_PREFIX = "sizylift:coach-exercise-copy:";

    private final AthleteDataRepository exercises;
    private final PublishedAggregateRepository aggregates;
    private final PayloadReader payloads;
    private final AggregatePublisher<ExercisePayloadV1> exercisePublisher;
    private final AggregateArbiter arbiter;

    CoachExerciseAdoption(AthleteDataRepository exercises, PublishedAggregateRepository aggregates,
                          PayloadReader payloads, AggregatePublisher<ExercisePayloadV1> exercisePublisher,
                          AggregateArbiter arbiter) {
        this.exercises = exercises;
        this.aggregates = aggregates;
        this.payloads = payloads;
        this.exercisePublisher = exercisePublisher;
        this.arbiter = arbiter;
    }

    /** Id копии упражнения тренера у подопечного; одинаковый при каждой записи программы. */
    static UUID copyId(UUID athleteId, UUID coachExerciseId) {
        return UUID.nameUUIDFromBytes((COPY_ID_PREFIX + athleteId + ":" + coachExerciseId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Копирует упражнения тренера подопечному, если копии ещё нет или подопечный её удалил, и возвращает
     * программу со ссылками на копии. Существующую копию не трогает: это уже упражнение подопечного.
     * Вызывается внутри транзакции записи программы; строки агрегатов копий блокируются в порядке id.
     */
    Adoption adopt(UUID coachId, UUID athleteId, ProgramPayloadV1 program) {
        var copied = copyReferenced(coachId, athleteId, program.cycles().stream()
                .flatMap(cycle -> cycle.weeks().stream())
                .flatMap(week -> week.workouts().stream())
                .toList());
        return copied.copies().isEmpty()
                ? new Adoption(program, copied.changes())
                : new Adoption(rewrite(program, copied.copies()), copied.changes());
    }

    /** То же для разовой тренировки тренера (ADR-0011). */
    StandaloneAdoption adopt(UUID coachId, UUID athleteId, StandaloneWorkoutPayloadV1 standalone) {
        var copied = copyReferenced(coachId, athleteId, List.of(standalone.workout()));
        return copied.copies().isEmpty()
                ? new StandaloneAdoption(standalone, copied.changes())
                : new StandaloneAdoption(new StandaloneWorkoutPayloadV1(
                        rewrite(standalone.workout(), copied.copies()), standalone.updatedAt()), copied.changes());
    }

    /** Копирует упражнения тренера, на которые ссылаются тренировки; возвращает замену id и созданные копии. */
    private Copied copyReferenced(UUID coachId, UUID athleteId, List<ProgramPayloadV1.PlannedWorkout> workouts) {
        var referenced = workouts.stream()
                .flatMap(workout -> workout.blocks().stream())
                .flatMap(block -> block.exercises().stream())
                .map(ProgramPayloadV1.Exercise::exerciseId)
                .distinct()
                .toList();
        var coachOwned = referenced.isEmpty() ? List.<UUID>of() : exercises.ownedExercises(coachId, referenced);
        if (coachOwned.isEmpty()) {
            return new Copied(Map.of(), List.of());
        }
        var copies = coachOwned.stream()
                .collect(Collectors.toMap(Function.identity(), id -> copyId(athleteId, id)));
        var changes = new ArrayList<CopiedExercise>();
        copies.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(UUID::toString)))
                .forEach(copy -> copyIfMissing(coachId, athleteId, copy.getKey(), copy.getValue())
                        .ifPresent(changes::add));
        return new Copied(copies, changes);
    }

    private record Copied(Map<UUID, UUID> copies, List<CopiedExercise> changes) {
    }

    private Optional<CopiedExercise> copyIfMissing(UUID coachId, UUID athleteId, UUID original, UUID copyId) {
        var state = aggregates.lock(athleteId, AggregateType.EXERCISE, copyId);
        if (state.exists() && !state.deleted()) {
            return Optional.empty();
        }
        var payload = payloads.read(coachId, AggregateType.EXERCISE, original)
                .map(ExercisePayloadV1.class::cast);
        if (payload.isEmpty()) {
            return Optional.empty();
        }
        exercisePublisher.replace(athleteId, copyId, payload.get());
        var revision = aggregates.storeSynced(athleteId, AggregateType.EXERCISE, copyId, false,
                arbiter.effectiveChangedAt(null), SERVER_CLIENT_ID, SERVER_CLIENT_VERSION);
        return Optional.of(new CopiedExercise(copyId, revision));
    }

    private static ProgramPayloadV1 rewrite(ProgramPayloadV1 program, Map<UUID, UUID> copies) {
        return new ProgramPayloadV1(program.name(), program.description(), program.startDate(), program.status(),
                program.updatedAt(), program.cycles().stream().map(cycle -> new ProgramPayloadV1.Cycle(cycle.id(),
                        cycle.name(), cycle.position(), cycle.weeks().stream().map(week -> new ProgramPayloadV1.Week(
                                week.id(), week.number(), week.name(), week.startDate(), week.endDate(),
                                week.position(), week.workouts().stream()
                                .map(workout -> rewrite(workout, copies)).toList())).toList())).toList());
    }

    private static ProgramPayloadV1.PlannedWorkout rewrite(ProgramPayloadV1.PlannedWorkout workout,
                                                           Map<UUID, UUID> copies) {
        return new ProgramPayloadV1.PlannedWorkout(workout.id(), workout.title(), workout.workoutDate(),
                workout.position(), workout.blocks().stream().map(block -> new ProgramPayloadV1.Block(block.id(),
                        block.position(), block.blockType(), block.groupLabel(), block.freeKind(), block.title(),
                        block.description(), block.exercises().stream()
                        .map(exercise -> rewrite(exercise, copies)).toList())).toList());
    }

    private static ProgramPayloadV1.Exercise rewrite(ProgramPayloadV1.Exercise exercise, Map<UUID, UUID> copies) {
        return new ProgramPayloadV1.Exercise(exercise.id(), exercise.position(),
                copies.getOrDefault(exercise.exerciseId(), exercise.exerciseId()), exercise.exerciseName(),
                exercise.resultType(), exercise.planComment(), exercise.sets());
    }

    /** Программа после замены ссылок и созданные копии (их нужно разослать в ленты). */
    record Adoption(ProgramPayloadV1 program, List<CopiedExercise> copies) {

        boolean rewritten(ProgramPayloadV1 original) {
            return !program.equals(original);
        }
    }

    /** Разовая тренировка после замены ссылок и созданные копии. */
    record StandaloneAdoption(StandaloneWorkoutPayloadV1 standalone, List<CopiedExercise> copies) {

        boolean rewritten(StandaloneWorkoutPayloadV1 original) {
            return !standalone.equals(original);
        }
    }

    record CopiedExercise(UUID id, long revision) {
    }
}
