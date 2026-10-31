package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Перенос плановой тренировки подопечным; id агрегата — id плановой тренировки. Программу не меняет.
 * {@code programId} пуст у разовой тренировки (ADR-0011).
 */
public record WorkoutReschedulePayloadV1(
        UUID programId,
        @NotNull LocalDate scheduledDate,
        @NotNull Instant updatedAt) implements PayloadV1 {
}
