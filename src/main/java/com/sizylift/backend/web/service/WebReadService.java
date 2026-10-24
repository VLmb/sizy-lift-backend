package com.sizylift.backend.web.service;

import java.util.UUID;

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

    private final WebReadRepository repository;
    private final PayloadReader payloads;
    private final AccessPolicy access;

    WebReadService(WebReadRepository repository, PayloadReader payloads, AccessPolicy access) {
        this.repository = repository;
        this.payloads = payloads;
        this.access = access;
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

    @Transactional(readOnly = true)
    public ExerciseHistoryResponse history(UUID caller, UUID owner, UUID exerciseId, int limit) {
        var target = readable(caller, owner, AggregateType.WORKOUT);
        return new ExerciseHistoryResponse(target, exerciseId, repository.history(target, exerciseId, limit));
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
