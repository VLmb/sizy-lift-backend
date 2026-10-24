package com.sizylift.backend.sync.service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.sync.entity.FeedKind;
import com.sizylift.backend.sync.repository.SyncFeedRepository;
import org.springframework.stereotype.Service;

/**
 * Изменения лент при создании и разрыве связи и смене разрешения на травмы (ADR-0007, раздел 4).
 * Вызывается в транзакции изменения связи последним шагом; операции выполняются в порядке блокировки курсоров.
 */
@Service
public class FeedMaintenance {

    private final SyncFeedRepository feed;

    FeedMaintenance(SyncFeedRepository feed) {
        this.feed = feed;
    }

    public void apply(List<Operation> operations) {
        operations.stream()
                .sorted(Comparator.comparing(Operation::recipientId, AccessPolicy.LOCK_ORDER))
                .forEach(this::apply);
    }

    private void apply(Operation operation) {
        switch (operation) {
            case Seed seed -> feed.seed(seed.recipientId(), seed.ownerId(), seed.types().toArray(String[]::new));
            case Revoke revoke -> feed.revoke(revoke.recipientId(), revoke.ownerId(),
                    revoke.types().toArray(String[]::new));
            case Upsert upsert -> feed.upsert(upsert.recipientId(), feed.nextSeq(upsert.recipientId()),
                    upsert.ownerId(), upsert.type(), upsert.aggregateId(), upsert.revision(),
                    upsert.deleted() ? FeedKind.DELETE : FeedKind.UPSERT);
        }
    }

    public sealed interface Operation permits Seed, Revoke, Upsert {
        UUID recipientId();
    }

    /** Все существующие агрегаты владельца этих типов — в ленту получателя. */
    public record Seed(UUID recipientId, UUID ownerId, Set<String> types) implements Operation {
    }

    /** Получатель теряет доступ к агрегатам владельца этих типов. */
    public record Revoke(UUID recipientId, UUID ownerId, Set<String> types) implements Operation {
    }

    /** Новая ревизия одного агрегата. */
    public record Upsert(UUID recipientId, UUID ownerId, String type, UUID aggregateId, long revision,
                         boolean deleted) implements Operation {
    }
}
