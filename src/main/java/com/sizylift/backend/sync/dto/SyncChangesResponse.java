package com.sizylift.backend.sync.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.sync.entity.FeedKind;
import tools.jackson.databind.JsonNode;

/**
 * Страница ленты по возрастанию {@code seq}. {@code nextCursor} передаётся как {@code after} следующего
 * запроса; {@code hasMore} — в ленте есть записи за пределами страницы.
 */
public record SyncChangesResponse(List<Change> changes, long nextCursor, boolean hasMore) {

    /** {@code payload} и {@code schemaVersion} есть только у {@code upsert}. */
    public record Change(long seq, UUID ownerId, String type, UUID id, long revision, FeedKind kind,
                         Instant changedAt, Integer schemaVersion, JsonNode payload) {
    }
}
