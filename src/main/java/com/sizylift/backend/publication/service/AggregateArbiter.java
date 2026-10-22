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
import tools.jackson.databind.ObjectMapper;

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
    private final PayloadReader payloads;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry meters;

    AggregateArbiter(AggregateConflictRepository conflicts, PayloadReader payloads, ObjectMapper json, Clock clock,
                     MeterRegistry meters) {
        this.conflicts = conflicts;
        this.payloads = payloads;
        this.json = json;
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

    /**
     * Снимок сохранённой версии до того, как её заменит выигравшая правка; у tombstone содержимого нет.
     * Вызывается до записи, в той же транзакции.
     */
    public StoredVersion captureStored(UUID userId, String type, UUID aggregateId, AggregateState stored) {
        var payload = stored.deleted() ? null
                : payloads.read(userId, type, aggregateId).map(json::writeValueAsString).orElse(null);
        return new StoredVersion(userId, type, aggregateId, payload, stored.changedAt(), stored.sourceClientId());
    }

    public void storedLost(StoredVersion lost, long winnerRevision) {
        conflicts.record(lost.userId(), lost.type(), lost.aggregateId(), lost.payload(), lost.changedAt(),
                lost.clientId(), winnerRevision);
        count(lost.type(), OUTCOME_ACCEPTED);
    }

    /** Вытесняемая версия агрегата; {@code payload} — JSON v1 или {@code null} у tombstone. */
    public record StoredVersion(UUID userId, String type, UUID aggregateId, String payload, Instant changedAt,
                                String clientId) {
    }

    private void count(String type, String outcome) {
        meters.counter(CONFLICTS_METER, "type", type, "outcome", outcome).increment();
    }
}
