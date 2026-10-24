package com.sizylift.backend.coaching.entity;

import java.time.Instant;
import java.util.UUID;

/** Связь тренера и подопечного; {@code endedAt} заполнен у разорванной. */
public record CoachingLink(UUID id, UUID coachId, UUID athleteId, boolean healthAccess, Instant createdAt,
                           Instant endedAt) {

    public boolean active() {
        return endedAt == null;
    }

    public boolean involves(UUID userId) {
        return coachId.equals(userId) || athleteId.equals(userId);
    }
}
