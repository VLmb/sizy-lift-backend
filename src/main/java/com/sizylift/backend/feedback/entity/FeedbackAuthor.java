package com.sizylift.backend.feedback.entity;

import java.util.UUID;

public record FeedbackAuthor(UUID accountId, String email, String username, String name) {
    public static final FeedbackAuthor ANONYMOUS = new FeedbackAuthor(null, null, null, null);
}
