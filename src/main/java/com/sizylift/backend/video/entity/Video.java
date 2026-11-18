package com.sizylift.backend.video.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Запись о ролике. {@code title} — название видео техники; {@code exerciseId} и {@code exerciseName} —
 * упражнение, к которому ролик снят (название — как его видел владелец при загрузке).
 * {@code sourceContentType} есть у ролика из браузера: исходник лежит как есть, пока сервер его не перекодирует;
 * {@code sourceSizeBytes} — размер исходника до перекодирования. До готовности {@code sizeBytes} и
 * {@code previewSizeBytes} такого ролика — места, отложенные под результат в квоте.
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
        Instant uploadedAt,
        String sourceContentType,
        Long sourceSizeBytes) {

    private static final String KEY_FORMAT = "video/%s/%s.%s";
    private static final String VIDEO_EXTENSION = "mp4";
    private static final String PREVIEW_EXTENSION = "jpg";
    private static final String SOURCE_EXTENSION = "source";

    public boolean ready() {
        return status == VideoStatus.READY;
    }

    /** Загружен из браузера: перекодирует сервер. */
    public boolean fromBrowser() {
        return sourceContentType != null;
    }

    public String sourceKey() {
        return sourceKey(ownerId, id);
    }

    public String videoKey() {
        return videoKey(ownerId, id);
    }

    public String previewKey() {
        return previewKey(ownerId, id);
    }

    /** Сколько ролик занимает в хранилище сейчас, с исходником до перекодирования. */
    public long storedBytes() {
        return sizeBytes + previewSizeBytes + (sourceSizeBytes == null ? 0 : sourceSizeBytes);
    }

    public static String videoKey(UUID ownerId, UUID id) {
        return KEY_FORMAT.formatted(ownerId, id, VIDEO_EXTENSION);
    }

    public static String previewKey(UUID ownerId, UUID id) {
        return KEY_FORMAT.formatted(ownerId, id, PREVIEW_EXTENSION);
    }

    public static String sourceKey(UUID ownerId, UUID id) {
        return KEY_FORMAT.formatted(ownerId, id, SOURCE_EXTENSION);
    }
}
