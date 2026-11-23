package com.sizylift.backend.sync.service;

import java.time.Instant;
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
import com.sizylift.backend.publication.dto.payload.ProgramDecisionPayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.StandaloneWorkoutPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutReschedulePayloadV1;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.repository.BetweenDataRepository;
import com.sizylift.backend.publication.repository.ProgramDecisionRepository;
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
    private static final String ACTIVE_PROGRAM = "active";
    private static final String ASSIGNMENT_CLIENT = "server:program-assignment";
    private static final long ASSIGNMENT_CLIENT_VERSION = 1;
    /** Агрегаты, которых у пользователя не больше одного, хотя id выбирает клиент (одна строка на сервере). */
    private static final java.util.Set<String> SINGLE_PER_USER = java.util.Set.of(AggregateType.ATHLETE_PROFILE);
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
    private final BetweenDataRepository between;
    private final ProgramDecisionRepository decisions;

    SyncPushService(List<AggregatePublisher<?>> publishers, PublishedAggregateRepository aggregates,
                    TransactionTemplate transactions, PayloadParser parser, AggregateArbiter arbiter,
                    AggregateChangeListener feed, AccessPolicy access, CoachExerciseAdoption coachExercises,
                    DependentAggregates dependents, SyncMetrics metrics, BetweenDataRepository between,
                    ProgramDecisionRepository decisions) {
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
        this.between = between;
        this.decisions = decisions;
    }

    public SyncPushResponse push(UUID userId, List<Item> items) {
        return metrics.observePush(() -> {
            var results = items.stream().map(item -> push(userId, item, false)).toList();
            results.forEach(metrics::count);
            if (log.isDebugEnabled()) {
                log.debug("Sync push: {}", results.stream()
                        .collect(Collectors.groupingBy(ItemResult::status, Collectors.counting())));
            }
            return new SyncPushResponse(results);
        });
    }

    /**
     * Импорт создаёт только новые агрегаты. Внешняя транзакция включает квитанцию импорта;
     * отказ любой записи откатывает упражнения, программу, ленты и уведомления вместе.
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public SyncPushResponse createImportedProgram(UUID caller, List<Item> items) {
        if (items.isEmpty() || items.size() > SyncPushRequestLimit.MAX_IMPORT_ITEMS
                || items.stream().filter(item -> item.type().equals(AggregateType.PROGRAM)).count() != 1
                || items.stream().anyMatch(item -> item.deleted() || item.baseRevision() != 0
                    || !java.util.Set.of(AggregateType.PROGRAM, AggregateType.EXERCISE).contains(item.type())
                    || item.type().equals(AggregateType.EXERCISE) && item.ownerId() != null && !item.ownerId().equals(caller)))
            throw new AtomicCreateRejected();
        var owners = items.stream().map(item -> item.ownerId() == null ? caller : item.ownerId())
                .distinct().sorted(AccessPolicy.LOCK_ORDER).toList();
        owners.forEach(access::lockOwner);
        for (var item : items) {
            var owner = item.ownerId() == null ? caller : item.ownerId();
            if (!access.canWriteFor(caller, owner, item.type()) || aggregates.lock(owner, item.type(), item.id()).exists())
                throw new AtomicCreateRejected();
        }
        return feed.deferChanges(() -> {
            var results = new java.util.ArrayList<ItemResult>();
            for (var item : items) {
                var result = push(caller, item, false);
                if (result.status() != ItemStatus.ACCEPTED) throw new AtomicCreateRejected();
                results.add(result);
            }
            return new SyncPushResponse(List.copyOf(results));
        });
    }

    public static class AtomicCreateRejected extends RuntimeException { }
    private static final class SyncPushRequestLimit {
        private static final int MAX_IMPORT_ITEMS = 2001;
    }

    /** Серверное наполнение: штатные ревизии и ленты, без push о якобы реальных действиях пользователя. */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public SyncPushResponse pushGeneratedForOwner(UUID owner, List<Item> items) {
        if (items.stream().anyMatch(item -> item.ownerId() != null || item.baseRevision() != 0 || item.deleted()))
            throw new IllegalArgumentException("Допустимо только создание собственных тестовых агрегатов");
        var results = new java.util.ArrayList<ItemResult>();
        for (var item : items) {
            var result = push(owner, item, true);
            if (result.status() != ItemStatus.ACCEPTED) throw new GeneratedDataRejectedException();
            results.add(result);
        }
        return new SyncPushResponse(List.copyOf(results));
    }

    public static class GeneratedDataRejectedException extends RuntimeException { }

    private ItemResult push(UUID callerId, Item item, boolean generated) {
        var publisher = publishers.get(item.type());
        if (publisher == null) {
            return ItemResult.rejected(item, RejectionReason.UNSUPPORTED_TYPE);
        }
        if (item.schemaVersion() != SCHEMA_VERSION) {
            return ItemResult.rejected(item, RejectionReason.UNSUPPORTED_SCHEMA);
        }
        var write = new Write(callerId, item.ownerId() == null ? callerId : item.ownerId(), item, generated);
        if (AggregateType.PROGRAM_DECISION.equals(item.type()) && (!write.byOwner() || item.deleted())) {
            return ItemResult.rejected(item, RejectionReason.FORBIDDEN);
        }
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
        if (payload instanceof ProgramDecisionPayloadV1 decision
                && ProgramDecisionPayloadV1.PENDING.equals(decision.decision())) {
            return ItemResult.rejected(write.item(), RejectionReason.INVALID_PAYLOAD);
        }
        if (payload instanceof ProgramPayloadV1 program && !write.byOwner()) {
            return store(write, () -> replaceCoachProgram(write, publisher, program));
        }
        if (payload instanceof StandaloneWorkoutPayloadV1 standalone && !write.byOwner()) {
            return store(write, () -> replaceCoachStandalone(write, publisher, standalone));
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

    /** Разовая тренировка тренера: как программа, упражнения тренера копируются подопечному (ADR-0011). */
    private <P extends PayloadV1> Effects replaceCoachStandalone(Write write, AggregatePublisher<P> publisher,
                                                                 StandaloneWorkoutPayloadV1 standalone) {
        var adoption = coachExercises.adopt(write.callerId(), write.ownerId(), standalone);
        publisher.replace(write.ownerId(), write.item().id(), publisher.payloadType().cast(adoption.standalone()));
        return new Effects(adoption.copies(), adoption.rewritten(standalone));
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
            if (AggregateType.PROGRAM_DECISION.equals(item.type()) && !canAnswer(ownerId, item.id())) {
                status.setRollbackOnly();
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
            var rivals = rivals(ownerId, item);
            if (rivals.stream().anyMatch(rival -> !arbiter.incomingWins(changedAt, rival.state()))) {
                arbiter.incomingLost(ownerId, item.type(), item.id(), item.payload(), changedAt, item.clientId(),
                        rivals.getFirst().state().revision());
                return ItemResult.of(item, ItemStatus.SUPERSEDED, state.revision());
            }
            var replacedRivals = rivals.stream()
                    .map(rival -> arbiter.captureStored(ownerId, item.type(), rival.aggregateId(), rival.state()))
                    .toList();
            var lost = conflict && state.exists() ? arbiter.captureStored(ownerId, item.type(), item.id(), state)
                    : null;
            var before = dependents.capture(ownerId, item.type(), item.id());
            var wasActive = AggregateType.PROGRAM.equals(item.type()) && !write.byOwner()
                    && isProgramActive(ownerId, item.id());
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
            for (var rival : replacedRivals) {
                var retired = aggregates.retire(ownerId, item.type(), rival.aggregateId());
                arbiter.storedLost(rival, revision);
                feed.changed(AggregateChange.byServer(ownerId, item.type(), rival.aggregateId(), retired, true));
            }
            for (var copy : effects.copies()) {
                feed.changed(AggregateChange.byServer(ownerId, AggregateType.EXERCISE, copy.id(), copy.revision(),
                        false));
            }
            for (var removed : dependents.reconcile(ownerId, item.type(), item.id(), deleted, before)) {
                feed.changed(AggregateChange.byServer(ownerId, removed.type(), removed.id(), removed.revision(),
                        true));
            }
            if (!deleted && !wasActive && AggregateType.PROGRAM.equals(item.type())
                    && !write.byOwner() && isProgramActive(ownerId, item.id())) {
                offerProgram(ownerId, item.id(), changedAt);
            }
            feed.changed(new AggregateChange(ownerId, write.generated() ? null : write.callerId(), item.type(), item.id(), revision,
                    deleted, !state.exists() || state.deleted()));
            return ItemResult.accepted(item, revision, effects.payloadRewritten());
        });
    }

    private boolean canAnswer(UUID owner, UUID programId) {
        var program = aggregates.lock(owner, AggregateType.PROGRAM, programId);
        return program.exists() && !program.deleted() && program.authorUserId() != null
                && access.activeCoach(owner).filter(program.authorUserId()::equals).isPresent()
                && isProgramActive(owner, programId);
    }

    private boolean isProgramActive(UUID owner, UUID programId) {
        return between.programStatus(owner, programId).filter(ACTIVE_PROGRAM::equals).isPresent();
    }

    private void offerProgram(UUID owner, UUID programId, Instant at) {
        var decision = aggregates.lock(owner, AggregateType.PROGRAM_DECISION, programId);
        if (decision.exists() && !decision.deleted()) {
            return;
        }
        decisions.insert(owner, programId, new ProgramDecisionPayloadV1(ProgramDecisionPayloadV1.PENDING, at));
        var revision = aggregates.storeSynced(owner, AggregateType.PROGRAM_DECISION, programId,
                false, at, ASSIGNMENT_CLIENT, ASSIGNMENT_CLIENT_VERSION);
        feed.changed(AggregateChange.byServer(owner, AggregateType.PROGRAM_DECISION, programId, revision, false));
    }

    /**
     * Профиль у пользователя один, но его id выбирает клиент: профиль под новым id конкурирует с уже
     * сохранённым по {@code changedAt}, как правка того же агрегата. Иначе пустой профиль нового устройства
     * молча заменял заполненный.
     */
    private List<PublishedAggregateRepository.OtherAggregate> rivals(UUID ownerId, Item item) {
        if (item.deleted() || !SINGLE_PER_USER.contains(item.type())) {
            return List.of();
        }
        return aggregates.lockOtherLive(ownerId, item.type(), item.id());
    }

    /** Кто пишет, чьи данные и что. */
    private record Write(UUID callerId, UUID ownerId, Item item, boolean generated) {

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
