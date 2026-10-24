package com.sizylift.backend.sync.service;

import java.util.ArrayList;
import java.util.UUID;

import com.sizylift.backend.publication.service.PayloadReader;
import com.sizylift.backend.sync.dto.SyncChangesResponse;
import com.sizylift.backend.sync.dto.SyncChangesResponse.Change;
import com.sizylift.backend.sync.entity.FeedEntry;
import com.sizylift.backend.sync.entity.FeedKind;
import com.sizylift.backend.sync.repository.SyncFeedRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Чтение персональной ленты изменений с актуальным payload агрегатов (ADR-0007, раздел 4). */
@Service
public class SyncChangesService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;
    /** Ограничение страницы по объёму payload; первая запись отдаётся всегда, даже если больше. */
    static final int MAX_PAGE_BYTES = 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(SyncChangesService.class);

    private final SyncFeedRepository feed;
    private final PayloadReader payloads;
    private final ObjectMapper json;
    private final SyncMetrics metrics;
    private final TransactionTemplate snapshot;

    SyncChangesService(SyncFeedRepository feed, PayloadReader payloads, ObjectMapper json, SyncMetrics metrics,
                       PlatformTransactionManager transactionManager) {
        this.feed = feed;
        this.payloads = payloads;
        this.json = json;
        this.metrics = metrics;
        this.snapshot = new TransactionTemplate(transactionManager);
        snapshot.setReadOnly(true);
        // Лента и payload читаются из одного снимка: ревизия записи ленты совпадает с отданными данными.
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public SyncChangesResponse changes(UUID userId, long after, int limit) {
        var response = snapshot.execute(status -> page(userId, after, limit));
        metrics.recordPage(response.changes().size());
        return response;
    }

    private SyncChangesResponse page(UUID userId, long after, int limit) {
        var entries = feed.page(userId, after, limit + 1);
        var changes = new ArrayList<Change>();
        var bytes = 0L;
        var cursor = after;
        for (var entry : entries) {
            if (changes.size() == limit) {
                break;
            }
            var change = change(entry);
            bytes += change.payload() == null ? 0 : json.writeValueAsBytes(change.payload()).length;
            if (!changes.isEmpty() && bytes > MAX_PAGE_BYTES) {
                break;
            }
            changes.add(change);
            cursor = entry.seq();
        }
        return new SyncChangesResponse(changes, cursor, changes.size() < entries.size());
    }

    private Change change(FeedEntry entry) {
        if (entry.kind() != FeedKind.UPSERT) {
            return change(entry, entry.kind(), null);
        }
        var payload = payloads.read(entry.ownerId(), entry.type(), entry.aggregateId());
        if (payload.isEmpty()) {
            // В одном снимке так быть не должно: строка ленты и данные пишутся в одной транзакции.
            log.warn("Feed entry without aggregate data, type {}", entry.type());
            return change(entry, FeedKind.DELETE, null);
        }
        return change(entry, FeedKind.UPSERT, payload.get());
    }

    private Change change(FeedEntry entry, FeedKind kind, Object payload) {
        return new Change(entry.seq(), entry.ownerId(), entry.type(), entry.aggregateId(), entry.revision(), kind,
                entry.changedAt(), payload == null ? null : SyncPushService.SCHEMA_VERSION,
                payload == null ? null : json.valueToTree(payload));
    }
}
