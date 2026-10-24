package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.*;

/** Собственное упражнение пользователя; системные не публикуются — каталог есть на сервере. */
public record ExercisePayloadV1(
        @NotBlank @Size(max = SHORT_TEXT) String name,
        @Size(max = SHORT_TEXT) String shortName,
        UUID movementCategoryId,
        @NotNull @Pattern(regexp = RESULT_TYPE) String resultType,
        boolean isSbd,
        @Size(max = LONG_TEXT) String note,
        @NotNull Instant updatedAt,
        @NotNull @Size(max = MAX_MUSCLES) List<@NotNull @Valid Muscle> muscles,
        @NotNull @Size(max = MAX_EQUIPMENT) List<@NotNull UUID> equipmentIds) implements PayloadV1 {

    public record Muscle(@NotNull UUID muscleGroupId, @NotNull @Pattern(regexp = MUSCLE_ROLE) String role) {
    }
}
