package com.sizylift.backend.web.service;

import java.time.LocalDate;
import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.web.dto.MaxCandidateDtos.MaxCandidatesResponse;
import com.sizylift.backend.web.repository.MaxCandidateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Кандидаты для расчётного ПМ. Сервер ПМ не хранит и не считает: калькулятор один, у клиента.
 * Права — как у чтения тренировок в {@link WebReadService}: без права ответ 404.
 */
@Service
public class MaxCandidateService {

    private final MaxCandidateRepository repository;
    private final AccessPolicy access;

    MaxCandidateService(MaxCandidateRepository repository, AccessPolicy access) {
        this.repository = repository;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public MaxCandidatesResponse candidates(UUID caller, UUID owner, LocalDate from) {
        var target = owner == null ? caller : owner;
        if (!access.canRead(caller, target, AggregateType.WORKOUT)) {
            throw new WebReadService.NotFound();
        }
        return new MaxCandidatesResponse(from, repository.completedSince(target, from));
    }
}
