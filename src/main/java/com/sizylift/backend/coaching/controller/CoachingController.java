package com.sizylift.backend.coaching.controller;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.coaching.dto.CoachingDtos.EnabledRequest;
import com.sizylift.backend.coaching.dto.CoachingDtos.InvitePreviewResponse;
import com.sizylift.backend.coaching.dto.CoachingDtos.InviteResponse;
import com.sizylift.backend.coaching.dto.CoachingDtos.LinkResponse;
import com.sizylift.backend.coaching.dto.CoachingDtos.OverviewResponse;
import com.sizylift.backend.coaching.entity.CoachingLink;
import com.sizylift.backend.coaching.service.CoachingService;
import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Режим тренера, приглашения и связи (docs/coaching.md). */
@RestController
@RequestMapping("/api/v2/coaching")
public class CoachingController {

    private final AuthenticatedAccount authenticatedAccount;
    private final CoachingService coaching;

    public CoachingController(AuthenticatedAccount authenticatedAccount, CoachingService coaching) {
        this.authenticatedAccount = authenticatedAccount;
        this.coaching = coaching;
    }

    @GetMapping
    public OverviewResponse overview(@AuthenticationPrincipal Jwt jwt) {
        var userId = userId(jwt);
        return overview(userId, coaching.overview(userId));
    }

    @PutMapping("/coach-mode")
    public OverviewResponse setCoachMode(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody EnabledRequest request) {
        var userId = userId(jwt);
        return overview(userId, coaching.setCoachMode(userId, request.enabled()));
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse createInvite(@AuthenticationPrincipal Jwt jwt) {
        var invite = coaching.createInvite(userId(jwt));
        return new InviteResponse(invite.token(), invite.expiresAt());
    }

    @GetMapping("/invites/{token}")
    public InvitePreviewResponse previewInvite(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        userId(jwt);
        var invite = coaching.preview(token);
        return new InvitePreviewResponse(invite.coachId(), coaching.displayName(invite.coachId()),
                invite.expiresAt(), sorted(AccessPolicy.COACH_VISIBLE), sorted(AccessPolicy.coachVisibleTypes(true)));
    }

    @GetMapping("/invites")
    public List<com.sizylift.backend.coaching.repository.CoachingRepository.PendingInvite> pendingInvites(
            @AuthenticationPrincipal Jwt jwt) {
        return coaching.pendingInvites(userId(jwt));
    }

    @DeleteMapping("/invites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        coaching.revokeInvite(userId(jwt), id);
    }

    public record AcceptRequest(UUID replaceLinkId, Boolean healthAccess) {
    }

    @PostMapping("/invites/{token}/accept")
    public LinkResponse acceptInvite(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
                                     @RequestBody(required = false) AcceptRequest request) {
        var userId = userId(jwt);
        return link(userId, coaching.accept(userId, token, request == null ? null : request.replaceLinkId(),
                request != null && Boolean.TRUE.equals(request.healthAccess())));
    }

    @PutMapping("/links/{linkId}/health-access")
    public LinkResponse setHealthAccess(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID linkId,
                                        @Valid @RequestBody EnabledRequest request) {
        var userId = userId(jwt);
        return link(userId, coaching.setHealthAccess(userId, linkId, request.enabled()));
    }

    @DeleteMapping("/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endLink(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID linkId) {
        coaching.end(userId(jwt), linkId);
    }

    private OverviewResponse overview(UUID userId, CoachingService.Overview overview) {
        return new OverviewResponse(overview.coachEnabled(),
                overview.coach() == null ? null : link(userId, overview.coach()),
                overview.athletes().stream().map(link -> link(userId, link)).toList());
    }

    private LinkResponse link(UUID userId, CoachingLink link) {
        var counterpart = link.coachId().equals(userId) ? link.athleteId() : link.coachId();
        return new LinkResponse(link.id(), link.coachId(), link.athleteId(), link.healthAccess(), link.createdAt(),
                link.endedAt(), coaching.displayName(counterpart));
    }

    private static List<String> sorted(Set<String> types) {
        return types.stream().sorted().toList();
    }

    private UUID userId(Jwt jwt) {
        return authenticatedAccount.resolve(jwt).id();
    }
}
