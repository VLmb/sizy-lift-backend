package com.sizylift.backend.sync.controller;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.sync.dto.SyncPushRequest;
import com.sizylift.backend.sync.dto.SyncPushResponse;
import com.sizylift.backend.sync.service.SyncPushService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Синхронизация v2 (docs/sync.md). */
@RestController
@RequestMapping("/api/v2/sync")
public class SyncController {

    private final AuthenticatedAccount authenticatedAccount;
    private final SyncPushService push;

    public SyncController(AuthenticatedAccount authenticatedAccount, SyncPushService push) {
        this.authenticatedAccount = authenticatedAccount;
        this.push = push;
    }

    @PostMapping("/push")
    public SyncPushResponse push(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SyncPushRequest request) {
        return push.push(authenticatedAccount.resolve(jwt).id(), request.items());
    }
}
