package com.sizylift.backend.sync.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sizylift.backend.publication.dto.ItemStatus;
import com.sizylift.backend.publication.dto.RejectionReason;
import com.sizylift.backend.publication.dto.payload.PayloadV1;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.service.AggregateArbiter;
import com.sizylift.backend.publication.service.AggregatePublisher;
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
    private final SyncMetrics metrics;

    SyncPushService(List<AggregatePublisher<?>> publishers, PublishedAggregateRepository aggregates,
                    TransactionTemplate transactions, PayloadParser parser, AggregateArbiter arbiter,
                    SyncMetrics metrics) {
        this.publishers = publishers.stream()
                .collect(Collectors.toUnmodifiableMap(AggregatePublisher::type, Function.identity()));
        this.aggregates = aggregates;
        this.transactions = transactions;
        this.parser = parser;
        this.arbiter = arbiter;
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

    private ItemResult push(UUID userId, Item item) {
        var publisher = publishers.get(item.type());
        if (publisher == null) {
            return ItemResult.rejected(item, RejectionReason.UNSUPPORTED_TYPE);
        }
        if (item.schemaVersion() != SCHEMA_VERSION) {
            return ItemResult.rejected(item, RejectionReason.UNSUPPORTED_SCHEMA);
        }
        if (item.ownerId() != null && !item.ownerId().equals(userId)) {
            return ItemResult.rejected(item, RejectionReason.FORBIDDEN);
        }
        try {
            return item.deleted() ? delete(userId, publisher, item) : replace(userId, publisher, item);
        } catch (PayloadParser.InvalidPayloadException | DataIntegrityViolationException e) {
            log.debug("Rejected {} {}: {}", item.type(), item.id(), e.getClass().getSimpleName());
            return ItemResult.rejected(item, RejectionReason.INVALID_PAYLOAD);
        } catch (IdConflictException e) {
            return ItemResult.rejected(item, RejectionReason.ID_CONFLICT);
        }
    }

    private <P extends PayloadV1> ItemResult replace(UUID userId, AggregatePublisher<P> publisher, Item item) {
        var payload = parser.parse(item.payload(), publisher.payloadType());
        return store(userId, item, () -> publisher.replace(userId, item.id(), payload));
    }

    /** Удаление — та же запись с tombstone: конкурирует с правкой по {@code changedAt}. */
    private ItemResult delete(UUID userId, AggregatePublisher<?> publisher, Item item) {
        return store(userId, item, () -> publisher.delete(userId, item.id()));
    }

    private ItemResult store(UUID userId, Item item, Runnable write) {
        return transactions.execute(status -> {
            var state = aggregates.lock(userId, item.type(), item.id());
            if (isRepeat(state.sourceClientId(), state.sourceVersion(), item)) {
                return ItemResult.of(item, ItemStatus.STALE, state.revision());
            }
            var changedAt = arbiter.effectiveChangedAt(item.changedAt());
            var conflict = item.baseRevision() < state.revision();
            if (conflict && !arbiter.incomingWins(changedAt, state)) {
                arbiter.incomingLost(userId, item.type(), item.id(), item.payload(), changedAt, item.clientId(),
                        state.revision());
                return ItemResult.of(item, ItemStatus.SUPERSEDED, state.revision());
            }
            write.run();
            var revision = aggregates.storeSynced(userId, item.type(), item.id(), item.deleted(), changedAt,
                    item.clientId(), item.clientVersion());
            if (conflict) {
                arbiter.storedLost(userId, item.type(), item.id(), state, revision);
            }
            return ItemResult.of(item, ItemStatus.ACCEPTED, revision);
        });
    }

    /** Повторная доставка уже применённой правки того же клиента. */
    private static boolean isRepeat(String sourceClientId, Long sourceVersion, Item item) {
        return item.clientId().equals(sourceClientId) && sourceVersion != null
                && item.clientVersion() <= sourceVersion;
    }
}
