package com.sizylift.backend.applinks;

import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Страница ссылки приглашения и файлы {@code /.well-known} (docs/coaching.md): публичные, без токена. */
@SpringBootTest(properties = {
    "sizylift.app-links.apple-team-id=ABCDE12345",
    "sizylift.app-links.android-cert-fingerprints=AA:BB:CC, DD:EE:FF",
    "sizylift.app-links.app-store-url=https://apps.apple.com/app/id1234567890"})
@AutoConfigureMockMvc
class AppLinksApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void appleAssociationOpensOnlyInviteLinksInTheApp() throws Exception {
        mvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.applinks.details[0].appIDs[0]").value("ABCDE12345.com.sizylift.app"))
                .andExpect(jsonPath("$.applinks.details[0].components[0]['/']").value("/invite/*"))
                .andExpect(jsonPath("$.applinks.details.length()").value(1));
    }

    @Test
    void androidAssetLinksNameThePackageAndEveryFingerprint() throws Exception {
        mvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
                .andExpect(jsonPath("$[0].target.namespace").value("android_app"))
                .andExpect(jsonPath("$[0].target.package_name").value("com.sizylift.app"))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[1]").value("DD:EE:FF"));
    }

    @Test
    void invitePageOpensTheAppAndShowsTheCodeWithoutLeakingTheToken() throws Exception {
        var token = invite(UUID.randomUUID().toString());

        mvc.perform(get("/invite/" + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("href=\"sizylift://invite/" + token + "\"")))
                .andExpect(content().string(containsString("<code>" + token + "</code>")))
                .andExpect(content().string(containsString("https://apps.apple.com/app/id1234567890")))
                .andExpect(content().string(not(containsString("Google Play"))))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Robots-Tag", containsString("noindex")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
    }

    @Test
    void usedRevokedOrUnknownInviteShowsThatTheLinkIsGone() throws Exception {
        var coach = UUID.randomUUID().toString();
        var token = invite(coach);
        var id = json.readTree(mvc.perform(get("/api/v2/coaching/invites").with(token(coach)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asString();
        mvc.perform(delete("/api/v2/coaching/invites/" + id).with(token(coach))).andExpect(status().isNoContent());

        mvc.perform(get("/invite/" + token))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Приглашение недействительно")))
                .andExpect(content().string(not(containsString(token))));
        mvc.perform(get("/invite/AAAAAAAAAAAAAAAAAAAAAA")).andExpect(status().isNotFound());
        mvc.perform(get("/invite/not-a-token")).andExpect(status().isNotFound());
    }

    @Test
    void onlyTheInvitePagesAreOpenWithoutAToken() throws Exception {
        mvc.perform(get("/invite")).andExpect(status().isUnauthorized());
        mvc.perform(get("/invite/a/b")).andExpect(status().isUnauthorized());
        mvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v2/coaching/invites")).andExpect(status().isUnauthorized());
    }

    private String invite(String subject) throws Exception {
        mvc.perform(put("/api/v2/coaching/coach-mode").with(token(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        return json.readTree(mvc.perform(post("/api/v2/coaching/invites").with(token(subject)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("token").asString();
    }

    private static JwtRequestPostProcessor token(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject));
    }
}
