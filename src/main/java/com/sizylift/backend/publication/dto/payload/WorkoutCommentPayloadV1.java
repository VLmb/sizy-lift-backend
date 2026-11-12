package com.sizylift.backend.publication.dto.payload;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static com.sizylift.backend.publication.dto.payload.PayloadValues.LONG_TEXT;

/**
 * Комментарий к завершённой тренировке владельца; автор хранится в состоянии агрегата, а не в payload.
 * {@code atCompletion} — подопечный написал его на экране завершения (docs/coach-workspace.md, раздел 7):
 * такой комментарий показывается в строке «выполнил» и события {@code comment_added} не даёт. Без поля
 * или {@code false} — обычный комментарий; в ответах поле есть только со значением {@code true}.
 * <p>
 * Комментарий к упражнению (docs/coaching.md): {@code workoutExerciseId} — упражнение этой тренировки,
 * с ним необязательны {@code setNumber} (номер подхода с единицы) и {@code videoId} с длиной ролика
 * {@code videoDurationMs}. Текст может быть пустым только у комментария с видео. Без этих полей — общий
 * комментарий к тренировке, как раньше.
 */
public record WorkoutCommentPayloadV1(
        @NotNull UUID workoutId,
        @NotNull @Size(max = LONG_TEXT) String text,
        @NotNull Instant createdAt,
        @NotNull Instant updatedAt,
        Boolean atCompletion,
        UUID workoutExerciseId,
        @Min(1) Integer setNumber,
        UUID videoId,
        @Min(1) Integer videoDurationMs) implements PayloadV1 {

    public WorkoutCommentPayloadV1(UUID workoutId, String text, Instant createdAt, Instant updatedAt) {
        this(workoutId, text, createdAt, updatedAt, null, null, null, null, null);
    }

    public boolean writtenAtCompletion() {
        return Boolean.TRUE.equals(atCompletion);
    }

    @JsonIgnore
    @AssertTrue
    public boolean isTextOrVideoPresent() {
        return text == null || !text.isBlank() || videoId != null;
    }

    @JsonIgnore
    @AssertTrue
    public boolean isExerciseScopeConsistent() {
        return workoutExerciseId != null || setNumber == null && videoId == null;
    }

    @JsonIgnore
    @AssertTrue
    public boolean isVideoDurationConsistent() {
        return videoId != null || videoDurationMs == null;
    }

    /** Тот же комментарий без ролика: видео удалил владелец. */
    public WorkoutCommentPayloadV1 withoutVideo() {
        return new WorkoutCommentPayloadV1(workoutId, text, createdAt, updatedAt, atCompletion, workoutExerciseId,
                setNumber, null, null);
    }
}
