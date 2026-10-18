package com.sizylift.backend.identity;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.jayway.jsonpath.JsonPath;
import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.identity.entity.UserAccount;
import com.sizylift.backend.identity.service.ExternalIdentity;
import com.sizylift.backend.identity.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account provisioning behind {@code /api/v1/me}. Token validation itself is covered by
 * {@link KeycloakAuthenticationIntegrationTest}; here the authenticated JWT is injected directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CurrentUserApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserAccountService userAccounts;

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
    }

    @Test
    void healthStaysPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void createsAccountOnFirstRequestAndReusesItAfterwards() throws Exception {
        var subject = UUID.randomUUID().toString();

        var first = mvc.perform(get("/api/v1/me").with(token(ISSUER, subject, "lifter@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("lifter@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andReturn().getResponse().getContentAsString();
        var second = mvc.perform(get("/api/v1/me").with(token(ISSUER, subject, "lifter@example.com")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(accountsWithSubject(subject)).isEqualTo(1);
    }

    @Test
    void keepsEmailInSyncWithIdentityProvider() throws Exception {
        var subject = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/me").with(token(ISSUER, subject, "old@example.com"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/me").with(token(ISSUER, subject, "new@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@example.com"));

        assertThat(jdbc.queryForObject(
                "SELECT email FROM sizylift.user_account WHERE subject = ?", String.class, subject))
                .isEqualTo("new@example.com");
    }

    @Test
    void sameSubjectFromAnotherIssuerIsAnotherAccount() throws Exception {
        var subject = UUID.randomUUID().toString();

        var own = mvc.perform(get("/api/v1/me").with(token(ISSUER, subject, "a@example.com")))
                .andReturn().getResponse().getContentAsString();
        var foreign = mvc.perform(get("/api/v1/me").with(token("http://other.test/realms/x", subject, "a@example.com")))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(foreign, "$.id")).isNotEqualTo(JsonPath.read(own, "$.id"));
        assertThat(accountsWithSubject(subject)).isEqualTo(2);
    }

    @Test
    void concurrentFirstRequestsCreateSingleAccount() throws Exception {
        var identity = new ExternalIdentity(ISSUER, UUID.randomUUID().toString(), "race@example.com", true);
        var threads = 8;
        var start = new CountDownLatch(1);
        var calls = new ArrayList<Callable<UserAccount>>();
        for (var i = 0; i < threads; i++) {
            calls.add(() -> {
                start.await();
                return userAccounts.resolve(identity);
            });
        }

        try (var executor = Executors.newFixedThreadPool(threads)) {
            var results = calls.stream().map(executor::submit).toList();
            start.countDown();
            var ids = new ArrayList<UUID>();
            for (var result : results) {
                ids.add(result.get().getId());
            }
            assertThat(ids).hasSize(threads).containsOnly(ids.getFirst());
        }
        assertThat(accountsWithSubject(identity.subject())).isEqualTo(1);
    }

    private static JwtRequestPostProcessor token(String issuer, String subject, String email) {
        return jwt().jwt(token -> token
                .issuer(issuer)
                .subject(subject)
                .claim("email", email)
                .claim("email_verified", true));
    }

    private long accountsWithSubject(String subject) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sizylift.user_account WHERE subject = ?", Long.class, subject);
    }
}
