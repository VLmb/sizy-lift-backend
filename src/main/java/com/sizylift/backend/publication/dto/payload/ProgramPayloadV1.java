package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.*;

/** Программа: циклы → недели → тренировки → блоки → упражнения → подходы. */
public record ProgramPayloadV1(
        @NotBlank @Size(max = SHORT_TEXT) String name,
        @Size(max = LONG_TEXT) String description,
        LocalDate startDate,
        @NotNull @Pattern(regexp = PROGRAM_STATUS) String status,
        @NotNull Instant updatedAt,
        @NotNull @Size(max = MAX_CYCLES) List<@NotNull @Valid Cycle> cycles,
        UUID sourceProgramId) implements PayloadV1 {

    /**
     * Без {@code sourceProgramId}. Само поле — необязательная программа тренера, из которой сделана копия у
     * подопечного (CR-13, docs/coach-workspace.md, раздел 8); по нему клиент узнаёт «эта программа у него уже есть».
     */
    public ProgramPayloadV1(String name, String description, LocalDate startDate, String status, Instant updatedAt,
                            List<Cycle> cycles) {
        this(name, description, startDate, status, updatedAt, cycles, null);
    }

    public record Cycle(
            @NotNull UUID id,
            @NotBlank @Size(max = SHORT_TEXT) String name,
            @Min(0) int position,
            @NotNull @Size(max = MAX_WEEKS_PER_CYCLE) List<@NotNull @Valid Week> weeks) {
    }

    public record Week(
            @NotNull UUID id,
            @Min(1) int number,
            @Size(max = SHORT_TEXT) String name,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Min(0) int position,
            @NotNull @Size(max = MAX_WORKOUTS_PER_WEEK) List<@NotNull @Valid PlannedWorkout> workouts) {
    }

    public record PlannedWorkout(
            @NotNull UUID id,
            @Size(max = SHORT_TEXT) String title,
            @NotNull LocalDate workoutDate,
            @Min(0) int position,
            @NotNull @Size(max = MAX_BLOCKS) List<@NotNull @Valid Block> blocks) {
    }

    public record Block(
            @NotNull UUID id,
            @Min(0) int position,
            @NotNull @Pattern(regexp = BLOCK_TYPE) String blockType,
            @Pattern(regexp = GROUP_LABEL) String groupLabel,
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
            @NotNull @Pattern(regexp = RESULT_TYPE) String resultType,
            @Size(max = LONG_TEXT) String planComment,
            @NotNull @Size(max = MAX_SETS_PER_EXERCISE) List<@NotNull @Valid Set> sets,
            UUID techniqueVideoId) {

        /**
         * Без {@code techniqueVideoId}. Само поле — необязательное видео техники тренера к упражнению
         * (docs/video.md); сервер снимает его, когда тренер удаляет ролик.
         */
        public Exercise(UUID id, int position, UUID exerciseId, String exerciseName, String resultType,
                        String planComment, List<Set> sets) {
            this(id, position, exerciseId, exerciseName, resultType, planComment, sets, null);
        }
    }

    /**
     * {@code targetPercentOneRmTenths} — % от ПМ в десятых ({@code 725} = 72,5 %); вес по нему сервер не считает.
     * {@code targetRpeAuto} — RPE посчитан клиентом из веса, повторов и e1RM (ADR-0011); без поля — {@code false}.
     */
    public record Set(
            @NotNull UUID id,
            @Min(0) int position,
            @NotNull @Pattern(regexp = SET_TYPE) String setType,
            @Min(0) Integer targetWeightGrams,
            @Min(0) Integer repetitions,
            @Min(0) Integer durationMs,
            @Min(0) Integer targetDistanceM,
            @Min(RPE_TENTHS_MIN) @Max(RPE_TENTHS_MAX) Integer targetRpeTenths,
            boolean targetRpeAuto,
            @Min(PERCENT_ONE_RM_TENTHS_MIN) @Max(PERCENT_ONE_RM_TENTHS_MAX) Integer targetPercentOneRmTenths,
            @Min(0) Integer restMs,
            @Size(max = LONG_TEXT) String note,
            @Pattern(regexp = "(weight|percent|rpe):(weight|percent|rpe)?(,(weight|percent|rpe))*")
            @Size(max = 64) String targetCalculation,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Min(1) @Max(1000) Integer repetitionsMax) {
        public Set(UUID id, int position, String setType, Integer targetWeightGrams, Integer repetitions,
                   Integer durationMs, Integer targetDistanceM, Integer targetRpeTenths, boolean targetRpeAuto,
                   Integer targetPercentOneRmTenths, Integer restMs, String note, String targetCalculation) {
            this(id, position, setType, targetWeightGrams, repetitions, durationMs, targetDistanceM,
                    targetRpeTenths, targetRpeAuto, targetPercentOneRmTenths, restMs, note, targetCalculation, null);
        }

        @JsonIgnore
        @AssertTrue(message = "The upper repetition bound must exceed the positive lower bound.")
        public boolean isRepetitionRangeValid() {
            return repetitionsMax == null || repetitions != null && repetitions > 0 && repetitions < repetitionsMax;
        }

        public Set(UUID id, int position, String setType, Integer targetWeightGrams, Integer repetitions,
                   Integer durationMs, Integer targetDistanceM, Integer targetRpeTenths, boolean targetRpeAuto,
                   Integer targetPercentOneRmTenths, Integer restMs, String note) {
            this(id, position, setType, targetWeightGrams, repetitions, durationMs, targetDistanceM,
                    targetRpeTenths, targetRpeAuto, targetPercentOneRmTenths, restMs, note, null);
        }
    }
}
