package com.sizylift.backend.photo.controller;

import java.io.IOException;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.photo.dto.WorkoutPhotoDtos.Page;
import com.sizylift.backend.photo.dto.WorkoutPhotoDtos.View;
import com.sizylift.backend.photo.dto.WorkoutPhotoDtos.WriteResult;
import com.sizylift.backend.photo.service.WorkoutPhotoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(WorkoutPhotoController.PATH)
public class WorkoutPhotoController {
    public static final String PATH = "/api/v2/workout-photos";

    private final AuthenticatedAccount accounts;
    private final WorkoutPhotoService photos;

    public WorkoutPhotoController(AuthenticatedAccount accounts, WorkoutPhotoService photos) {
        this.accounts = accounts;
        this.photos = photos;
    }

    @GetMapping
    public Page page(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID after) {
        return photos.page(accounts.resolve(jwt).id(), after);
    }

    @GetMapping("/{workoutId}")
    public ResponseEntity<View> view(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workoutId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(photos.view(accounts.resolve(jwt).id(), workoutId));
    }

    @PutMapping(path = "/{workoutId}/{version}", consumes = MediaType.IMAGE_JPEG_VALUE)
    public WriteResult upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workoutId,
                              @PathVariable UUID version, @RequestParam(required = false) UUID base,
                              HttpServletRequest request) throws IOException {
        return photos.upload(accounts.resolve(jwt).id(), workoutId, version, base, request.getInputStream(),
                request.getContentLengthLong());
    }

    @DeleteMapping("/{workoutId}/{version}")
    public WriteResult delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workoutId,
                              @PathVariable UUID version, @RequestParam(required = false) UUID base) {
        return photos.delete(accounts.resolve(jwt).id(), workoutId, version, base);
    }
}
