package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Фактический 1ПМ, введённый пользователем; id агрегата — id упражнения. */
public record OneRepMaxPayloadV1(@Min(1) int weightGrams, @NotNull LocalDate achievedOn, @NotNull Instant updatedAt) {
}
