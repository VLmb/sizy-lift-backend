package com.sizylift.backend.identity.controller;

import com.sizylift.backend.identity.dto.CurrentUserResponse;
import com.sizylift.backend.identity.service.ExternalIdentity;
import com.sizylift.backend.identity.service.UserAccountService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {

    private final UserAccountService userAccounts;

    public CurrentUserController(UserAccountService userAccounts) {
        this.userAccounts = userAccounts;
    }

    @GetMapping
    public CurrentUserResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
        var identity = new ExternalIdentity(
                jwt.getClaimAsString(JwtClaimNames.ISS),
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")));
        return CurrentUserResponse.from(userAccounts.resolve(identity));
    }
}
