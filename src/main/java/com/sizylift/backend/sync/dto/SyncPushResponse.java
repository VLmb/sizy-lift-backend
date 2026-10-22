package com.sizylift.backend.sync.dto;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.dto.ItemStatus;
import com.sizylift.backend.publication.dto.RejectionReason;
import com.sizylift.backend.sync.dto.SyncPushRequest.Item;

public record SyncPushResponse(List<ItemResult> results) {

    /**
     * {@code revision} — ревизия агрегата после обработки (у {@code superseded} и {@code stale} текущая
     * серверная); {@code error} заполнен только у {@code rejected}, у него же нет ревизии.
     */
    public record ItemResult(String type, UUID id, long clientVersion, ItemStatus status, Long revision,
                             RejectionReason error) {

        public static ItemResult of(Item item, ItemStatus status, long revision) {
            return new ItemResult(item.type(), item.id(), item.clientVersion(), status, revision, null);
        }

        public static ItemResult rejected(Item item, RejectionReason reason) {
            return new ItemResult(item.type(), item.id(), item.clientVersion(), ItemStatus.REJECTED, null, reason);
        }
    }
}
