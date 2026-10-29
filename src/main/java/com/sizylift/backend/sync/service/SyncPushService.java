package com.sizylift.backend.sync.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.dto.ItemStatus;
import com.sizylift.backend.publication.dto.RejectionReason;
import com.sizylift.backend.publication.dto.payload.PayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutReschedulePayloadV1;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.service.AggregateArbiter;
import com.sizylift.backend.publication.service.AggregateChange;
import com.sizylift.backend.publication.service.AggregateChangeListener;
import com.sizylift.backend.publication.service.AggregatePublisher;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.publication.service.DependentAggregates;
import com.sizylift.backend.publication.service.IdConflictException;
import com.sizylift.backend.publication.service.PayloadParser;
import com.sizylift.backend.sync.dto.SyncPushRequest.Item;
import com.sizylift.backend.sync.dto.SyncPushResponse;
import com.sizylift.backend.sync.dto.SyncPushResponse.ItemResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Запись агрегатов с базовой ревизией и правилом «побеждает последнее изменение» (ADR-0007, раздел 3).
 * Каждый агрегат — своя транзакция, ошибка одного не влияет на остальные.
 */
@Service
public class SyncPushService {

    static final int SCHEMA_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(SyncPushService.class);

    private final Map<String, AggregatePublisher<?>> publishers;
    private final PublishedAggregateRepository aggregates;
    private final TransactionTemplate transactions;
    private final PayloadParser parser;
    private final AggregateArbiter arbiter;
    private final AggregateChangeListener feed;
    private final AccessPolicy access;
    private final CoachExerciseAdoption coachExercises;
    private final DependentAggregates dependents;
    private final SyncMetrics metrics;

    SyncPushService(List<AggregatePublisher<?>> publishers, PublishedAggregateRepository aggregates,
                    TransactionTemplate transactions, PayloadParser parser, AggregateArbiter arbiter,
                    AggregateChangeListener feed, AccessPolicy access, CoachExerciseAdoption coachExercises,
                    DependentAggregates dependents, SyncMetrics metrics) {
        this.publishers = publishers.stream()
                .collect(Collectors.toUnmodifiableMap(AggregatePublisher::type, Function.identity()));
        this.aggregates = aggregates;
        this.transactions = transactions;
        this.parser = parser;
        this.arbiter = arbiter;
        this.feed = feed;
        this.access = access;
        this.coachExercises = coachExercises;
        this.dependents = dependents;
        this.metrics = metrics;
    }

    public SyncPushResponse push(UUID userId, List<Item> items) {
        return metrics.observePush(() -> {
            var results = items.stream().map(item -> push(userId, item)).toList();
            results.forEach(metrics::count);
            if (log.isDebugEnabled()) {
                log.debug("Sync push: {}", results.stream()
                        .collect(Collectors.groupingBy(ItemResult::status, Collectors.counting())));
            }
            return new SyncPushResponse(results);
        });
    }

    private ItemResult push(UUID callerId, Item item) {
        var publisher = publishers.get(item.type());
        if (publisher == null) {
            return ItemResult.rejected(item, RejectionReason.UNSUPPORTED_TYPE);
        }
        if (item.schemaVersion() != SCHEMA_VERSION) {
            return ItemResult.rejected(item, RejectionReason.UNSUPPORTED_SCHEMA);
        }
        var write = new Write(callerId, item.ownerId() == null ? callerId : item.ownerId(), item);
        try {
            return item.deleted() ? delete(write, publisher) : replace(write, publisher);
        } catch (PayloadParser.InvalidPayloadException | DataIntegrityViolationException e) {
            log.debug("Rejected {} {}: {}", item.type(), item.id(), e.getClass().getSimpleName());
            return ItemResult.rejected(item, RejectionReason.INVALID_PAYLOAD);
        } catch (IdConflictException e) {
            return ItemResult.rejected(item, RejectionReason.ID_CONFLICT);
        }
    }

    private <P extends PayloadV1> ItemResult replace(Write write, AggregatePublisher<P> publisher) {
        var payload = parser.parse(write.item().payload(), publisher.payloadType());
        if (payload instanceof ProgramPayloadV1 program && !write.byOwner()) {
            return store(write, () -> replaceCoachProgram(write, publisher, program));
        }
        if (payload instanceof WorkoutReschedulePayloadV1 reschedule) {
            return store(write, () -> replaceReschedule(write, publisher, payload, reschedule));
        }
        return store(write, () -> {
            publisher.replace(write.ownerId(), write.item().id(), payload);
            return Effects.NONE;
        });
    }

    /** Перенос на исходную дату программы снимает перенос: tombstone, {@code delete} в ленты (docs/coaching.md). */
    private <P extends PayloadV1> Effects replaceReschedule(Write write, AggregatePublisher<P> publisher, P payload,
                                                           WorkoutReschedulePayloadV1 reschedule) {
        if (dependents.returnsToPlan(write.ownerId(), write.item().id(), reschedule)) {
            publisher.delete(write.ownerId(), write.item().id());
            return Effects.REMOVED;
        }
        publisher.replace(write.ownerId(), write.item().id(), payload);
        return Effects.NONE;
    }

    /** Программа тренера: его упражнения сначала копируются в библиотеку подопечного. */
    private <P extends PayloadV1> Effects replaceCoachProgram(Write write, AggregatePublisher<P> publisher,
                                                              ProgramPayloadV1 program) {
        var adoption = coachExercises.adopt(write.callerId(), write.ownerId(), program);
        publisher.replace(write.ownerId(), write.item().id(), publisher.payloadType().cast(adoption.program()));
        return new Effects(adoption.copies(), adoption.rewritten(program));
    }

    /** Удаление — та же запись с tombstone: конкурирует с правкой по {@code changedAt}. */
    private ItemResult delete(Write write, AggregatePublisher<?> publisher) {
        return store(write, () -> {
            publisher.delete(write.ownerId(), write.item().id());
            return Effects.NONE;
        });
    }

    /**
     * Порядок блокировок во всех записях: аккаунт владельца (разделяемая), строка агрегата, курсоры лент.
     * Изменения связей берут аккаунт подопечного исключительно, поэтому права и фан-аут не меняются до коммита.
     */
    private ItemResult store(Write write, Supplier<Effects> data) {
        var item = write.item();
        var ownerId = write.ownerId();
        return transactions.execute(status -> {
            feed.beforeWrite(ownerId);
            if (!access.canWriteFor(write.callerId(), ownerId, item.type())) {
                return ItemResult.rejected(item, RejectionReason.FORBIDDEN);
            }
            var state = aggregates.lock(ownerId, item.type(), item.id());
            if (!access.canWrite(write.callerId(), ownerId, item.type(), state.authorUserId(), state.exists())) {
                status.setRollbackOnly();
                return ItemResult.rejected(item, RejectionReason.FORBIDDEN);
            }
            if (item.clientVersion() <= aggregates.clientVersion(ownerId, item.type(), item.id(), item.clientId())) {
                return ItemResult.of(item, ItemStatus.STALE, state.revision());
            }
            aggregates.rememberClientVersion(ownerId, item.type(), item.id(), item.clientId(), item.clientVersion());
            if (item.baseRevision() > state.revision()) {
                metrics.countBaseAhead(item.type());
            }
            var changedAt = arbiter.effectiveChangedAt(item.changedAt());
            var conflict = item.baseRevision() != state.revision();
            if (conflict && !arbiter.incomingWins(changedAt, state)) {
                arbiter.incomingLost(ownerId, item.type(), item.id(), item.payload(), changedAt, item.clientId(),
                        state.revision());
                return ItemResult.of(item, ItemStatus.SUPERSEDED, state.revision());
            }
            var lost = conflict && state.exists() ? arbiter.captureStored(ownerId, item.type(), item.id(), state)
                    : null;
            var before = dependents.capture(ownerId, item.type(), item.id());
            var effects = data.get();
            var deleted = item.deleted() || effects.removed();
            var revision = aggregates.storeSynced(ownerId, item.type(), item.id(), deleted, changedAt,
                    item.clientId(), item.clientVersion());
            if (!state.exists() && !write.callerId().equals(ownerId)) {
                aggregates.assignAuthor(ownerId, item.type(), item.id(), write.callerId());
            }
            if (lost != null) {
                arbiter.storedLost(lost, revision);
            }
            for (var copy : effects.copies()) {
                feed.changed(AggregateChange.byServer(ownerId, AggregateType.EXERCISE, copy.id(), copy.revision(),
                        false));
            }
            for (var removed : dependents.reconcile(ownerId, item.type(), item.id(), deleted, before)) {
                feed.changed(AggregateChange.byServer(ownerId, removed.type(), removed.id(), removed.revision(),
                        true));
            }
            feed.changed(new AggregateChange(ownerId, write.callerId(), item.type(), item.id(), revision,
                    deleted, !state.exists() || state.deleted()));
            return ItemResult.accepted(item, revision, effects.payloadRewritten());
        });
    }

    /** Кто пишет, чьи данные и что. */
    private record Write(UUID callerId, UUID ownerId, Item item) {

        boolean byOwner() {
            return callerId.equals(ownerId);
        }
    }

    /**
     * Побочные результаты записи данных: созданные копии упражнений, замена payload и {@code removed} — правка
     * сохранена как удаление (перенос на исходную дату).
     */
    private record Effects(List<CoachExerciseAdoption.CopiedExercise> copies, boolean payloadRewritten,
                           boolean removed) {

        static final Effects NONE = new Effects(List.of(), false, false);
        static final Effects REMOVED = new Effects(List.of(), true, true);

        Effects(List<CoachExerciseAdoption.CopiedExercise> copies, boolean payloadRewritten) {
            this(copies, payloadRewritten, false);
        }
    }
}
