package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Actual one-rep max entered by the user; the aggregate id is the exercise id. */
public record OneRepMaxPayloadV1(@Min(1) int weightGrams, @NotNull LocalDate achievedOn, @NotNull Instant updatedAt) {
}
