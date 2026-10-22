package com.sizylift.backend.notifications.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Регистрация установки приложения. {@code pushToken} — {@code ExponentPushToken[…]};
 * {@code null} — уведомления запрещены или пользователь вышел.
 */
public record DeviceRequest(
        @NotNull @Pattern(regexp = PLATFORM) String platform,
        @Pattern(regexp = PUSH_TOKEN) String pushToken) {

    public static final String PLATFORM = "ios|android";
    public static final String PUSH_TOKEN = "Expo(nent)?PushToken\\[[A-Za-z0-9_-]{1,200}]";
}
