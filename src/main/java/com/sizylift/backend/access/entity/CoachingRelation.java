package com.sizylift.backend.access.entity;

import java.util.UUID;

/** Активная связь глазами политики доступа: кто тренер, кто подопечный и разрешены ли травмы. */
public record CoachingRelation(UUID coachId, UUID athleteId, boolean healthAccess) {
}
