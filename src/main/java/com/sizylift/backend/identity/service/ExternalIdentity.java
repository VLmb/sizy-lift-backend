package com.sizylift.backend.identity.service;

import java.util.Objects;

/** Личность из проверенного access token; {@code issuer} + {@code subject} — её постоянный ключ. */
public record ExternalIdentity(String issuer, String subject, String email, boolean emailVerified, String displayName) {

    public ExternalIdentity(String issuer, String subject, String email, boolean emailVerified) {
        this(issuer, subject, email, emailVerified, null);
    }

    public ExternalIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
    }
}
