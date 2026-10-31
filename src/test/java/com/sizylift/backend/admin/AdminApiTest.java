package com.sizylift.backend.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.admin.service.KeycloakDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminApiTest extends PostgresIntegrationTest {
    private static final String ISSUER = "http://keycloak.test/realms/sizylift";
    private static final String ROOT = "/api/v2/admin";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KeycloakDirectory directory;

    @Test
    void everyAdministrativeReadRejectsOrdinaryUsersAndRolesFromAnotherClient() throws Exception {
        for (var path : List.of("/access", "/overview", "/users", "/users/id", "/metrics", "/containers", "/containers/id/logs")) {
            mvc.perform(get(ROOT + path).with(jwt())).andExpect(status().isForbidden());
            mvc.perform(get(ROOT + path).with(jwt().jwt(token -> token.claim("azp", "sizylift-web")
                    .claim("realm_access", Map.of("roles", List.of("sizy-admin")))))).andExpect(status().isForbidden());
            mvc.perform(get(ROOT + path).with(jwt().jwt(token -> token.claim("azp", "sizy-admin")
                    .claim("roles", List.of("sizy-admin"))))).andExpect(status().isForbidden());
        }
    }

    @Test
    void joinsIdentityByIssuerAndSubjectAndPaginatesAfterFiltering() throws Exception {
        var subject = UUID.randomUUID().toString();
        var unopened = UUID.randomUUID().toString();
        var account = UUID.randomUUID();
        jdbc.update("INSERT INTO sizylift.user_account (id, issuer, subject, email, coach_enabled) VALUES (?, ?, ?, ?, true)",
                account, ISSUER, subject, "old-contact@example.test");
        // Одинаковый sub другого issuer не должен попасть в административную карточку.
        jdbc.update("INSERT INTO sizylift.user_account (issuer, subject, email) VALUES (?, ?, ?)",
                "http://foreign.test/realms/other", subject, "foreign@example.test");
        when(directory.users()).thenReturn(List.of(
                new KeycloakDirectory.Person(subject, "Тренер", "current@example.test", true, Instant.now(), "active"),
                new KeycloakDirectory.Person(unopened, "Новый", "new@example.test", false, Instant.now(), "pending")));

        mvc.perform(get(ROOT + "/users").param("coach", "true").param("size", "1").with(admin()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].accountId").value(account.toString()))
                .andExpect(jsonPath("$.items[0].email").value("current@example.test"))
                .andExpect(jsonPath("$.items[0].lastSeenAt").isEmpty());
        mvc.perform(get(ROOT + "/users/" + unopened).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").isEmpty())
                .andExpect(jsonPath("$.firstSeenAt").isEmpty()).andExpect(jsonPath("$.state").value("pending"));
        mvc.perform(get(ROOT + "/overview").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(2)).andExpect(jsonPath("$.started").value(1));
        mvc.perform(get(ROOT + "/users").param("state", "pending").param("page", "1").param("size", "1").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void rejectsInvalidBoundsAndNeverAcceptsArbitraryPromqlOrDockerPaths() throws Exception {
        for (var parameter : List.of("size", "page")) {
            mvc.perform(get(ROOT + "/users").param(parameter, "-1").with(admin())).andExpect(status().isBadRequest());
        }
        mvc.perform(get(ROOT + "/users").param("sort", "email; DROP TABLE").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/metrics").param("period", "100d").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/metrics").param("period", "7d").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/containers/not-a-container/logs").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/users").with(admin())).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void activityBelongsToCallerAndIsNotAnAccountContactUpdate() throws Exception {
        var subject = UUID.randomUUID().toString();
        var caller = jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", "activity@example.test"));
        mvc.perform(get("/api/v1/me").with(caller)).andExpect(status().isOk());
        var first = jdbc.queryForMap("SELECT last_seen_at, updated_at FROM sizylift.user_account WHERE issuer = ? AND subject = ?", ISSUER, subject);
        assertThat(first.get("last_seen_at")).isNotNull();
        mvc.perform(get("/api/v1/me").with(caller)).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT last_seen_at, updated_at FROM sizylift.user_account WHERE issuer = ? AND subject = ?", ISSUER, subject)).isEqualTo(first);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(token -> token.issuer(ISSUER).subject("admin-owner").claim("azp", "sizy-admin")
                .claim("realm_access", Map.of("roles", List.of("sizy-admin"))));
    }
}
