package com.sizylift.backend.web.controller;

import java.util.UUID;
import java.time.LocalDate;
import com.sizylift.backend.web.dto.WebDtos.*;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.web.dto.WebDtos.CatalogResponse;
import com.sizylift.backend.web.dto.WebDtos.ExerciseHistoryResponse;
import com.sizylift.backend.web.dto.WebDtos.OneRepMaxListResponse;
import com.sizylift.backend.web.dto.WebDtos.ProgramListResponse;
import com.sizylift.backend.web.dto.WebDtos.ProgramResponse;
import com.sizylift.backend.web.service.WebReadService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Чтение для веб-версии (docs/web.md). {@code ownerId} — чьи данные: по умолчанию свои, тренер передаёт id
 * подопечного. Запись — {@code POST /api/v2/sync/push}.
 */
@RestController
@RequestMapping("/api/v2/web")
public class WebController {

    private final AuthenticatedAccount authenticatedAccount;
    private final WebReadService reads;

    public WebController(AuthenticatedAccount authenticatedAccount, WebReadService reads) {
        this.authenticatedAccount = authenticatedAccount;
        this.reads = reads;
    }

    @GetMapping("/catalog")
    public CatalogResponse catalog(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID ownerId) {
        return reads.catalog(userId(jwt), ownerId);
    }

    @GetMapping("/programs")
    public ProgramListResponse programs(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(required = false) UUID ownerId) {
        return reads.programs(userId(jwt), ownerId);
    }

    @GetMapping("/programs/{programId}")
    public ProgramResponse program(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID programId,
                                   @RequestParam(required = false) UUID ownerId) {
        return reads.program(userId(jwt), ownerId, programId);
    }

    @GetMapping("/one-rep-maxes")
    public OneRepMaxListResponse oneRepMaxes(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(required = false) UUID ownerId) {
        return reads.oneRepMaxes(userId(jwt), ownerId);
    }

    @GetMapping("/one-rep-maxes/{exerciseId}/history")
    public OneRepMaxHistoryResponse oneRepMaxHistory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exerciseId,
                                                     @RequestParam(required = false) UUID ownerId) {
        return reads.oneRepMaxHistory(userId(jwt), ownerId, exerciseId);
    }

    @GetMapping("/exercises/{exerciseId}/history")
    public ExerciseHistoryResponse history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID exerciseId,
                                           @RequestParam(required = false) UUID ownerId,
                                           @RequestParam(defaultValue = "" + WebReadService.DEFAULT_HISTORY_LIMIT)
                                           @Min(1) @Max(WebReadService.MAX_HISTORY_LIMIT) int limit,
                                           @RequestParam(defaultValue = "0") @Min(0) @Max(WebReadService.MAX_HISTORY_OFFSET) int offset) {
        return reads.history(userId(jwt), ownerId, exerciseId, limit, offset);
    }

    @GetMapping("/timeline")
    public TimelineResponse timeline(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID ownerId,
                                     @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reads.timeline(userId(jwt), ownerId, from, to);
    }

    @GetMapping("/workouts/{id}")
    public WorkoutResponse workout(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @RequestParam(required = false) UUID ownerId) {
        return reads.workout(userId(jwt), ownerId, id);
    }

    @GetMapping("/profile")
    public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID ownerId) {
        return reads.profile(userId(jwt), ownerId);
    }

    private UUID userId(Jwt jwt) {
        return authenticatedAccount.resolve(jwt).id();
    }
}
