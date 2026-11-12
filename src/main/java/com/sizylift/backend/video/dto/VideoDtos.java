package com.sizylift.backend.video.dto;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Контракт видео (docs/video.md). */
public final class VideoDtos {

    private VideoDtos() {
    }

    /** {@code id} придумывает приложение; {@code title} обязателен для видео техники. */
    public record CreateVideoRequest(UUID id, String kind, Long sizeBytes, Long previewSizeBytes, Integer durationMs,
                                     String title, UUID exerciseId, String exerciseName) {
    }

    /**
     * {@code videoUrl}, {@code previewUrl} и {@code urlExpiresAt} есть только у готового ролика;
     * {@code usage} — только владельцу.
     */
    public record VideoResponse(UUID id, UUID ownerId, String kind, String status, long sizeBytes, int durationMs,
                                String title, UUID exerciseId, String exerciseName, Instant createdAt,
                                Instant uploadedAt, URI videoUrl, URI previewUrl, Instant urlExpiresAt,
                                Usage usage) {
    }

    /** Где ролик используется: комментарии к упражнениям и программы. */
    public record Usage(List<CommentUsage> comments, List<ProgramUsage> programs) {

        public static final Usage NONE = new Usage(List.of(), List.of());
    }

    /** {@code workoutDate} — {@code null}, пока тренировка не опубликована. */
    public record CommentUsage(UUID commentId, UUID ownerId, UUID workoutId, LocalDate workoutDate,
                               UUID workoutExerciseId, Integer setNumber) {
    }

    public record ProgramUsage(UUID programId, UUID ownerId, String name) {
    }

    /** Ссылки PUT; заголовок {@code Content-Type} и размер тела должны совпасть с подписанными. */
    public record UploadTarget(URI videoUrl, String videoContentType, URI previewUrl, String previewContentType,
                               Instant expiresAt) {
    }

    /** {@code upload} — {@code null}, если ролик уже загружен. */
    public record UploadResponse(VideoResponse video, UploadTarget upload) {
    }

    public record QuotaResponse(long usedBytes, long limitBytes) {
    }

    public record VideoListResponse(List<VideoResponse> videos, String nextCursor, QuotaResponse quota) {
    }

    /** {@code removedFrom} — откуда ролик убран при удалении. */
    public record DeleteResponse(UUID id, Usage removedFrom) {
    }

    public record Failure(String code, String message) {
    }
}
