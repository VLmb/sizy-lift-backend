package com.sizylift.backend.sync.service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.service.AggregateChangeListener;
import com.sizylift.backend.sync.entity.FeedKind;
import com.sizylift.backend.sync.repository.SyncFeedRepository;
import org.springframework.stereotype.Component;

/**
 * Добавляет изменение агрегата в ленты получателей (ADR-0007, раздел 4). Курсоры блокируются последними
 * в транзакции и всегда в порядке {@code user_id}, поэтому записи с общими получателями не ждут друг друга
 * по кругу.
 */
@Component
class FeedFanout implements AggregateChangeListener {

    /** Порядок строкового представления совпадает с порядком uuid в PostgreSQL. */
    static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

    private final SyncFeedRepository feed;

    FeedFanout(SyncFeedRepository feed) {
        this.feed = feed;
    }

    @Override
    public void changed(UUID ownerId, String type, UUID aggregateId, long revision, boolean deleted) {
        var kind = deleted ? FeedKind.DELETE : FeedKind.UPSERT;
        for (var recipient : recipients(ownerId)) {
            feed.upsert(recipient, feed.nextSeq(recipient), ownerId, type, aggregateId, revision, kind);
        }
    }

    /** Пока только владелец; тренер с активной связью добавится вместе с AccessPolicy (этап 3). */
    private static List<UUID> recipients(UUID ownerId) {
        return List.of(ownerId).stream().sorted(LOCK_ORDER).toList();
    }
}
