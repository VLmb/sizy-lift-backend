package com.sizylift.backend.web.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Назначить программу нескольким подопечным (CR-13, docs/coach-workspace.md, раздел 8). */
public final class AssignDtos {

    public static final int MAX_ATHLETES = 50;

    private AssignDtos() {
    }

    /**
     * Копии готовит клиент: % от ПМ пересчитан в вес по ПМ каждого подопечного, даты сдвинуты к выбранному
     * началу. {@code id} — новый id программы у подопечного, {@code program} — payload v1 со статусом {@code draft}.
     */
    public record AssignRequest(@NotEmpty @Size(max = MAX_ATHLETES) List<@NotNull @Valid Copy> copies) {
    }

    public record Copy(@NotNull UUID athleteId, @NotNull UUID id, @NotNull JsonNode program) {
    }

    public record AssignResponse(List<Created> created) {
    }

    public record Created(UUID athleteId, UUID id, long revision) {
    }
}
