package com.sizylift.backend.identity.controller;

import com.sizylift.backend.identity.deletion.KeycloakAccountDeletion;
import com.sizylift.backend.identity.dto.CurrentUserResponse;
import com.sizylift.backend.video.config.VideoProperties;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {

    private final AuthenticatedAccount authenticatedAccount;
    private final KeycloakAccountDeletion deletion;
    private final VideoProperties video;

    public CurrentUserController(AuthenticatedAccount authenticatedAccount, KeycloakAccountDeletion deletion,
                                 VideoProperties video) {
        this.authenticatedAccount = authenticatedAccount;
        this.deletion = deletion;
        this.video = video;
    }

    @GetMapping
    public CurrentUserResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
        return CurrentUserResponse.from(authenticatedAccount.resolve(jwt), deletion.configured(), video.enabled());
    }
}
