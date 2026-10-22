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

import static com.sizylift.backend.publication.dto.payload.PayloadValues.*;

/** Завершённая тренировка: блоки, упражнения, подходы, снимки плана и события рекордов. */
public record WorkoutPayloadV1(
        @NotNull LocalDate workoutDate,
        @Size(max = SHORT_TEXT) String title,
        @Size(max = LONG_TEXT) String description,
        @NotNull Instant startedAt,
        @NotNull Instant completedAt,
        @NotNull @Pattern(regexp = WORKOUT_QUALITY) String quality,
        UUID plannedWorkoutId,
        @NotNull Instant updatedAt,
        @NotNull @Size(max = MAX_BLOCKS) List<@NotNull @Valid Block> blocks,
        @NotNull @Size(max = MAX_PLAN_SNAPSHOTS) List<@NotNull @Valid PlanSetSnapshot> planSetSnapshots,
        @NotNull @Size(max = MAX_RECORD_EVENTS) List<@NotNull @Valid RecordEvent> recordEvents) implements PayloadV1 {

    public record Block(
            @NotNull UUID id,
            @Min(0) int position,
            @NotNull @Pattern(regexp = BLOCK_TYPE) String blockType,
            @Pattern(regexp = GROUP_LABEL) String groupLabel,
            @Min(1) Integer plannedRounds,
            @Min(1) Integer currentRound,
            @Min(0) Integer completedRounds,
            @Min(1) Integer intervalWorkMs,
            @Min(0) Integer intervalRestMs,
            @Pattern(regexp = FREE_KIND) String freeKind,
            @Size(max = SHORT_TEXT) String title,
            @Size(max = LONG_TEXT) String description,
            @NotNull @Size(max = MAX_EXERCISES_PER_BLOCK) List<@NotNull @Valid Exercise> exercises) {
    }

    public record Exercise(
            @NotNull UUID id,
            @Min(0) int position,
            @NotNull UUID exerciseId,
            @NotBlank @Size(max = SHORT_TEXT) String exerciseName,
            @Size(max = SHORT_TEXT) String exerciseShortName,
            @NotNull @Pattern(regexp = RESULT_TYPE) String resultType,
            @NotNull UUID originalExerciseId,
            @NotBlank @Size(max = SHORT_TEXT) String originalExerciseName,
            @Size(max = LONG_TEXT) String actualComment,
            @Size(max = LONG_TEXT) String planComment,
            @NotNull @Size(max = MAX_SETS_PER_EXERCISE) List<@NotNull @Valid Set> sets) {
    }

    public record Set(
            @NotNull UUID id,
            @Min(0) int position,
            @NotNull @Pattern(regexp = SET_TYPE) String setType,
            @NotNull @Pattern(regexp = SET_COMPLETION) String completionStatus,
            @Min(0) Integer weightGrams,
            @Min(0) Integer repetitions,
            @Min(0) Integer durationMs,
            @Min(0) Integer distanceM,
            @Min(RPE_TENTHS_MIN) @Max(RPE_TENTHS_MAX) Integer rpeTenths,
            @Min(1) Integer averageHeartRateBpm,
            @Min(1) Integer maximumHeartRateBpm,
            @Min(0) Integer powerWatts,
            @Min(0) Integer elevationGainM,
            @Size(max = LONG_TEXT) String note,
            Instant completedAt,
            UUID sourcePlannedSetId) {
    }

    public record PlanSetSnapshot(
            @NotNull UUID id,
            UUID actualSetId,
            @NotNull UUID sourcePlannedExerciseId,
            @NotNull UUID sourcePlannedSetId,
            @Min(0) int position,
            @NotNull @Pattern(regexp = RESULT_TYPE) String resultType,
            @NotNull @Pattern(regexp = SET_TYPE) String setType,
            @Min(0) Integer targetWeightGrams,
            @Min(0) Integer repetitions,
            @Min(0) Integer durationMs,
            @Min(0) Integer targetDistanceM,
            @Min(RPE_TENTHS_MIN) @Max(RPE_TENTHS_MAX) Integer targetRpeTenths,
            @Min(0) Integer restMs,
            @Size(max = LONG_TEXT) String note) {
    }

    public record RecordEvent(
            @NotNull UUID id,
            @NotNull UUID workoutExerciseId,
            @NotNull UUID actualSetId,
            @NotNull @Pattern(regexp = RECORD_TYPE) String recordType,
            @Min(0) int value,
            @Min(0) Integer previousValue,
            @NotNull Instant createdAt) {
    }
}
