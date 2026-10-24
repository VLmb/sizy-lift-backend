package com.sizylift.backend.sync.service;

import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.service.AggregateChangeListener;
import com.sizylift.backend.sync.entity.FeedKind;
import com.sizylift.backend.sync.repository.SyncFeedRepository;
import org.springframework.stereotype.Component;

/**
 * Добавляет изменение агрегата в ленты получателей (ADR-0007, раздел 4): владельцу и тренеру, если тот видит
 * этот тип. Курсоры блокируются последними в транзакции и всегда в порядке {@code user_id}, поэтому записи
 * с общими получателями не ждут друг друга по кругу.
 */
@Component
class FeedFanout implements AggregateChangeListener {

    private final SyncFeedRepository feed;
    private final AccessPolicy access;

    FeedFanout(SyncFeedRepository feed, AccessPolicy access) {
        this.feed = feed;
        this.access = access;
    }

    @Override
    public void beforeWrite(UUID ownerId) {
        access.lockOwner(ownerId);
    }

    @Override
    public void changed(UUID ownerId, String type, UUID aggregateId, long revision, boolean deleted) {
        var kind = deleted ? FeedKind.DELETE : FeedKind.UPSERT;
        for (var recipient : access.recipients(ownerId, type)) {
            feed.upsert(recipient, feed.nextSeq(recipient), ownerId, type, aggregateId, revision, kind);
        }
    }
}
