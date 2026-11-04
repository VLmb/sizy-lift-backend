package com.sizylift.backend.identity.dto;

import java.time.Instant;
import java.util.UUID;

import com.sizylift.backend.identity.entity.UserAccount;

/** accountDeletionAvailable — удаление аккаунта включено на сервере; иначе клиент не предлагает его. */
public record CurrentUserResponse(UUID id, String email, boolean emailVerified, Instant createdAt,
                                  boolean accountDeletionAvailable) {

    public static CurrentUserResponse from(UserAccount account, boolean accountDeletionAvailable) {
        return new CurrentUserResponse(account.id(), account.email(), account.emailVerified(), account.createdAt(),
                accountDeletionAvailable);
    }
}
