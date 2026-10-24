package com.sizylift.backend.sync.entity;

import java.time.Instant;
import java.util.UUID;

/** Строка ленты получателя; {@code changedAt} — время последней правки агрегата. */
public record FeedEntry(long seq, UUID ownerId, String type, UUID aggregateId, long revision, FeedKind kind,
                        Instant changedAt) {
}
