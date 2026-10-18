package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.LONG_TEXT;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.RESULT_TYPE;
import static com.sizylift.backend.publication.dto.payload.PayloadValues.SHORT_TEXT;

/** User's own exercise; system exercises are not published, the server has the catalog. */
public record ExercisePayloadV1(
        @NotBlank @Size(max = SHORT_TEXT) String name,
        @Size(max = SHORT_TEXT) String shortName,
        UUID movementCategoryId,
        @NotNull @Pattern(regexp = RESULT_TYPE) String resultType,
        boolean isSbd,
        @Size(max = LONG_TEXT) String note,
        @NotNull Instant updatedAt,
        @NotNull @Size(max = 30) List<@NotNull @Valid Muscle> muscles,
        @NotNull @Size(max = 30) List<@NotNull UUID> equipmentIds) {

    public record Muscle(@NotNull UUID muscleGroupId, @NotNull @Pattern(regexp = "primary|secondary") String role) {
    }
}
