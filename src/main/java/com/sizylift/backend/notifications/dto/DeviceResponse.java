package com.sizylift.backend.notifications.dto;

import java.util.UUID;

/** Токен в ответ не возвращается: клиенту достаточно знать, что он сохранён. */
public record DeviceResponse(UUID id, String platform, boolean pushEnabled) {
}
