package com.sizylift.backend.admin.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
public class KeycloakDirectory {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_USERS = 10_000;
    private static final long CACHE_SECONDS = 30;
    private final RestClient http = AdminHttp.client();
    private final String url;
    private final String realm;
    private final String clientId;
    private final String secret;
    private List<Person> cached = List.of();
    private Instant expires = Instant.MIN;

    public KeycloakDirectory(@Value("${sizylift.admin.keycloak-url:}") String url,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${sizylift.admin.keycloak-client-id:}") String clientId,
            @Value("${sizylift.admin.keycloak-client-secret:}") String secret) {
        this.url = url;
        this.realm = issuer.substring(issuer.lastIndexOf('/') + 1);
        this.clientId = clientId;
        this.secret = secret;
    }

    public synchronized List<Person> users() {
        if (Instant.now().isBefore(expires)) return cached;
        if (url.isBlank() || clientId.isBlank() || secret.isBlank()) throw new Unavailable();
        try {
            cached = load(token());
            expires = Instant.now().plusSeconds(CACHE_SECONDS);
            return cached;
        } catch (RuntimeException exception) {
            throw new Unavailable();
        }
    }

    private String token() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        var response = http.post().uri(url + "/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        return response.get("access_token").asString();
    }

    private List<Person> load(String token) {
        var users = new ArrayList<Person>();
        for (int offset = 0; offset <= MAX_USERS; offset += PAGE_SIZE) {
            int first = offset;
            var response = http.get().uri(url + "/admin/realms/{realm}/users?first={first}&max={max}&briefRepresentation=false",
                    realm, first, PAGE_SIZE).headers(headers -> headers.setBearerAuth(token))
                    .retrieve().body(JsonNode.class);
            if (response == null || !response.isArray()) throw new Unavailable();
            for (var row : response) users.add(person(row));
            if (users.size() > MAX_USERS) throw new Unavailable();
            if (response.size() < PAGE_SIZE) return List.copyOf(users);
        }
        throw new Unavailable();
    }

    private static Person person(JsonNode row) {
        var name = (row.path("firstName").asString("") + " " + row.path("lastName").asString("")).trim();
        var actions = row.path("requiredActions");
        var state = !row.path("enabled").asBoolean() ? "disabled"
                : !row.path("emailVerified").asBoolean() || actions.size() > 0 ? "pending" : "active";
        var created = row.path("createdTimestamp");
        return new Person(row.path("id").asString(), name, row.path("email").asString(""),
                row.path("emailVerified").asBoolean(), created.isNumber() ? Instant.ofEpochMilli(created.asLong()) : null, state);
    }

    public record Person(String id, String name, String email, boolean emailVerified, Instant registeredAt, String state) {
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public static class Unavailable extends RuntimeException {
        public Unavailable() {
            super("Каталог пользователей временно недоступен");
        }
    }
}
