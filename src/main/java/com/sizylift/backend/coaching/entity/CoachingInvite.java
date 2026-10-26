package com.sizylift.backend.coaching.entity;

import java.time.Instant;
import java.util.UUID;

/** Приглашение тренера; сам токен не хранится, только его хеш. */
public record CoachingInvite(UUID id, UUID coachId, Instant expiresAt, Instant acceptedAt, Instant revokedAt) {

    public boolean usableAt(Instant now) {
        return revokedAt == null && acceptedAt == null && now.isBefore(expiresAt);
    }
}
