package com.sizylift.backend.notifications.entity;

import java.util.UUID;

/** Устройство пользователя с push-токеном. */
public record PushTarget(UUID userId, UUID deviceId, String pushToken) {
}
