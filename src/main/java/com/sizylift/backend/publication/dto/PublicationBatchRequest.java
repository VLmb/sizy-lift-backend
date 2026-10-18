package com.sizylift.backend.publication.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record PublicationBatchRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Item> items) {

    /**
     * One aggregate in its current state. {@code version} grows with every local change;
     * {@code payload} is absent for a deleted aggregate.
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
