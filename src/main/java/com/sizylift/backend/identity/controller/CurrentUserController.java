package com.sizylift.backend.identity.controller;

import com.sizylift.backend.identity.dto.CurrentUserResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {

    private final AuthenticatedAccount authenticatedAccount;

    public CurrentUserController(AuthenticatedAccount authenticatedAccount) {
        this.authenticatedAccount = authenticatedAccount;
    }

    @GetMapping
    public CurrentUserResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
        return CurrentUserResponse.from(authenticatedAccount.resolve(jwt));
    }
}
