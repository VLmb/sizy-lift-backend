package com.sizylift.backend.security;

import java.util.UUID;
import com.sizylift.backend.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Граница безопасности проверяется для всех зарегистрированных API, включая новые контроллеры. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityBoundaryApiTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping routes;

    @Test
    void everyApiRouteRequiresBearerBeforeParsingItsInput() throws Exception {
        var checked = 0;
        for (var mapping : routes.getHandlerMethods().keySet()) {
            for (var path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/")) continue;
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    var concrete = path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                    var result = mvc.perform(request(HttpMethod.valueOf(method.name()), concrete)
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andExpect(status().isUnauthorized())
                            .andExpect(header().doesNotExist("Set-Cookie"))
                            .andReturn();
                    assertThat(result.getRequest().getSession(false)).as("%s %s", method, path).isNull();
                    checked++;
                }
            }
        }
        assertThat(checked).as("инвентаризация контроллеров не пуста").isGreaterThan(25);
    }

    @Test
    void unknownRoutesAndFrameworkLoginLogoutDoNotOpenAnotherAuthenticationFlow() throws Exception {
        for (var path : new String[]{"/api/v3/future", "/actuator/env", "/actuator/metrics", "/login", "/logout", "/error"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        mvc.perform(get("/actuator/env").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void cookiesQueryAndFormTokensCannotAuthenticate() throws Exception {
        mvc.perform(get("/api/v1/me").cookie(new Cookie("access_token", "forged"),
                        new Cookie("JSESSIONID", "forged"), new Cookie("KEYCLOAK_SESSION", "forged")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").param("access_token", "forged")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v2/sync/push").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("access_token=forged")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ignoresAnAuthenticatedHttpSessionAndDoesNotRememberThePreviousBearer() throws Exception {
        var token = Jwt.withTokenValue("session-token").header("alg", "RS256")
                .issuer("http://keycloak.test/realms/sizylift").subject(UUID.randomUUID().toString()).build();
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new JwtAuthenticationToken(token)));
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
        var valid = mvc.perform(get("/api/v1/me").with(jwt().jwt(j -> j.issuer(token.getIssuer().toString())
                        .subject(token.getSubject()))))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        assertThat(valid.getRequest().getSession(false)).isNull();
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void corsDoesNotTrustArbitraryNullOriginsOrCredentials() throws Exception {
        for (var origin : new String[]{"https://evil.example", "null", "http://web.test.evil.example"}) {
            mvc.perform(options("/api/v2/sync/push").header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
        mvc.perform(options("/api/v2/sync/push").header("Origin", "http://web.test")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://web.test"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void rejectsOversizedJsonBeforeCreatingAnAccountOrParsingTheAggregate() throws Exception {
        mvc.perform(post("/api/v1/publication/batch").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(new byte[ApiBodyLimitFilter.PUBLICATION_BYTES + 1]))
                .andExpect(status().is(413)).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/v2/sync/push").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(new byte[ApiBodyLimitFilter.API_BYTES + 1]))
                .andExpect(status().is(413));
        mvc.perform(post("/api/v2/sync/push").contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[ApiBodyLimitFilter.API_BYTES + 1]))
                .andExpect(status().isUnauthorized());
    }
}
