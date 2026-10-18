package com.sizylift.backend.identity.dto;

import java.time.Instant;
import java.util.UUID;

import com.sizylift.backend.identity.entity.UserAccount;

public record CurrentUserResponse(UUID id, String email, boolean emailVerified, Instant createdAt) {

    public static CurrentUserResponse from(UserAccount account) {
        return new CurrentUserResponse(account.id(), account.email(), account.emailVerified(), account.createdAt());
    }
}
