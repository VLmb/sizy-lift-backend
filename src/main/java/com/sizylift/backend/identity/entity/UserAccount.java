package com.sizylift.backend.identity.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * SizyLift account. Keycloak owns credentials; the account is identified by the token issuer and subject.
 * Rows are created by {@code UserAccountRepository#insertIfAbsent}, not by {@code persist}.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String issuer;

    @Column(nullable = false, updatable = false)
    private String subject;

    private String email;

    @Column(nullable = false)
    private boolean emailVerified;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
    }

    /** Copies contact data from the identity provider; unchanged data causes no UPDATE. */
    public void updateContact(String email, boolean emailVerified, Instant now) {
        if (Objects.equals(this.email, email) && this.emailVerified == emailVerified) {
            return;
        }
        this.email = email;
        this.emailVerified = emailVerified;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
