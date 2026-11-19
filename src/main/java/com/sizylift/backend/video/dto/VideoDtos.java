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

    /**
     * {@code id} придумывает клиент; {@code title} обязателен для видео техники. {@code source}: {@code device}
     * (по умолчанию) — приложение уже сжало ролик и сделало превью; {@code browser} — исходный файл как есть,
     * {@code sizeBytes} — его размер, {@code contentType} — его тип, превью делает сервер.
     */
    public record CreateVideoRequest(UUID id, String kind, Long sizeBytes, Long previewSizeBytes, Integer durationMs,
                                     String title, UUID exerciseId, String exerciseName, String source,
                                     String contentType) {
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

    /** Где ролик используется: комментарии к упражнениям, программы и упражнения (видео упражнения). */
    public record Usage(List<CommentUsage> comments, List<ProgramUsage> programs, List<ExerciseUsage> exercises) {

        public static final Usage NONE = new Usage(List.of(), List.of(), List.of());
    }

    /** Упражнение тренера, к которому ролик прикреплён для всех подопечных. */
    public record ExerciseUsage(UUID exerciseId, String exerciseName) {
    }

    /** Новое название ролика; у видео техники обязательно. */
    public record RenameRequest(String title) {
    }

    /** Прикрепить видео техники к упражнению; {@code exerciseName} — для «Где используется». */
    public record ExerciseVideoRequest(UUID videoId, String exerciseName) {
    }

    /**
     * Видео упражнения. {@code exerciseId} — в пространстве того, кто спрашивает: у подопечного упражнение
     * тренера из его библиотеки — копия под производным id. {@code own} — своё видео, иначе — тренера.
     */
    public record ExerciseVideoResponse(UUID exerciseId, String exerciseName, UUID videoId, UUID ownerId,
                                        String title, String status, int durationMs, URI previewUrl,
                                        Instant urlExpiresAt, boolean own) {
    }

    public record ExerciseVideoListResponse(List<ExerciseVideoResponse> exercises) {
    }

    /** {@code workoutDate} — {@code null}, пока тренировка не опубликована. */
    public record CommentUsage(UUID commentId, UUID ownerId, UUID workoutId, LocalDate workoutDate,
                               UUID workoutExerciseId, Integer setNumber) {
    }

    public record ProgramUsage(UUID programId, UUID ownerId, String name) {
    }

    /**
     * Ссылки PUT; заголовок {@code Content-Type} и размер тела должны совпасть с подписанными. У загрузки
     * из браузера одна ссылка — на исходник, {@code previewUrl} и {@code previewContentType} пусты.
     */
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
