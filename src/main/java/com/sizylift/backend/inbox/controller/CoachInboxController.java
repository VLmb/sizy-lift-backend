package com.sizylift.backend.inbox.controller;

import java.time.Instant;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.inbox.dto.InboxDtos.InboxResponse;
import com.sizylift.backend.inbox.dto.InboxDtos.SeenRequest;
import com.sizylift.backend.inbox.service.CoachInboxService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** «Входящие» тренера (docs/coach-workspace.md, раздел 4). */
@RestController
@RequestMapping("/api/v2/web/inbox")
public class CoachInboxController {

    private final AuthenticatedAccount accounts;
    private final CoachInboxService inbox;

    public CoachInboxController(AuthenticatedAccount accounts, CoachInboxService inbox) {
        this.accounts = accounts;
        this.inbox = inbox;
    }

    @GetMapping
    public InboxResponse inbox(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID athleteId,
                               @RequestParam(defaultValue = "false") boolean unseenOnly,
                               @RequestParam(defaultValue = "" + CoachInboxService.DEFAULT_LIMIT)
                               @Min(1) @Max(CoachInboxService.MAX_LIMIT) int limit,
                               @RequestParam(required = false) Instant before,
                               @RequestParam(required = false) UUID beforeId) {
        return inbox.inbox(accounts.resolve(jwt).id(), athleteId, unseenOnly, before, beforeId, limit);
    }

    @PostMapping("/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seen(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) SeenRequest request) {
        inbox.seen(accounts.resolve(jwt).id(), request);
    }
}
