package com.sizylift.backend.sync.service;

import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.service.AggregateChange;
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
    private final ChangeNotifications notifications;

    FeedFanout(SyncFeedRepository feed, AccessPolicy access, ChangeNotifications notifications) {
        this.feed = feed;
        this.access = access;
        this.notifications = notifications;
    }

    @Override
    public void beforeWrite(UUID ownerId) {
        access.lockOwner(ownerId);
    }

    /** Ленты получателей, затем уведомления — в той же транзакции. */
    @Override
    public void changed(AggregateChange change) {
        var kind = change.deleted() ? FeedKind.DELETE : FeedKind.UPSERT;
        for (var recipient : access.recipients(change.ownerId(), change.type())) {
            feed.upsert(recipient, feed.nextSeq(recipient), change.ownerId(), change.type(), change.aggregateId(),
                    change.revision(), kind);
        }
        notifications.onChange(change);
    }
}
