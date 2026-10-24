package com.sizylift.backend.sync.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record SyncPushRequest(
        @NotNull @Size(min = 1, max = MAX_ITEMS) List<@NotNull @Valid Item> items) {

    /** Как в публикации v1. */
    public static final int MAX_ITEMS = 100;
    public static final int MAX_CLIENT_ID_LENGTH = 100;

    /**
     * Правка агрегата. {@code baseRevision} — ревизия, от которой клиент правил ({@code 0} для нового);
     * {@code changedAt} — время последней локальной правки по часам клиента; пара
     * {@code clientId}/{@code clientVersion} делает повтор идемпотентным. {@code ownerId} задаётся только
     * при записи чужих данных (тренер), иначе пишет владелец.
     */
    public record Item(
            @NotBlank String type,
            @NotNull UUID id,
            UUID ownerId,
            @Min(0) long baseRevision,
            @NotNull Instant changedAt,
            @NotBlank @Size(max = MAX_CLIENT_ID_LENGTH) String clientId,
            @Min(1) long clientVersion,
            @Min(1) int schemaVersion,
            boolean deleted,
            JsonNode payload) {
    }
}
