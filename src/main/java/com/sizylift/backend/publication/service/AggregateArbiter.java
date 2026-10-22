package com.sizylift.backend.publication.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.sizylift.backend.publication.entity.AggregateState;
import com.sizylift.backend.publication.repository.AggregateConflictRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Правило «побеждает последнее изменение» (ADR-0007, раздел 3) и журнал проигравших версий.
 * Вызывается внутри транзакции, держащей блокировку агрегата.
 */
@Component
public class AggregateArbiter {

    static final String CONFLICTS_METER = "sizylift.sync.conflicts";
    /** Входящая правка записана, сохранённая версия ушла в журнал. */
    static final String OUTCOME_ACCEPTED = "accepted";
    /** Входящая правка проиграла и ушла в журнал. */
    static final String OUTCOME_SUPERSEDED = "superseded";

    private final AggregateConflictRepository conflicts;
    private final Clock clock;
    private final MeterRegistry meters;

    AggregateArbiter(AggregateConflictRepository conflicts, Clock clock, MeterRegistry meters) {
        this.conflicts = conflicts;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Время правки, которым можно сравнивать: не позже серверного «сейчас», чтобы правка из будущего не
     * выигрывала вечно, и с точностью PostgreSQL (микросекунды). {@code null} означает «сейчас».
     */
    public Instant effectiveChangedAt(Instant claimed) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (claimed == null) {
            return now;
        }
        var truncated = claimed.truncatedTo(ChronoUnit.MICROS);
        return truncated.isAfter(now) ? now : truncated;
    }

    /** Ничья решается в пользу сохранённой версии: она детерминирована. */
    public boolean incomingWins(Instant incomingChangedAt, AggregateState stored) {
        return stored.changedAt() == null || incomingChangedAt.isAfter(stored.changedAt());
    }

    public void incomingLost(UUID userId, String type, UUID aggregateId, JsonNode payload, Instant changedAt,
                             String clientId, long winnerRevision) {
        conflicts.record(userId, type, aggregateId, payload == null ? null : payload.toString(), changedAt,
                clientId, winnerRevision);
        count(type, OUTCOME_SUPERSEDED);
    }

    /** Содержимого вытесненной версии нет, пока сервер не умеет собирать payload из таблиц (AggregateReader). */
    public void storedLost(UUID userId, String type, UUID aggregateId, AggregateState lost, long winnerRevision) {
        conflicts.record(userId, type, aggregateId, null, lost.changedAt(), lost.sourceClientId(), winnerRevision);
        count(type, OUTCOME_ACCEPTED);
    }

    private void count(String type, String outcome) {
        meters.counter(CONFLICTS_METER, "type", type, "outcome", outcome).increment();
    }
}
