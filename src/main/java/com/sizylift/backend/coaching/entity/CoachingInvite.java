package com.sizylift.backend.coaching.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Приглашение тренера; сам токен не хранится, только его хеш. {@code programId} — необязательная своя программа
 * тренера: её состав виден до входа, а после принятия у подопечного появляется её копия-черновик (CR-02).
 */
public record CoachingInvite(UUID id, UUID coachId, Instant expiresAt, Instant acceptedAt, Instant revokedAt,
                             UUID programId) {

    public boolean usableAt(Instant now) {
        return revokedAt == null && acceptedAt == null && now.isBefore(expiresAt);
    }
}
