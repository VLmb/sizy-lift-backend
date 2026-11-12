package com.sizylift.backend.video.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.repository.BetweenDataRepository;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.publication.service.ServerRewrites;
import com.sizylift.backend.video.config.VideoProperties;
import com.sizylift.backend.video.dto.VideoDtos.CommentUsage;
import com.sizylift.backend.video.dto.VideoDtos.CreateVideoRequest;
import com.sizylift.backend.video.dto.VideoDtos.DeleteResponse;
import com.sizylift.backend.video.dto.VideoDtos.ProgramUsage;
import com.sizylift.backend.video.dto.VideoDtos.QuotaResponse;
import com.sizylift.backend.video.dto.VideoDtos.UploadResponse;
import com.sizylift.backend.video.dto.VideoDtos.UploadTarget;
import com.sizylift.backend.video.dto.VideoDtos.Usage;
import com.sizylift.backend.video.dto.VideoDtos.VideoListResponse;
import com.sizylift.backend.video.dto.VideoDtos.VideoResponse;
import com.sizylift.backend.video.entity.Video;
import com.sizylift.backend.video.entity.VideoKind;
import com.sizylift.backend.video.entity.VideoStatus;
import com.sizylift.backend.video.repository.VideoRepository;
import com.sizylift.backend.video.repository.VideoRepository.CommentUse;
import com.sizylift.backend.video.repository.VideoRepository.ProgramUse;
import com.sizylift.backend.video.service.VideoFailure.Reason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Видео подходов и техники (ADR-0012): выдача места и подписанных ссылок, доступ, квота, удаление.
 * Чужое или недоступное видео неотличимо от несуществующего — 404.
 */
@Service
public class VideoService {

    public static final String VIDEO_CONTENT_TYPE = "video/mp4";
    public static final String PREVIEW_CONTENT_TYPE = "image/jpeg";
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int DEFAULT_PAGE_SIZE = 30;
    public static final int MAX_PAGE_SIZE = 100;
    private static final String CURSOR_SEPARATOR = "_";

    private final VideoRepository videos;
    private final VideoStorage storage;
    private final VideoProperties properties;
    private final AccessPolicy access;
    private final ServerRewrites rewrites;
    private final BetweenDataRepository between;
    private final Clock clock;

    VideoService(VideoRepository videos, VideoStorage storage, VideoProperties properties, AccessPolicy access,
                 ServerRewrites rewrites, BetweenDataRepository between, Clock clock) {
        this.videos = videos;
        this.storage = storage;
        this.properties = properties;
        this.access = access;
        this.rewrites = rewrites;
        this.between = between;
        this.clock = clock;
    }

    /** Выдаёт место под ролик. Повтор с тем же id до завершения загрузки возвращает новые ссылки. */
    @Transactional
    public UploadResponse create(UUID callerId, CreateVideoRequest request) {
        var draft = draft(callerId, request);
        videos.lockOwner(callerId);
        var existing = videos.find(draft.id());
        if (existing.isPresent()) {
            return repeated(callerId, existing.get(), draft);
        }
        requireRoom(draft);
        videos.insert(draft);
        return new UploadResponse(response(draft, Usage.NONE), uploadTarget(draft));
    }

    /** Подтверждает загрузку: оба объекта на месте и их размер совпадает с заявленным. */
    @Transactional
    public VideoResponse complete(UUID callerId, UUID id) {
        var video = videos.lock(id).filter(found -> found.ownerId().equals(callerId))
                .orElseThrow(() -> new VideoFailure(Reason.NOT_FOUND));
        if (!video.ready()) {
            if (!uploaded(video.videoKey(), video.sizeBytes())
                    || !uploaded(video.previewKey(), video.previewSizeBytes())) {
                throw new VideoFailure(Reason.UPLOAD_INCOMPLETE);
            }
            videos.markReady(id, now());
        }
        return view(callerId, videos.find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public VideoResponse get(UUID callerId, UUID id) {
        var video = videos.find(id).filter(found -> canView(callerId, found))
                .orElseThrow(() -> new VideoFailure(Reason.NOT_FOUND));
        return view(callerId, video);
    }

    /** Свои ролики одного вида, новые сначала, со ссылками на превью и сведениями об использовании. */
    @Transactional(readOnly = true)
    public VideoListResponse list(UUID callerId, String kindCode, String cursor, Integer limit) {
        var kind = VideoKind.parse(kindCode).orElseThrow(() -> new VideoFailure(Reason.INVALID_VIDEO));
        var size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new VideoFailure(Reason.INVALID_VIDEO);
        }
        var before = Cursor.parse(cursor);
        var page = videos.list(callerId, kind, before == null ? null : before.createdAt(),
                before == null ? null : before.id(), size + 1);
        var more = page.size() > size;
        var shown = more ? page.subList(0, size) : page;
        var usage = usage(callerId, shown.stream().map(Video::id).toList());
        var expiresAt = now().plus(properties.viewUrlTtl());
        var items = shown.stream()
                .map(video -> response(video, usage.getOrDefault(video.id(), Usage.NONE), false, expiresAt))
                .toList();
        var next = more ? Cursor.of(shown.getLast()) : null;
        return new VideoListResponse(items, next, new QuotaResponse(videos.usedBytes(callerId, kind), quota(kind)));
    }

    /**
     * Удаляет ролик владельца и снимает его с комментариев и программ: комментарий без текста удаляется,
     * остальные агрегаты получают новую ревизию. Файлы убирает {@link VideoMaintenance}.
     */
    @Transactional
    public DeleteResponse delete(UUID callerId, UUID id) {
        var video = videos.find(id).filter(found -> found.ownerId().equals(callerId))
                .orElseThrow(() -> new VideoFailure(Reason.NOT_FOUND));
        var removedFrom = usage(callerId, List.of(id)).getOrDefault(id, Usage.NONE);
        detach(id);
        videos.lock(id);
        videos.delete(id);
        videos.queueRemoval(id, video.ownerId(), now());
        return new DeleteResponse(id, removedFrom);
    }

    /** Перед удалением аккаунта: файлы всех его роликов ставятся в очередь удаления. */
    @Transactional
    public void queueRemovalOfAll(UUID ownerId) {
        var now = now();
        videos.ownedIds(ownerId).forEach(id -> videos.queueRemoval(id, ownerId, now));
    }

    private void detach(UUID videoId) {
        var comments = videos.commentUses(List.of(videoId));
        var programs = videos.programUses(List.of(videoId));
        if (comments.isEmpty() && programs.isEmpty()) {
            return;
        }
        var owners = new HashSet<UUID>();
        comments.forEach(use -> owners.add(use.ownerId()));
        programs.forEach(use -> owners.add(use.ownerId()));
        rewrites.rewrite(owners, () -> {
            comments.forEach(use -> rewrites.apply(use.ownerId(), AggregateType.WORKOUT_COMMENT, use.commentId(),
                    () -> detachFromComment(use, videoId)));
            programs.forEach(use -> rewrites.apply(use.ownerId(), AggregateType.PROGRAM, use.programId(), () -> {
                videos.detachFromProgram(use.ownerId(), use.programId(), videoId);
                return false;
            }));
        });
    }

    /** Комментарий, в котором было только видео, без него пуст — удаляется. */
    private boolean detachFromComment(CommentUse use, UUID videoId) {
        if (use.textBlank()) {
            between.deleteComment(use.ownerId(), use.commentId());
            return true;
        }
        videos.detachFromComment(use.ownerId(), use.commentId(), videoId);
        return false;
    }

    private Video draft(UUID callerId, CreateVideoRequest request) {
        if (request == null || request.id() == null || request.sizeBytes() == null
                || request.previewSizeBytes() == null || request.durationMs() == null
                || request.sizeBytes() <= 0 || request.previewSizeBytes() <= 0 || request.durationMs() <= 0) {
            throw new VideoFailure(Reason.INVALID_VIDEO);
        }
        var kind = VideoKind.parse(request.kind()).orElseThrow(() -> new VideoFailure(Reason.INVALID_VIDEO));
        var title = text(request.title());
        if (kind == VideoKind.TECHNIQUE && title == null) {
            throw new VideoFailure(Reason.INVALID_VIDEO);
        }
        if (request.sizeBytes() > properties.maxSizeBytes()
                || request.previewSizeBytes() > properties.maxPreviewSizeBytes()) {
            throw new VideoFailure(Reason.TOO_LARGE);
        }
        if (request.durationMs() > properties.maxDuration().toMillis()) {
            throw new VideoFailure(Reason.TOO_LONG);
        }
        if (kind == VideoKind.TECHNIQUE && !videos.coachEnabled(callerId)) {
            throw new VideoFailure(Reason.COACH_REQUIRED);
        }
        return new Video(request.id(), callerId, kind, VideoStatus.PENDING, request.sizeBytes(),
                request.previewSizeBytes(), request.durationMs(), kind == VideoKind.TECHNIQUE ? title : null,
                request.exerciseId(), text(request.exerciseName()), now(), null);
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_TITLE_LENGTH) {
            throw new VideoFailure(Reason.INVALID_VIDEO);
        }
        return value.strip();
    }

    /** Повторный запрос места: тот же владелец и те же файлы, иначе id занят. */
    private UploadResponse repeated(UUID callerId, Video existing, Video draft) {
        if (!existing.ownerId().equals(callerId) || existing.kind() != draft.kind()
                || existing.sizeBytes() != draft.sizeBytes()
                || existing.previewSizeBytes() != draft.previewSizeBytes()) {
            throw new VideoFailure(Reason.ID_CONFLICT);
        }
        return new UploadResponse(view(callerId, existing), existing.ready() ? null : uploadTarget(existing));
    }

    private void requireRoom(Video draft) {
        if (videos.usedBytes(draft.ownerId(), draft.kind()) + draft.storedBytes() > quota(draft.kind())) {
            throw new VideoFailure(Reason.QUOTA_EXCEEDED);
        }
        if (videos.totalBytes() + draft.storedBytes() > properties.bucketLimitBytes()) {
            throw new VideoFailure(Reason.STORAGE_FULL);
        }
    }

    private long quota(VideoKind kind) {
        return kind == VideoKind.SET ? properties.quota().setBytes() : properties.quota().techniqueBytes();
    }

    private UploadTarget uploadTarget(Video video) {
        var ttl = properties.uploadUrlTtl();
        return new UploadTarget(
                storage.uploadUrl(video.videoKey(), VIDEO_CONTENT_TYPE, video.sizeBytes(), ttl), VIDEO_CONTENT_TYPE,
                storage.uploadUrl(video.previewKey(), PREVIEW_CONTENT_TYPE, video.previewSizeBytes(), ttl),
                PREVIEW_CONTENT_TYPE, now().plus(ttl));
    }

    private boolean uploaded(String key, long expectedSize) {
        var size = storage.size(key);
        return size.isPresent() && size.getAsLong() == expectedSize;
    }

    /**
     * Видео подхода видят владелец и его действующий тренер; видео техники — тренер-владелец и его действующий
     * подопечный, в чьей программе оно прикреплено.
     */
    private boolean canView(UUID callerId, Video video) {
        if (video.ownerId().equals(callerId)) {
            return true;
        }
        if (video.kind() == VideoKind.SET) {
            return access.activeCoach(video.ownerId()).filter(callerId::equals).isPresent();
        }
        return access.activeCoach(callerId).filter(video.ownerId()::equals).isPresent()
                && videos.attachedToProgramOf(callerId, video.id());
    }

    private VideoResponse view(UUID callerId, Video video) {
        var usage = video.ownerId().equals(callerId)
                ? usage(callerId, List.of(video.id())).getOrDefault(video.id(), Usage.NONE) : null;
        return response(video, usage, true, now().plus(properties.viewUrlTtl()));
    }

    private VideoResponse response(Video video, Usage usage) {
        return response(video, usage, false, null);
    }

    private VideoResponse response(Video video, Usage usage, boolean withVideoUrl, Instant expiresAt) {
        var ready = video.ready() && expiresAt != null;
        var ttl = properties.viewUrlTtl();
        return new VideoResponse(video.id(), video.ownerId(), video.kind().code(), video.status().code(),
                video.sizeBytes(), video.durationMs(), video.title(), video.exerciseId(), video.exerciseName(),
                video.createdAt(), video.uploadedAt(),
                ready && withVideoUrl ? storage.viewUrl(video.videoKey(), ttl) : null,
                ready ? storage.viewUrl(video.previewKey(), ttl) : null, ready ? expiresAt : null, usage);
    }

    /**
     * Использование роликов владельца. Программы — его собственные и действующих подопечных: программы бывших
     * подопечных ему не показываются, но ролик с них при удалении снимается.
     */
    private Map<UUID, Usage> usage(UUID ownerId, List<UUID> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> visibleOwners = new HashSet<>(videos.activeAthletesOf(ownerId));
        visibleOwners.add(ownerId);
        var comments = videos.commentUses(videoIds).stream()
                .filter(use -> visibleOwners.contains(use.ownerId()))
                .collect(Collectors.groupingBy(CommentUse::videoId));
        var programs = videos.programUses(videoIds).stream()
                .filter(use -> visibleOwners.contains(use.ownerId()))
                .collect(Collectors.groupingBy(ProgramUse::videoId));
        var ids = new ArrayList<>(videoIds);
        return ids.stream().collect(Collectors.toMap(id -> id, id -> new Usage(
                comments.getOrDefault(id, List.of()).stream().map(use -> new CommentUsage(use.commentId(),
                        use.ownerId(), use.workoutId(), use.workoutDate(), use.workoutExerciseId(),
                        use.setNumber())).toList(),
                programs.getOrDefault(id, List.of()).stream().map(use -> new ProgramUsage(use.programId(),
                        use.ownerId(), use.name())).toList())));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Курсор страницы: время создания и id последнего показанного ролика. */
    private record Cursor(Instant createdAt, UUID id) {

        static String of(Video video) {
            return video.createdAt() + CURSOR_SEPARATOR + video.id();
        }

        static Cursor parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            var parts = value.split(CURSOR_SEPARATOR);
            try {
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException e) {
                throw new VideoFailure(Reason.INVALID_VIDEO);
            }
        }
    }
}
