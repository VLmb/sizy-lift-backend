package com.sizylift.backend.publication.dto;

import java.util.List;
import java.util.UUID;

public record PublicationBatchResponse(List<ItemResult> results) {

    /**
     * {@code accepted} and {@code stale} mean the version is stored (or a newer one is);
     * {@code rejected} carries an error code and is worth retrying only after an app update.
     */
    public record ItemResult(String type, UUID id, long version, String status, String error) {

        public static ItemResult of(String type, UUID id, long version, String status) {
            return new ItemResult(type, id, version, status, null);
        }

        public static ItemResult rejected(String type, UUID id, long version, String error) {
            return new ItemResult(type, id, version, "rejected", error);
        }
    }
}
