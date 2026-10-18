package com.sizylift.backend.publication.controller;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.publication.dto.PublicationBatchRequest;
import com.sizylift.backend.publication.dto.PublicationBatchResponse;
import com.sizylift.backend.publication.service.PublicationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publication of data from the phone (docs/publication.md). */
@RestController
@RequestMapping("/api/v1/publication")
public class PublicationController {

    private final AuthenticatedAccount authenticatedAccount;
    private final PublicationService publication;

    public PublicationController(AuthenticatedAccount authenticatedAccount, PublicationService publication) {
        this.authenticatedAccount = authenticatedAccount;
        this.publication = publication;
    }

    @PostMapping("/batch")
    public PublicationBatchResponse publish(@AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody PublicationBatchRequest request) {
        return publication.publish(authenticatedAccount.resolve(jwt).getId(), request.items());
    }
}
