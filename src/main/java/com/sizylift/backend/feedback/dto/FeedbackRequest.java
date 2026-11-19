package com.sizylift.backend.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotNull Source source,
        @NotBlank @Size(max = MAX_MESSAGE_LENGTH) String message) {

    public static final int MAX_MESSAGE_LENGTH = 2000;

    public enum Source {
        WEB, APP, PWA
    }
}
