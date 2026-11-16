package com.sizylift.backend.identity.dto;

import java.time.Instant;
import java.util.UUID;

import com.sizylift.backend.identity.entity.UserAccount;

/**
 * accountDeletionAvailable — удаление аккаунта включено на сервере; иначе клиент не предлагает его.
 * videoAvailable — видео включены ({@code sizylift.video.enabled}); иначе клиенты прячут всё, что с ними связано.
 */
public record CurrentUserResponse(UUID id, String email, boolean emailVerified, Instant createdAt,
                                  boolean accountDeletionAvailable, boolean videoAvailable) {

    public static CurrentUserResponse from(UserAccount account, boolean accountDeletionAvailable,
                                           boolean videoAvailable) {
        return new CurrentUserResponse(account.id(), account.email(), account.emailVerified(), account.createdAt(),
                accountDeletionAvailable, videoAvailable);
    }
}
