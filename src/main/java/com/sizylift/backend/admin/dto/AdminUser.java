package com.sizylift.backend.admin.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminUser(String id, UUID accountId, String name, String email, boolean emailVerified,
        Instant registeredAt, String state, boolean coachEnabled, Instant firstSeenAt, Instant lastSeenAt,
        long workouts, long programs, long athletes, boolean hasCoach) {
}
