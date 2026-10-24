package com.sizylift.backend.publication.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sizylift.backend.publication.dto.ItemStatus;
import com.sizylift.backend.publication.dto.PublicationBatchRequest.Item;
import com.sizylift.backend.publication.dto.PublicationBatchResponse;
import com.sizylift.backend.publication.dto.PublicationBatchResponse.ItemResult;
import com.sizylift.backend.publication.dto.RejectionReason;
import com.sizylift.backend.publication.dto.payload.PayloadV1;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Принимает пачки агрегатов с телефона. Каждый агрегат сохраняется в своей транзакции, поэтому ошибка
 * в одном не влияет на остальные. Версия не новее сохранённой ничего не меняет — повторная доставка
 * и доставка не по порядку безопасны. Контракт v1 сохраняется; рядом с sync v2 действует правило по
 * {@code updatedAt} (ADR-0007, раздел 3).
 */
@Service
public class PublicationService {

    static final int SCHEMA_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(PublicationService.class);

    private final Map<String, AggregatePublisher<?>> publishers;
    private final PublishedAggregateRepository aggregates;
    private final TransactionTemplate transactions;
    private final PayloadParser parser;
    private final AggregateArbiter arbiter;
    private final AggregateChangeListener changes;
    private final PublicationMetrics metrics;

    PublicationService(List<AggregatePublisher<?>> publishers, PublishedAggregateRepository aggregates,
                       TransactionTemplate transactions, PayloadParser parser, AggregateArbiter arbiter,
                       AggregateChangeListener changes, PublicationMetrics metrics) {
        this.publishers = publishers.stream()
                .collect(Collectors.toUnmodifiableMap(AggregatePublisher::type, Function.identity()));
        this.aggregates = aggregates;
        this.transactions = transactions;
        this.parser = parser;
        this.arbiter = arbiter;
        this.changes = changes;
        this.metrics = metrics;
    }

    public PublicationBatchResponse publish(UUID userId, List<Item> items) {
        return metrics.observeBatch(items.size(), () -> publishBatch(userId, items));
    }

    private PublicationBatchResponse publishBatch(UUID userId, List<Item> items) {
        var results = items.stream().map(item -> publish(userId, item)).toList();
        results.forEach(metrics::count);
        if (log.isDebugEnabled()) {
            log.debug("Publication batch: {}", results.stream()
                    .collect(Collectors.groupingBy(ItemResult::status, Collectors.counting())));
        }
        return new PublicationBatchResponse(results);
    }

    private ItemResult publish(UUID userId, Item item) {
        var publisher = publishers.get(item.type());
        if (publisher == null) {
            return ItemResult.rejected(item, RejectionReason.UNSUPPORTED_TYPE);
        }
        if (item.schemaVersion() != SCHEMA_VERSION) {
            return ItemResult.rejected(item, RejectionReason.UNSUPPORTED_SCHEMA);
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
        return storeIfNewer(userId, item, payload.updatedAt(), false,
                () -> publisher.replace(userId, item.id(), payload));
    }

    /** Tombstone хранит версию, чтобы опоздавшая старая копия не восстановила удалённый агрегат. */
    private ItemResult delete(UUID userId, AggregatePublisher<?> publisher, Item item) {
        return storeIfNewer(userId, item, null, true, () -> publisher.delete(userId, item.id()));
    }

    /**
     * Версия не новее сохранённой — {@code stale}. Если агрегат последним писал sync v2 (веб, другой
     * клиент), побеждает правка с более поздним {@code updatedAt} корня; проигравшая версия идёт в журнал.
     */
    private ItemResult storeIfNewer(UUID userId, Item item, Instant updatedAt, boolean deleted, Runnable write) {
        return transactions.execute(status -> {
            var state = aggregates.lock(userId, item.type(), item.id());
            if (item.version() <= state.version()) {
                return ItemResult.of(item, ItemStatus.STALE);
            }
            var changedAt = arbiter.effectiveChangedAt(updatedAt);
            if (state.writtenBySync() && !arbiter.incomingWins(changedAt, state)) {
                arbiter.incomingLost(userId, item.type(), item.id(), item.payload(), changedAt, null,
                        state.revision());
                return ItemResult.of(item, ItemStatus.STALE);
            }
            var lost = state.writtenBySync() ? arbiter.captureStored(userId, item.type(), item.id(), state) : null;
            write.run();
            var revision = aggregates.storeLegacy(userId, item.type(), item.id(), item.version(), deleted,
                    changedAt);
            if (lost != null) {
                arbiter.storedLost(lost, revision);
            }
            changes.changed(userId, item.type(), item.id(), revision, deleted);
            return ItemResult.of(item, ItemStatus.ACCEPTED);
        });
    }
}
