package com.sizylift.backend.feedback.controller;

import com.sizylift.backend.feedback.dto.FeedbackRequest;
import com.sizylift.backend.feedback.entity.FeedbackAuthor;
import com.sizylift.backend.feedback.service.FeedbackService;
import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedbackController {
    public static final String PATH = "/api/v1/feedback";

    private final FeedbackService service;
    private final AuthenticatedAccount accounts;

    public FeedbackController(FeedbackService service, AuthenticatedAccount accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> submit(@Valid @RequestBody FeedbackRequest feedback, HttpServletRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        if (!service.submit(feedback, request.getRemoteAddr(), () -> author(jwt))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return ResponseEntity.noContent().build();
    }

    private FeedbackAuthor author(Jwt jwt) {
        if (jwt == null) return FeedbackAuthor.ANONYMOUS;
        var account = accounts.resolve(jwt);
        return new FeedbackAuthor(account.id(), account.email(),
                jwt.getClaimAsString(StandardClaimNames.PREFERRED_USERNAME),
                jwt.getClaimAsString(StandardClaimNames.NAME));
    }
}
