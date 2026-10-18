package com.sizylift.backend.publication.controller;

import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.publication.dto.PublicationBatchRequest;
import com.sizylift.backend.publication.dto.PublicationBatchResponse;
import com.sizylift.backend.publication.dto.PublicationScopeDto;
import com.sizylift.backend.publication.service.PublicationScope;
import com.sizylift.backend.publication.service.PublicationScope.Programs;
import com.sizylift.backend.publication.service.PublicationScopeService;
import com.sizylift.backend.publication.service.PublicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Publication of data from the phone (docs/publication.md). */
@RestController
@RequestMapping("/api/v1/publication")
public class PublicationController {

    private final AuthenticatedAccount authenticatedAccount;
    private final PublicationService publication;
    private final PublicationScopeService scopes;

    public PublicationController(AuthenticatedAccount authenticatedAccount, PublicationService publication,
                                 PublicationScopeService scopes) {
        this.authenticatedAccount = authenticatedAccount;
        this.publication = publication;
        this.scopes = scopes;
    }

    @PostMapping("/batch")
    public PublicationBatchResponse publish(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody PublicationBatchRequest request) {
        return publication.publish(userId(jwt), request.items());
    }

    @GetMapping("/scope")
    public PublicationScopeDto scope(@AuthenticationPrincipal Jwt jwt) {
        return toDto(scopes.get(userId(jwt)));
    }

    @PutMapping("/scope")
    public PublicationScopeDto updateScope(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody PublicationScopeDto scope) {
        return toDto(scopes.update(userId(jwt), new PublicationScope(scope.enabled(), scope.workouts(),
                scope.workoutsSince(), Programs.fromCode(scope.programs()), scope.profile(), scope.records(),
                scope.health())));
    }

    @DeleteMapping("/data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteData(@AuthenticationPrincipal Jwt jwt) {
        scopes.disableAndDeleteAll(userId(jwt));
    }

    private UUID userId(Jwt jwt) {
        return authenticatedAccount.resolve(jwt).getId();
    }

    private static PublicationScopeDto toDto(PublicationScope scope) {
        return new PublicationScopeDto(scope.enabled(), scope.workouts(), scope.workoutsSince(),
                scope.programs().code(), scope.profile(), scope.records(), scope.health());
    }
}
