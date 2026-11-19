package com.sizylift.backend.web.controller;

import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.web.service.PlannedWorkoutService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/web/planned-workouts/{id}/skip")
public class PlannedWorkoutController {

    private final AuthenticatedAccount accounts;
    private final PlannedWorkoutService plans;

    public PlannedWorkoutController(AuthenticatedAccount accounts, PlannedWorkoutService plans) {
        this.accounts = accounts;
        this.plans = plans;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skip(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        plans.skip(accounts.resolve(jwt).id(), id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unskip(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        plans.unskip(accounts.resolve(jwt).id(), id);
    }
}
