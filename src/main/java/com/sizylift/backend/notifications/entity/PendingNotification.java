package com.sizylift.backend.notifications.entity;

import java.util.Map;
import java.util.UUID;

/** Строка outbox, взятая на отправку; {@code data} — только тип и id. */
public record PendingNotification(UUID id, UUID userId, NotificationKind kind, Map<String, String> data,
                                  int attempts) {
}
