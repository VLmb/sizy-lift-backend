package com.sizylift.backend.sync.controller;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.sync.dto.SyncChangesResponse;
import com.sizylift.backend.sync.dto.SyncPushRequest;
import com.sizylift.backend.sync.dto.SyncPushResponse;
import com.sizylift.backend.sync.service.SyncChangesService;
import com.sizylift.backend.sync.service.SyncPushService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Синхронизация v2 (docs/sync.md). */
@RestController
@RequestMapping("/api/v2/sync")
public class SyncController {

    private final AuthenticatedAccount authenticatedAccount;
    private final SyncPushService push;
    private final SyncChangesService changes;

    public SyncController(AuthenticatedAccount authenticatedAccount, SyncPushService push,
                          SyncChangesService changes) {
        this.authenticatedAccount = authenticatedAccount;
        this.push = push;
        this.changes = changes;
    }

    @PostMapping("/push")
    public SyncPushResponse push(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SyncPushRequest request) {
        return push.push(authenticatedAccount.resolve(jwt).id(), request.items());
    }

    @GetMapping("/changes")
    public SyncChangesResponse changes(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(defaultValue = "0") @Min(0) long after,
                                       @RequestParam(defaultValue = "" + SyncChangesService.DEFAULT_LIMIT)
                                       @Min(1) @Max(SyncChangesService.MAX_LIMIT) int limit) {
        return changes.changes(authenticatedAccount.resolve(jwt).id(), after, limit);
    }
}
