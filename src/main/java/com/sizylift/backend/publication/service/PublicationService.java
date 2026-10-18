package com.sizylift.backend.publication.service;

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
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Принимает пачки агрегатов с телефона. Каждый агрегат сохраняется в своей транзакции, поэтому ошибка
 * в одном не влияет на остальные. Версия не новее сохранённой ничего не меняет — повторная доставка
 * и доставка не по порядку безопасны.
 */
@Service
public class PublicationService {

    static final int SCHEMA_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(PublicationService.class);

    private final Map<String, AggregatePublisher<?>> publishers;
    private final PublishedAggregateRepository versions;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Validator validator;
    private final PublicationMetrics metrics;

    PublicationService(List<AggregatePublisher<?>> publishers, PublishedAggregateRepository versions,
                       TransactionTemplate transactions, ObjectMapper json, Validator validator,
                       PublicationMetrics metrics) {
        this.publishers = publishers.stream()
                .collect(Collectors.toUnmodifiableMap(AggregatePublisher::type, Function.identity()));
        this.versions = versions;
        this.transactions = transactions;
        this.json = json;
        this.validator = validator;
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
        } catch (InvalidPayloadException | DataIntegrityViolationException e) {
            log.debug("Rejected {} {}: {}", item.type(), item.id(), e.getClass().getSimpleName());
            return ItemResult.rejected(item, RejectionReason.INVALID_PAYLOAD);
        } catch (IdConflictException e) {
            return ItemResult.rejected(item, RejectionReason.ID_CONFLICT);
        }
    }

    private <P> ItemResult replace(UUID userId, AggregatePublisher<P> publisher, Item item) {
        var payload = parse(item.payload(), publisher.payloadType());
        return storeIfNewer(userId, item, false, () -> {
            publisher.delete(userId, item.id());
            publisher.insert(userId, item.id(), payload);
        });
    }

    /** Tombstone хранит версию, чтобы опоздавшая старая копия не восстановила удалённый агрегат. */
    private ItemResult delete(UUID userId, AggregatePublisher<?> publisher, Item item) {
        return storeIfNewer(userId, item, true, () -> publisher.delete(userId, item.id()));
    }

    private ItemResult storeIfNewer(UUID userId, Item item, boolean deleted, Runnable write) {
        return transactions.execute(status -> {
            if (item.version() <= versions.lockVersion(userId, item.type(), item.id())) {
                return ItemResult.of(item, ItemStatus.STALE);
            }
            write.run();
            versions.store(userId, item.type(), item.id(), item.version(), deleted);
            return ItemResult.of(item, ItemStatus.ACCEPTED);
        });
    }

    private <P> P parse(JsonNode payload, Class<P> type) {
        if (payload == null || !payload.isObject()) {
            throw new InvalidPayloadException();
        }
        P value;
        try {
            value = json.treeToValue(payload, type);
        } catch (JacksonException e) {
            throw new InvalidPayloadException();
        }
        if (!validator.validate(value).isEmpty()) {
            throw new InvalidPayloadException();
        }
        return value;
    }

    static class InvalidPayloadException extends RuntimeException {
    }
}
