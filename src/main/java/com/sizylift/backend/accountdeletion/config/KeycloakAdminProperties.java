package com.sizylift.backend.accountdeletion.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Доступ к Keycloak Admin API для удаления пользователя (docs/authentication.md, «Удаление аккаунта»).
 * В production {@code url} — {@code KEYCLOAK_ADMIN_URL}, {@code clientSecret} — {@code KEYCLOAK_BACKEND_CLIENT_SECRET}.
 *
 * @param url          внутренний адрес Keycloak без пути realm, например {@code http://keycloak:8080}
 * @param realm        realm пользователей приложения
 * @param clientId     конфиденциальный клиент с service account и ролью {@code manage-users}
 * @param clientSecret секрет этого клиента
 */
@ConfigurationProperties("sizylift.keycloak-admin")
public record KeycloakAdminProperties(URI url, String realm, String clientId, String clientSecret,
                                      Duration connectTimeout, Duration readTimeout) {
}
