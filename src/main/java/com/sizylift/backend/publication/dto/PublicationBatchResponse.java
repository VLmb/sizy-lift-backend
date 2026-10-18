package com.sizylift.backend.publication.dto;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.publication.dto.PublicationBatchRequest.Item;

public record PublicationBatchResponse(List<ItemResult> results) {

    /** {@code error} заполнен только у {@link ItemStatus#REJECTED}. */
    public record ItemResult(String type, UUID id, long version, ItemStatus status, RejectionReason error) {

        public static ItemResult of(Item item, ItemStatus status) {
            return new ItemResult(item.type(), item.id(), item.version(), status, null);
        }

        public static ItemResult rejected(Item item, RejectionReason reason) {
            return new ItemResult(item.type(), item.id(), item.version(), ItemStatus.REJECTED, reason);
        }
    }
}
