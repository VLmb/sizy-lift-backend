package com.sizylift.backend.identity.deletion;

import java.net.URI;
import com.sizylift.backend.identity.entity.UserAccount;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.JsonNode;

/** Удаляет только sub текущего аккаунта через отдельный сервисный клиент Keycloak. */
@Component
public class KeycloakAccountDeletion {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final boolean enabled;
    private final String issuer;
    private final String clientId;
    private final String secret;
    private final RestClient client;
    private final URI adminUrl;

    public KeycloakAccountDeletion(@Value("${sizylift.account-deletion.enabled:false}") boolean enabled,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${sizylift.account-deletion.admin-url:}") String adminUrl,
            @Value("${sizylift.account-deletion.client-id:}") String clientId,
            @Value("${sizylift.account-deletion.client-secret:}") String secret) {
        this.enabled = enabled;
        this.issuer = issuer;
        this.adminUrl = adminUrl.isBlank() ? null : URI.create(adminUrl);
        this.clientId = clientId;
        this.secret = secret;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        client = RestClient.builder().requestFactory(factory).build();
    }

    public void requireConfigured() {
        if (!enabled || adminUrl == null || clientId.isBlank() || secret.isBlank()) {
            throw new Unavailable();
        }
    }

    public void delete(AccountDeletionRepository.Pending job) {
        requireConfigured();
        if (!issuer.equals(job.issuer())) {
            throw new Unavailable();
        }
        var realm = URI.create(issuer).getPath().replaceFirst(".*/realms/", "");
        var accessToken = accessToken(realm);
        try {
            client.delete().uri(adminUrl + "/admin/realms/{realm}/users/{subject}", realm, job.subject())
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != HttpStatus.NOT_FOUND.value()) {
                throw new Unavailable();
            }
        }
    }


    public void preflight(UserAccount account) {
        requireConfigured();
        if (!issuer.equals(account.issuer())) throw new Unavailable();
        var realm = URI.create(issuer).getPath().replaceFirst(".*/realms/", "");
        try {
            var token = accessToken(realm);
            client.get().uri(adminUrl + "/admin/realms/{realm}/users/{subject}", realm, account.subject())
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            throw new Unavailable();
        }
    }

    private String accessToken(String realm) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        var token = client.post().uri(adminUrl + "/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        return token.get("access_token").asString();
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class Unavailable extends RuntimeException {
    }
}
