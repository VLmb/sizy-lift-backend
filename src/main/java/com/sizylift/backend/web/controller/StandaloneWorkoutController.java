package com.sizylift.backend.web.controller;

import java.util.UUID;
import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.web.dto.StandaloneWorkoutDtos.Response;
import com.sizylift.backend.web.dto.StandaloneWorkoutDtos.SaveRequest;
import com.sizylift.backend.web.service.StandaloneWorkoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/web/standalone-workouts/{id}")
public class StandaloneWorkoutController {
    private final AuthenticatedAccount accounts;
    private final StandaloneWorkoutService workouts;

    public StandaloneWorkoutController(AuthenticatedAccount accounts, StandaloneWorkoutService workouts) { this.accounts = accounts; this.workouts = workouts; }

    @GetMapping
    public Response read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(required = false) UUID ownerId) {
        return workouts.read(accounts.resolve(jwt).id(), ownerId, id);
    }

    @PutMapping
    public Response save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(required = false) UUID ownerId, @Valid @RequestBody SaveRequest request) {
        return workouts.save(accounts.resolve(jwt).id(), ownerId, id, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(required = false) UUID ownerId, @RequestParam @Min(1) long revision) {
        workouts.delete(accounts.resolve(jwt).id(), ownerId, id, revision);
    }
}
