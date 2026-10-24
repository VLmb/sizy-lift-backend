package com.sizylift.backend.imports.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Сессия импорта: нормализованный вход и раунды диалога с моделью. */
public record ImportSession(UUID id, UUID userId, UUID targetOwnerId, String input, LocalDate startDate,
                            ImportStatus status, List<Round> rounds, Instant expiresAt) {
}
