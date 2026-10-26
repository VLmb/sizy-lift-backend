package com.sizylift.backend.identity.deletion;

import java.time.Clock;
import java.time.Duration;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountDeletionController {
    private static final Duration RECENT_LOGIN = Duration.ofMinutes(5);
    private final AuthenticatedAccount accounts;
    private final AccountDeletionService deletion;
    private final Clock clock;

    public AccountDeletionController(AuthenticatedAccount accounts, AccountDeletionService deletion, Clock clock) {
        this.accounts = accounts;
        this.deletion = deletion;
        this.clock = clock;
    }

    @DeleteMapping("/api/v1/me")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delete(@AuthenticationPrincipal Jwt jwt) {
        var authTime = jwt.getClaimAsInstant("auth_time");
        if (authTime == null || authTime.isBefore(clock.instant().minus(RECENT_LOGIN))) {
            throw new RecentLoginRequired();
        }
        deletion.delete(accounts.resolve(jwt));
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class RecentLoginRequired extends RuntimeException {
    }
}
