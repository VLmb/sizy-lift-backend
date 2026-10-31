package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.PlannedWorkout;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Разовая тренировка без программы (ADR-0011): та же плановая тренировка, что в программе. Id агрегата
 * совпадает с {@code workout.id}.
 */
public record StandaloneWorkoutPayloadV1(
        @NotNull @Valid PlannedWorkout workout,
        @NotNull Instant updatedAt) implements PayloadV1 {
}
