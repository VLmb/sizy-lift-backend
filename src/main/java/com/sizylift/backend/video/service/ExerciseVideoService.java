package com.sizylift.backend.video.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.repository.AthleteDataRepository;
import com.sizylift.backend.sync.service.CoachExerciseCopies;
import com.sizylift.backend.video.config.VideoProperties;
import com.sizylift.backend.video.dto.VideoDtos.ExerciseVideoListResponse;
import com.sizylift.backend.video.dto.VideoDtos.ExerciseVideoRequest;
import com.sizylift.backend.video.dto.VideoDtos.ExerciseVideoResponse;
import com.sizylift.backend.video.entity.Video;
import com.sizylift.backend.video.entity.VideoKind;
import com.sizylift.backend.video.entity.VideoStatus;
import com.sizylift.backend.video.repository.ExerciseVideoRepository;
import com.sizylift.backend.video.repository.ExerciseVideoRepository.Link;
import com.sizylift.backend.video.repository.VideoRepository;
import com.sizylift.backend.video.service.VideoFailure.Reason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Видео техники у упражнения (docs/video.md, «Видео упражнения»): тренер прикрепляет одно видео к упражнению,
 * и его видят все действующие подопечные там, где встречается это упражнение. Видео упражнения конкретной
 * программы перекрывает его — это решает клиент. Упражнение из библиотеки тренера у подопечного лежит копией
 * под производным id, поэтому подопечному отдаётся id копии.
 */
@Service
public class ExerciseVideoService {

    private static final int MAX_NAME_LENGTH = 200;

    private final ExerciseVideoRepository links;
    private final VideoRepository videos;
    private final AthleteDataRepository exercises;
    private final AccessPolicy access;
    private final VideoStorage storage;
    private final VideoProperties properties;
    private final Clock clock;

    ExerciseVideoService(ExerciseVideoRepository links, VideoRepository videos, AthleteDataRepository exercises,
                         AccessPolicy access, VideoStorage storage, VideoProperties properties, Clock clock) {
        this.links = links;
        this.videos = videos;
        this.exercises = exercises;
        this.access = access;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    /** Ставит или заменяет видео упражнения: своё видео техники и включённый режим тренера. */
    @Transactional
    public ExerciseVideoResponse attach(UUID callerId, UUID exerciseId, ExerciseVideoRequest request) {
        requireEnabled();
        if (request == null || request.videoId() == null) {
            throw new VideoFailure(Reason.INVALID_VIDEO);
        }
        var video = videos.find(request.videoId()).filter(found -> found.ownerId().equals(callerId))
                .orElseThrow(() -> new VideoFailure(Reason.NOT_FOUND));
        if (video.kind() != VideoKind.TECHNIQUE) {
            throw new VideoFailure(Reason.INVALID_VIDEO);
        }
        if (!videos.coachEnabled(callerId)) {
            throw new VideoFailure(Reason.COACH_REQUIRED);
        }
        var coachOwned = !exercises.ownedExercises(callerId, List.of(exerciseId)).isEmpty();
        links.attach(callerId, exerciseId, video.id(), name(request.exerciseName()), coachOwned, now());
        return links.linksOf(callerId).stream().filter(link -> link.exerciseId().equals(exerciseId)).findFirst()
                .map(link -> response(link, exerciseId, true, now().plus(properties.viewUrlTtl())))
                .orElseThrow();
    }

    @Transactional
    public void detach(UUID callerId, UUID exerciseId) {
        requireEnabled();
        if (!links.detach(callerId, exerciseId)) {
            throw new VideoFailure(Reason.NOT_FOUND);
        }
    }

    /**
     * Видео упражнений, которые видит вызывающий: свои (тренер) и действующего тренера (подопечный). У одного
     * упражнения своё видео главнее тренерского.
     */
    @Transactional(readOnly = true)
    public ExerciseVideoListResponse list(UUID callerId) {
        requireEnabled();
        var expiresAt = now().plus(properties.viewUrlTtl());
        var own = links.linksOf(callerId).stream()
                .map(link -> response(link, link.exerciseId(), true, expiresAt));
        var coach = access.activeCoach(callerId).stream()
                .flatMap(coachId -> links.linksOf(coachId).stream())
                .map(link -> response(link, athleteExerciseId(callerId, link), false, expiresAt));
        var byExercise = new LinkedHashMap<UUID, ExerciseVideoResponse>();
        Stream.concat(own, coach).forEach(item -> byExercise.putIfAbsent(item.exerciseId(), item));
        return new ExerciseVideoListResponse(List.copyOf(byExercise.values()));
    }

    /** Упражнение тренера из его библиотеки у подопечного — копия; каталожное — то же самое. */
    private static UUID athleteExerciseId(UUID athleteId, Link link) {
        return link.coachOwned() ? CoachExerciseCopies.copyId(athleteId, link.exerciseId()) : link.exerciseId();
    }

    private ExerciseVideoResponse response(Link link, UUID exerciseId, boolean own, Instant expiresAt) {
        var ready = link.status() == VideoStatus.READY;
        return new ExerciseVideoResponse(exerciseId, link.exerciseName(), link.videoId(), link.coachId(),
                link.title(), link.status().code(), link.durationMs(),
                ready ? storage.viewUrl(Video.previewKey(link.coachId(), link.videoId()), properties.viewUrlTtl())
                        : null,
                ready ? expiresAt : null, own);
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new VideoFailure(Reason.DISABLED);
        }
    }

    private static String name(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var name = value.strip();
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
