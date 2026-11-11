package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.LONG_TEXT;

/**
 * Комментарий к завершённой тренировке владельца; автор хранится в состоянии агрегата, а не в payload.
 * {@code atCompletion} — подопечный написал его на экране завершения (docs/coach-workspace.md, раздел 7):
 * такой комментарий показывается в строке «выполнил» и события {@code comment_added} не даёт. Без поля
 * или {@code false} — обычный комментарий; в ответах поле есть только со значением {@code true}.
 */
public record WorkoutCommentPayloadV1(
        @NotNull UUID workoutId,
        @NotBlank @Size(max = LONG_TEXT) String text,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt,
        Boolean atCompletion) implements PayloadV1 {

    public WorkoutCommentPayloadV1(UUID workoutId, String text, Instant createdAt, Instant updatedAt) {
        this(workoutId, text, createdAt, updatedAt, null);
    }

    public boolean writtenAtCompletion() {
        return Boolean.TRUE.equals(atCompletion);
    }
}
