package com.sizylift.backend.web.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Веб-версия. {@code allowedOrigins} — адреса, с которых браузеру разрешено обращаться к API (CORS). Пусто —
 * CORS выключен: в production веб отдаётся с того же домена, что и API (docs/web.md).
 */
@ConfigurationProperties("sizylift.web")
public record WebProperties(List<String> allowedOrigins) {

    public WebProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
