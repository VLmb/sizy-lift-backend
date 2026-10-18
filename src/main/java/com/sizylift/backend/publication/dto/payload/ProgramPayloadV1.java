package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.BLOCK_TYPE;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.FREE_KIND;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.GROUP_LABEL;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.LONG_TEXT;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.RESULT_TYPE;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.SET_TYPE;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.SHORT_TEXT;

/** Training program: cycles → weeks → planned workouts → blocks → exercises → sets. */
public record ProgramPayloadV1(
        @NotBlank @Size(max = SHORT_TEXT) String name,
        @Size(max = LONG_TEXT) String description,
        LocalDate startDate,
        @NotNull @Pattern(regexp = "draft|active") String status,
        @NotNull Instant updatedAt,
        @NotNull @Size(max = 50) List<@NotNull @Valid Cycle> cycles) {

    public record Cycle(
            @NotNull UUID id,
            @NotBlank @Size(max = SHORT_TEXT) String name,
            @Min(0) int position,
            @NotNull @Size(max = 104) List<@NotNull @Valid Week> weeks) {
    }

    public record Week(
            @NotNull UUID id,
            @Min(1) int number,
            @Size(max = SHORT_TEXT) String name,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Min(0) int position,
            @NotNull @Size(max = 14) List<@NotNull @Valid PlannedWorkout> workouts) {
    }

    public record PlannedWorkout(
            @NotNull UUID id,
            @Size(max = SHORT_TEXT) String title,
            @NotNull LocalDate workoutDate,
            @Min(0) int position,
            @NotNull @Size(max = 100) List<@NotNull @Valid Block> blocks) {
    }

    public record Block(
            @NotNull UUID id,
            @Min(0) int position,
            @NotNull @Pattern(regexp = BLOCK_TYPE) String blockType,
            @Pattern(regexp = GROUP_LABEL) String groupLabel,
            @Pattern(regexp = FREE_KIND) String freeKind,
            @Size(max = SHORT_TEXT) String title,
            @Size(max = LONG_TEXT) String description,
            @NotNull @Size(max = 50) List<@NotNull @Valid Exercise> exercises) {
    }

    public record Exercise(
            @NotNull UUID id,
            @Min(0) int position,
            @NotNull UUID exerciseId,
            @NotBlank @Size(max = SHORT_TEXT) String exerciseName,
            @NotNull @Pattern(regexp = RESULT_TYPE) String resultType,
            @Size(max = LONG_TEXT) String planComment,
            @NotNull @Size(max = 200) List<@NotNull @Valid Set> sets) {
    }

    public record Set(
            @NotNull UUID id,
            @Min(0) int position,
            @NotNull @Pattern(regexp = SET_TYPE) String setType,
            @Min(0) Integer targetWeightGrams,
            @Min(0) Integer repetitions,
            @Min(0) Integer durationMs,
            @Min(0) Integer targetDistanceM,
            @Min(50) @Max(100) Integer targetRpeTenths,
            @Min(0) Integer restMs,
            @Size(max = LONG_TEXT) String note) {
    }
}
