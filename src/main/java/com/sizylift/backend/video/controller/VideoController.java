package com.sizylift.backend.video.controller;

import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.video.dto.VideoDtos.CreateVideoRequest;
import com.sizylift.backend.video.dto.VideoDtos.DeleteResponse;
import com.sizylift.backend.video.dto.VideoDtos.ExerciseVideoListResponse;
import com.sizylift.backend.video.dto.VideoDtos.ExerciseVideoRequest;
import com.sizylift.backend.video.dto.VideoDtos.ExerciseVideoResponse;
import com.sizylift.backend.video.dto.VideoDtos.RenameRequest;
import com.sizylift.backend.video.dto.VideoDtos.Failure;
import com.sizylift.backend.video.dto.VideoDtos.UploadResponse;
import com.sizylift.backend.video.dto.VideoDtos.VideoListResponse;
import com.sizylift.backend.video.dto.VideoDtos.VideoResponse;
import com.sizylift.backend.video.service.ExerciseVideoService;
import com.sizylift.backend.video.service.VideoFailure;
import com.sizylift.backend.video.service.VideoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Видео подходов и техники (docs/video.md). Файлы идут мимо сервера, по подписанным ссылкам. */
@RestController
@RequestMapping("/api/v2/videos")
public class VideoController {

    private final AuthenticatedAccount accounts;
    private final VideoService videos;
    private final ExerciseVideoService exerciseVideos;

    public VideoController(AuthenticatedAccount accounts, VideoService videos, ExerciseVideoService exerciseVideos) {
        this.accounts = accounts;
        this.videos = videos;
        this.exerciseVideos = exerciseVideos;
    }

    /** Видео упражнений: свои и действующего тренера (docs/video.md, «Видео упражнения»). */
    @GetMapping("/exercises")
    public ExerciseVideoListResponse exerciseVideos(@AuthenticationPrincipal Jwt jwt) {
        return exerciseVideos.list(accounts.resolve(jwt).id());
    }

    @PutMapping("/exercises/{exerciseId}")
    public ExerciseVideoResponse attachToExercise(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exerciseId,
                                                  @RequestBody ExerciseVideoRequest request) {
        return exerciseVideos.attach(accounts.resolve(jwt).id(), exerciseId, request);
    }

    @DeleteMapping("/exercises/{exerciseId}")
    public ResponseEntity<Void> detachFromExercise(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exerciseId) {
        exerciseVideos.detach(accounts.resolve(jwt).id(), exerciseId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public VideoResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @RequestBody RenameRequest request) {
        return videos.rename(accounts.resolve(jwt).id(), id, request);
    }

    @PostMapping
    public UploadResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateVideoRequest request) {
        return videos.create(accounts.resolve(jwt).id(), request);
    }

    @PostMapping("/{id}/complete")
    public VideoResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return videos.complete(accounts.resolve(jwt).id(), id);
    }

    @GetMapping("/{id}")
    public VideoResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return videos.get(accounts.resolve(jwt).id(), id);
    }

    @GetMapping
    public VideoListResponse list(@AuthenticationPrincipal Jwt jwt, @RequestParam String kind,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        return videos.list(accounts.resolve(jwt).id(), kind, cursor, limit);
    }

    @DeleteMapping("/{id}")
    public DeleteResponse delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return videos.delete(accounts.resolve(jwt).id(), id);
    }

    @ExceptionHandler(VideoFailure.class)
    ResponseEntity<Failure> failure(VideoFailure failure) {
        var reason = failure.reason();
        return ResponseEntity.status(reason.status()).body(new Failure(reason.code(), failure.getMessage()));
    }
}
