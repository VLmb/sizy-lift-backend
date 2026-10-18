package com.sizylift.backend.identity.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Аккаунт SizyLift. Учётными данными владеет Keycloak; аккаунт определяется парой issuer + subject токена.
 */
public record UserAccount(
        UUID id,
        String issuer,
        String subject,
        String email,
        boolean emailVerified,
        Instant createdAt,
        Instant updatedAt) {
}
