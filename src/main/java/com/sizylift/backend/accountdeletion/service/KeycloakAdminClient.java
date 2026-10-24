package com.sizylift.backend.accountdeletion.service;

import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sizylift.backend.accountdeletion.config.KeycloakAdminProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Keycloak Admin REST API (https://www.keycloak.org/docs-api/latest/rest-api/, сверено 23.10.2026): токен
 * service account по client credentials, затем {@code DELETE /admin/realms/{realm}/users/{id}}. Удаление
 * завершает сессии и отзывает refresh-токены пользователя. Токен не кэшируется: удаления редки.
 */
public class KeycloakAdminClient {

    private static final String TOKEN_PATH = "/realms/{realm}/protocol/openid-connect/token";
    private static final String USER_PATH = "/admin/realms/{realm}/users/{id}";
    private static final String GRANT_TYPE = "grant_type";
    private static final String CLIENT_CREDENTIALS = "client_credentials";
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";
    private static final String BEARER = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    private final RestClient rest;
    private final KeycloakAdminProperties properties;

    public KeycloakAdminClient(RestClient rest, KeycloakAdminProperties properties) {
        this.rest = rest;
        this.properties = properties;
    }

    /** Удаляет пользователя по {@code sub}; уже удалённый (404) — не ошибка, повтор безопасен. */
    public void deleteUser(String userId) {
        var token = call(this::serviceAccountToken);
        call(() -> {
            try {
                return rest.delete().uri(USER_PATH, properties.realm(), userId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER + token)
                        .retrieve().toBodilessEntity();
            } catch (HttpClientErrorException.NotFound e) {
                return null;
            }
        });
    }

    private String serviceAccountToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add(GRANT_TYPE, CLIENT_CREDENTIALS);
        form.add(CLIENT_ID, properties.clientId());
        form.add(CLIENT_SECRET, properties.clientSecret());
        var response = rest.post().uri(TOKEN_PATH, properties.realm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(TokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw new RestClientException("Keycloak returned no access token");
        }
        return response.accessToken();
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            log.warn("Keycloak Admin API request failed: {}", e.getClass().getSimpleName());
            throw new Unavailable(e);
        }
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    /** Keycloak недоступен или отказал; аккаунт не удалён, клиент повторяет запрос позже. */
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class Unavailable extends RuntimeException {

        Unavailable(RestClientException cause) {
            super("Keycloak Admin API request failed", cause);
        }
    }
}
