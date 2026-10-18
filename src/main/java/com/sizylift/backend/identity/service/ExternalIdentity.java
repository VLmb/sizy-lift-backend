package com.sizylift.backend.identity.service;

import java.util.Objects;

/** Identity confirmed by a validated access token. {@code issuer} + {@code subject} is the stable key. */
public record ExternalIdentity(String issuer, String subject, String email, boolean emailVerified) {

    public ExternalIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
    }
}
