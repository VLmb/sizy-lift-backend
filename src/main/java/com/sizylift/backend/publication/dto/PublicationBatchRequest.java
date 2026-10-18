package com.sizylift.backend.publication.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record PublicationBatchRequest(
        @NotNull @Size(min = 1, max = MAX_ITEMS) List<@NotNull @Valid Item> items) {

    /** Приложение шлёт пачки до 50 агрегатов; запас на будущие версии клиента. */
    public static final int MAX_ITEMS = 100;

    /**
     * Агрегат в текущем состоянии. {@code version} растёт при каждом локальном изменении;
     * у удалённого агрегата нет {@code payload}.
     */
    public record Item(
            @NotBlank String type,
            @NotNull UUID id,
            @Min(1) long version,
            @Min(1) int schemaVersion,
            boolean deleted,
            JsonNode payload) {
    }
}
