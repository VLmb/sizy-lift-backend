package com.sizylift.backend.video.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Запись о ролике. {@code title} — название видео техники; {@code exerciseId} и {@code exerciseName} —
 * упражнение, к которому ролик снят (название — как его видел владелец при загрузке).
 */
public record Video(
        UUID id,
        UUID ownerId,
        VideoKind kind,
        VideoStatus status,
        long sizeBytes,
        long previewSizeBytes,
        int durationMs,
        String title,
        UUID exerciseId,
        String exerciseName,
        Instant createdAt,
        Instant uploadedAt) {

    private static final String KEY_FORMAT = "video/%s/%s.%s";
    private static final String VIDEO_EXTENSION = "mp4";
    private static final String PREVIEW_EXTENSION = "jpg";

    public boolean ready() {
        return status == VideoStatus.READY;
    }

    public String videoKey() {
        return videoKey(ownerId, id);
    }

    public String previewKey() {
        return previewKey(ownerId, id);
    }

    public long storedBytes() {
        return sizeBytes + previewSizeBytes;
    }

    public static String videoKey(UUID ownerId, UUID id) {
        return KEY_FORMAT.formatted(ownerId, id, VIDEO_EXTENSION);
    }

    public static String previewKey(UUID ownerId, UUID id) {
        return KEY_FORMAT.formatted(ownerId, id, PREVIEW_EXTENSION);
    }
}
