package com.sizylift.backend.web.service;

import java.util.UUID;
import java.util.List;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import com.sizylift.backend.web.dto.WebDtos.*;
import com.sizylift.backend.web.repository.DiaryReadRepository;
import com.sizylift.backend.web.repository.StandaloneWorkoutRepository;
import com.sizylift.backend.publication.repository.BetweenDataRepository;
import com.sizylift.backend.publication.repository.OneRepMaxHistoryRepository;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.publication.service.PayloadReader;
import com.sizylift.backend.web.dto.WebDtos.CatalogResponse;
import com.sizylift.backend.web.dto.WebDtos.ExerciseHistoryResponse;
import com.sizylift.backend.web.dto.WebDtos.OneRepMaxListResponse;
import com.sizylift.backend.web.dto.WebDtos.ProgramListResponse;
import com.sizylift.backend.web.dto.WebDtos.ProgramResponse;
import com.sizylift.backend.web.repository.WebReadRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Чтение данных для веба. Чужие данные — только по {@link AccessPolicy#canRead}; без права ответ такой же,
 * как для несуществующих данных (404), чтобы не раскрывать, что они есть.
 */
@Service
public class WebReadService {

    public static final int DEFAULT_HISTORY_LIMIT = 5;
    public static final int MAX_HISTORY_LIMIT = 20;
    public static final int MAX_HISTORY_OFFSET = 100_000;

    private final WebReadRepository repository;
    private final PayloadReader payloads;
    private final AccessPolicy access;
    private final DiaryReadRepository diary;
    private final BetweenDataRepository between;
    private final StandaloneWorkoutRepository standalone;
    private final OneRepMaxHistoryRepository oneRepMaxHistory;
    private static final long MAX_PERIOD_DAYS = 366;

    WebReadService(WebReadRepository repository, PayloadReader payloads, AccessPolicy access,
                   DiaryReadRepository diary, BetweenDataRepository between,
                   StandaloneWorkoutRepository standalone, OneRepMaxHistoryRepository oneRepMaxHistory) {
        this.repository = repository;
        this.payloads = payloads;
        this.access = access;
        this.diary = diary;
        this.between = between;
        this.standalone = standalone;
        this.oneRepMaxHistory = oneRepMaxHistory;
    }

    /** Каталог и свои упражнения; тренер с {@code owner} подопечного видит ещё и его упражнения. */
    @Transactional(readOnly = true)
    public CatalogResponse catalog(UUID caller, UUID owner) {
        var target = readable(caller, owner, AggregateType.EXERCISE);
        return new CatalogResponse(repository.categories(), repository.equipment(), repository.muscles(),
                repository.exercises(caller, target));
    }

    @Transactional(readOnly = true)
    public ProgramListResponse programs(UUID caller, UUID owner) {
        var target = readable(caller, owner, AggregateType.PROGRAM);
        return new ProgramListResponse(target, repository.programs(target));
    }

    /** Программа и её ревизия читаются из одного снимка: ревизия соответствует отданным данным. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProgramResponse program(UUID caller, UUID owner, UUID programId) {
        var target = readable(caller, owner, AggregateType.PROGRAM);
        var head = repository.head(target, AggregateType.PROGRAM, programId).orElseThrow(NotFound::new);
        var program = payloads.read(target, AggregateType.PROGRAM, programId)
                .map(ProgramPayloadV1.class::cast)
                .orElseThrow(NotFound::new);
        return new ProgramResponse(target, programId, head.revision(), head.authorId(), head.changedAt(), program);
    }

    @Transactional(readOnly = true)
    public OneRepMaxListResponse oneRepMaxes(UUID caller, UUID owner) {
        var target = readable(caller, owner, AggregateType.ONE_REP_MAX);
        return new OneRepMaxListResponse(target, repository.oneRepMaxes(target));
    }

    /** История 1ПМ упражнения; удалённый агрегат истории не имеет. */
    @Transactional(readOnly = true)
    public OneRepMaxHistoryResponse oneRepMaxHistory(UUID caller, UUID owner, UUID exerciseId) {
        var target = readable(caller, owner, AggregateType.ONE_REP_MAX);
        return new OneRepMaxHistoryResponse(oneRepMaxHistory.history(target, exerciseId).stream()
                .map(entry -> new OneRepMaxHistoryEntry(entry.weightGrams(), entry.achievedOn(), entry.recordedAt()))
                .toList());
    }

    @Transactional(readOnly = true)
    public ExerciseHistoryResponse history(UUID caller, UUID owner, UUID exerciseId, int limit, int offset) {
        var target = readable(caller, owner, AggregateType.WORKOUT);
        return new ExerciseHistoryResponse(target, exerciseId, repository.history(target, exerciseId, limit, offset));
    }


    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TimelineResponse timeline(UUID caller, UUID owner, LocalDate from, LocalDate to) {
        var target = readable(caller, owner, AggregateType.WORKOUT);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_PERIOD_DAYS) {
            throw new InvalidPeriod();
        }
        var planned = java.util.stream.Stream.concat(diary.planned(target, from, to).stream(),
                standalone.planned(target, from, to).stream()).sorted(java.util.Comparator.comparing(PlannedSummary::scheduledDate)).toList();
        return new TimelineResponse(target, planned, diary.workouts(target, from, to));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkoutResponse workout(UUID caller, UUID owner, UUID id) {
        var target = readable(caller, owner, AggregateType.WORKOUT);
        var head = repository.head(target, AggregateType.WORKOUT, id).orElseThrow(NotFound::new);
        var workout = payloads.read(target, AggregateType.WORKOUT, id)
                .map(WorkoutPayloadV1.class::cast).orElseThrow(NotFound::new);
        var comments = between.commentsOf(target, id).stream()
                .map(commentId -> aggregate(target, AggregateType.WORKOUT_COMMENT, commentId))
                .sorted(java.util.Comparator.comparing(comment ->
                        ((com.sizylift.backend.publication.dto.payload.WorkoutCommentPayloadV1) comment.payload()).createdAt()))
                .toList();
        return new WorkoutResponse(target, id, head.revision(), workout, comments);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProfileResponse profile(UUID caller, UUID owner) {
        var target = readable(caller, owner, AggregateType.ATHLETE_PROFILE);
        var profiles = aggregates(target, AggregateType.ATHLETE_PROFILE);
        boolean health = access.canRead(caller, target, AggregateType.INJURY);
        return new ProfileResponse(target, profiles.isEmpty() ? null : profiles.getFirst(), health,
                health ? aggregates(target, AggregateType.INJURY) : List.of(),
                health ? aggregates(target, AggregateType.CONTRAINDICATION) : List.of());
    }

    private List<AggregateResponse> aggregates(UUID owner, String type) {
        return diary.ids(owner, type).stream().map(id -> aggregate(owner, type, id)).toList();
    }

    private AggregateResponse aggregate(UUID owner, String type, UUID id) {
        var head = repository.head(owner, type, id).orElseThrow(NotFound::new);
        return new AggregateResponse(id, head.revision(), head.authorId(),
                payloads.read(owner, type, id).orElseThrow(NotFound::new));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidPeriod extends RuntimeException {
    }

    /** Владелец данных: сам вызывающий, если {@code owner} не задан, иначе тот, чьи данные он вправе читать. */
    private UUID readable(UUID caller, UUID owner, String type) {
        var target = owner == null ? caller : owner;
        if (!access.canRead(caller, target, type)) {
            throw new NotFound();
        }
        return target;
    }

    /** Данных нет или их нельзя читать. */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class NotFound extends RuntimeException {
    }
}
