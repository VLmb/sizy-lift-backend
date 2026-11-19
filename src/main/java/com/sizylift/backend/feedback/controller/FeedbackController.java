package com.sizylift.backend.feedback.controller;

import com.sizylift.backend.feedback.dto.FeedbackRequest;
import com.sizylift.backend.feedback.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedbackController {
    public static final String PATH = "/api/v1/feedback";

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> submit(@Valid @RequestBody FeedbackRequest feedback, HttpServletRequest request) {
        return switch (service.send(feedback, request.getRemoteAddr())) {
            case SENT -> ResponseEntity.noContent().build();
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }
}
