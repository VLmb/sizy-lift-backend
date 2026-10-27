package com.sizylift.backend.web.dto;

import java.util.UUID;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1.PlannedWorkout;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Отдельный план тренировки, без программы и без фиктивного факта выполнения. */
public final class StandaloneWorkoutDtos {
    private StandaloneWorkoutDtos() { }

    public record SaveRequest(@NotNull @Min(0) Long revision, @NotNull @Valid PlannedWorkout workout) { }
    public record Response(UUID ownerId, UUID id, long revision, PlannedWorkout workout) { }
}
