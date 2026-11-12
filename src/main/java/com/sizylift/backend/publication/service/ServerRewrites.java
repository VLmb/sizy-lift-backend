package com.sizylift.backend.publication.service;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Правка агрегатов самим сервером вне записи клиента (удалённое видео снимается с комментариев и программ):
 * новая ревизия и ленты, как при каскадах {@link DependentAggregates}, без уведомлений о действии пользователя.
 * Порядок блокировок тот же, что при записи: аккаунты владельцев, строки агрегатов, курсоры лент.
 */
@Component
public class ServerRewrites {

    private final PublishedAggregateRepository aggregates;
    private final AggregateArbiter arbiter;
    private final AggregateChangeListener feed;

    ServerRewrites(PublishedAggregateRepository aggregates, AggregateArbiter arbiter, AggregateChangeListener feed) {
        this.aggregates = aggregates;
        this.arbiter = arbiter;
        this.feed = feed;
    }

    /** Блокирует владельцев и выполняет правки; изменения расходятся по лентам после всех правок. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rewrite(Collection<UUID> ownerIds, Runnable changes) {
        ownerIds.stream().distinct().sorted(AccessPolicy.LOCK_ORDER).forEach(feed::beforeWrite);
        feed.deferChanges(() -> {
            changes.run();
            return null;
        });
    }

    /**
     * Меняет данные существующего агрегата. {@code change} возвращает {@code true}, если агрегат после правки
     * удалён. Удалённые и неизвестные агрегаты не трогаются.
     */
    public void apply(UUID ownerId, String type, UUID aggregateId, Supplier<Boolean> change) {
        var state = aggregates.lock(ownerId, type, aggregateId);
        if (!state.exists() || state.deleted()) {
            return;
        }
        boolean deleted = change.get();
        var revision = aggregates.storeSynced(ownerId, type, aggregateId, deleted, arbiter.effectiveChangedAt(null),
                DependentAggregates.SERVER_CLIENT_ID, DependentAggregates.SERVER_CLIENT_VERSION);
        feed.changed(AggregateChange.byServer(ownerId, type, aggregateId, revision, deleted));
    }
}
