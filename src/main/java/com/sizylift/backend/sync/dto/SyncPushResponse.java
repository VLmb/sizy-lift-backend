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
     * {@code payloadRewritten} — сервер сохранил payload не таким, как прислан (ссылки программы тренера
     * переписаны на копии упражнений у подопечного, перенос на исходную дату сохранён как удаление); клиент берёт
     * агрегат из ленты, даже при той же ревизии.
     */
    public record ItemResult(String type, UUID id, long clientVersion, ItemStatus status, Long revision,
                             RejectionReason error, boolean payloadRewritten) {

        public static ItemResult of(Item item, ItemStatus status, long revision) {
            return new ItemResult(item.type(), item.id(), item.clientVersion(), status, revision, null, false);
        }

        public static ItemResult accepted(Item item, long revision, boolean payloadRewritten) {
            return new ItemResult(item.type(), item.id(), item.clientVersion(), ItemStatus.ACCEPTED, revision, null,
                    payloadRewritten);
        }

        public static ItemResult rejected(Item item, RejectionReason reason) {
            return new ItemResult(item.type(), item.id(), item.clientVersion(), ItemStatus.REJECTED, null, reason,
                    false);
        }
    }
}
