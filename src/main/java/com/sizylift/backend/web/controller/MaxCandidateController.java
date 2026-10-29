package com.sizylift.backend.web.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.web.dto.MaxCandidateDtos.MaxCandidatesResponse;
import com.sizylift.backend.web.service.MaxCandidateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Подходы для расчётного ПМ (docs/web.md); {@code ownerId} — как у остальных запросов {@code /api/v2/web}. */
@RestController
@RequestMapping("/api/v2/web")
public class MaxCandidateController {

    private final AuthenticatedAccount authenticatedAccount;
    private final MaxCandidateService candidates;

    public MaxCandidateController(AuthenticatedAccount authenticatedAccount, MaxCandidateService candidates) {
        this.authenticatedAccount = authenticatedAccount;
        this.candidates = candidates;
    }

    @GetMapping("/max-candidates")
    public MaxCandidatesResponse maxCandidates(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) UUID ownerId,
                                               @RequestParam LocalDate from) {
        return candidates.candidates(authenticatedAccount.resolve(jwt).id(), ownerId, from);
    }
}
