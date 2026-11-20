package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;

/**
 * Перенос плановой тренировки подопечным; id агрегата — id плановой тренировки. Программу не меняет.
 * {@code programId} пуст у разовой тренировки (ADR-0011). {@code skipped} — подопечный отметил пропуск
 * (PWA-09): план остаётся, дата — текущая; без поля или {@code false} — пропуска нет.
 */
public record WorkoutReschedulePayloadV1(
        UUID programId,
        @NotNull LocalDate scheduledDate,
        @NotNull Instant updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean skipped) implements PayloadV1 {

    public WorkoutReschedulePayloadV1 {
        skipped = Boolean.TRUE.equals(skipped) ? Boolean.TRUE : null;
    }

    /** Перенос без отметки пропуска. */
    public WorkoutReschedulePayloadV1(UUID programId, LocalDate scheduledDate, Instant updatedAt) {
        this(programId, scheduledDate, updatedAt, null);
    }

    @JsonIgnore
    public boolean isSkip() {
        return Boolean.TRUE.equals(skipped);
    }
}
