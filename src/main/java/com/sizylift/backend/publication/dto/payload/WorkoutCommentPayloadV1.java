package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.LONG_TEXT;

/** Комментарий к завершённой тренировке владельца; автор хранится в состоянии агрегата, а не в payload. */
public record WorkoutCommentPayloadV1(
        @NotNull UUID workoutId,
        @NotBlank @Size(max = LONG_TEXT) String text,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt) implements PayloadV1 {
}
