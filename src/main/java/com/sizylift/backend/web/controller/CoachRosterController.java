package com.sizylift.backend.web.controller;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.web.repository.CoachRosterRepository.AthleteRow;
import com.sizylift.backend.web.service.CoachRosterService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/web/athletes")
public class CoachRosterController {
    private final AuthenticatedAccount accounts;
    private final CoachRosterService roster;

    public CoachRosterController(AuthenticatedAccount accounts, CoachRosterService roster) {
        this.accounts = accounts;
        this.roster = roster;
    }

    @GetMapping
    public List<AthleteRow> list(@AuthenticationPrincipal Jwt jwt) {
        return roster.list(accounts.resolve(jwt).id());
    }

    @PostMapping("/{linkId}/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seen(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID linkId) {
        roster.seen(accounts.resolve(jwt).id(), linkId);
    }
}
