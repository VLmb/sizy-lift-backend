package com.sizylift.backend.notifications.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Push-уведомления (ADR-0009). {@code enabled = false} в dev и тестах: строки копятся в outbox, Expo не
 * вызывается. В production {@code expo.access-token} приходит из {@code EXPO_ACCESS_TOKEN}.
 */
@ConfigurationProperties("sizylift.notifications")
public record NotificationProperties(boolean enabled, Expo expo) {

    /**
     * {@code url} — базовый адрес Push API Expo; {@code accessToken} — токен «enhanced push security», пустой —
     * без заголовка Authorization; таймауты ограничивают ожидание одного запроса.
     */
    public record Expo(URI url, String accessToken, Duration connectTimeout, Duration readTimeout) {
    }
}
