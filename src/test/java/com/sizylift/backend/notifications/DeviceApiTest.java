package com.sizylift.backend.notifications;

import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Регистрация устройств для push (docs/notifications.md). */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final String TOKEN = "ExponentPushToken[cccccccccccccccccccccc]";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void registersUpdatesAndRemovesTheDevice() throws Exception {
        var user = UUID.randomUUID().toString();
        var device = UUID.randomUUID();

        register(user, device, "{\"platform\":\"ios\",\"pushToken\":\"" + TOKEN + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.pushEnabled").value(true))
                .andExpect(jsonPath("$.pushToken").doesNotExist());
        assertThat(token(device)).isEqualTo(TOKEN);

        register(user, device, "{\"platform\":\"ios\",\"pushToken\":null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.pushEnabled").value(false));
        assertThat(token(device)).isNull();

        mvc.perform(delete("/api/v2/devices/" + device).with(token(UUID.randomUUID().toString())))
                .andExpect(status().isNoContent());
        assertThat(count(device)).as("чужое устройство не удаляется").isEqualTo(1);
        mvc.perform(delete("/api/v2/devices/" + device).with(token(user))).andExpect(status().isNoContent());
        assertThat(count(device)).isZero();
    }

    @Test
    void anotherAccountCannotTakeADeviceAndLogoutAllowsTheNextOwner() throws Exception {
        var first = UUID.randomUUID().toString();
        var second = UUID.randomUUID().toString();
        var device = UUID.randomUUID();
        var reinstalled = UUID.randomUUID();
        var body = "{\"platform\":\"android\",\"pushToken\":\"" + TOKEN + "\"}";

        register(first, device, body).andExpect(status().isOk());
        register(second, device, body).andExpect(status().isForbidden());
        assertThat(token(device)).isEqualTo(TOKEN);
        assertThat(jdbc.queryForObject("SELECT u.subject FROM sizylift.device d JOIN sizylift.user_account u "
                + "ON u.id = d.user_id WHERE d.id = ?", String.class, device)).isEqualTo(first);
        mvc.perform(delete("/api/v2/devices/" + device).with(token(first))).andExpect(status().isNoContent());
        register(second, device, body).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT u.subject FROM sizylift.device d JOIN sizylift.user_account u "
                + "ON u.id = d.user_id WHERE d.id = ?", String.class, device)).isEqualTo(second);

        register(second, reinstalled, body).andExpect(status().isOk());
        assertThat(token(device)).as("тот же токен не остаётся на старой установке").isNull();
        assertThat(token(reinstalled)).isEqualTo(TOKEN);
    }

    @Test
    void forbiddenDeviceRegistrationRollsBackTokenReleaseAndCannotDisableTheVictim() throws Exception {
        var owner = UUID.randomUUID().toString();
        var attacker = UUID.randomUUID().toString();
        var victimDevice = UUID.randomUUID();
        var attackerDevice = UUID.randomUUID();
        var attackerToken = "ExponentPushToken[attacker-owned-device]";
        register(owner, victimDevice, "{\"platform\":\"ios\",\"pushToken\":\"" + TOKEN + "\"}")
                .andExpect(status().isOk());
        register(attacker, attackerDevice, "{\"platform\":\"ios\",\"pushToken\":\"" + attackerToken + "\"}")
                .andExpect(status().isOk());
        register(attacker, victimDevice, "{\"platform\":\"ios\",\"pushToken\":\"" + attackerToken + "\"}")
                .andExpect(status().isForbidden());
        register(attacker, victimDevice, "{\"platform\":\"ios\",\"pushToken\":null}")
                .andExpect(status().isForbidden());
        assertThat(token(victimDevice)).isEqualTo(TOKEN);
        assertThat(token(attackerDevice)).isEqualTo(attackerToken);
    }

    @Test
    void rejectsInvalidInputAndAnonymousRequests() throws Exception {
        var user = UUID.randomUUID().toString();
        register(user, UUID.randomUUID(), "{\"platform\":\"web\",\"pushToken\":null}")
                .andExpect(status().isBadRequest());
        register(user, UUID.randomUUID(), "{\"platform\":\"ios\",\"pushToken\":\"not-a-token\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v2/devices/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"ios\"}")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions register(String user, UUID device, String body)
            throws Exception {
        return mvc.perform(put("/api/v2/devices/" + device).with(token(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String token(UUID device) {
        return jdbc.queryForObject("SELECT push_token FROM sizylift.device WHERE id = ?", String.class, device);
    }

    private long count(UUID device) {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift.device WHERE id = ?", Long.class, device);
    }

    private static JwtRequestPostProcessor token(String subject) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@example.com"));
    }
}
