package com.sizylift.backend.admin;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.admin.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminSampleDataApiTest extends PostgresIntegrationTest {
    private static final String ISSUER = "http://keycloak.test/realms/sizylift";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AdminSampleDataService samples;
    @Autowired AdminSampleScenario scenario;
    @MockitoBean KeycloakDirectory directory;

    @Test
    void scenarioIsAlwaysInWeekFourAndNeverCompletesTodayOrFutureWorkouts() {
        var owner = UUID.randomUUID();
        var monday = LocalDate.of(2026, 10, 26);
        for (int day = 0; day < 14; day++) {
            var today = monday.plusDays(day);
            var dataset = scenario.build(owner, today, Instant.now());
            assertThat(dataset.currentStart().plusWeeks(3))
                    .isEqualTo(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            int beforeToday = (today.getDayOfWeek().getValue()) / 2;
            assertThat(dataset.workouts()).isEqualTo(20 + beforeToday);
            for (var item : dataset.items()) {
                if (item.type().equals("workout"))
                    assertThat(LocalDate.parse(item.payload().get("workoutDate").asText())).isBefore(today);
            }
        }
    }

    @Test
    void administrativeWriteRequiresRoleAndDedicatedClient() throws Exception {
        var path = path("anything");
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().jwt(token -> token.claim("azp", "sizylift-web")
                .claim("realm_access", Map.of("roles", List.of("sizy-admin"))))))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().jwt(token -> token.claim("azp", "sizy-admin"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsWholeScenarioAndFeedsWithoutReplacingExistingUserData() throws Exception {
        var owner = account();
        var coach = UUID.randomUUID();
        jdbc.update("INSERT INTO sizylift.user_account (id, issuer, subject, coach_enabled) VALUES (?, ?, ?, true)", coach, ISSUER, coach.toString());
        jdbc.update("INSERT INTO sizylift.coaching_link (coach_user_id, athlete_user_id) VALUES (?, ?)", coach, owner);
        var kept = UUID.randomUUID();
        jdbc.update("INSERT INTO sizylift.program (user_id, id, name, status, updated_at) VALUES (?, ?, 'Свой шаблон', 'draft', now())", owner, kept);
        var result = mvc.perform(post(path(owner.toString())).with(admin()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.alreadyGenerated").value(false))
                .andExpect(jsonPath("$.plannedWorkouts").value(30))
                .andExpect(jsonPath("$.currentWeek").value(4)).andReturn();
        var response = json.readTree(result.getResponse().getContentAsString());
        assertThat(count("program", owner)).isEqualTo(3);
        assertThat(count("planned_workout", owner)).isEqualTo(30);
        assertThat(count("workout", owner)).isEqualTo(response.get("completedWorkouts").asInt());
        assertThat(count("workout_reschedule", owner)).isEqualTo(1);
        assertThat(count("admin_sample_data", owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT min(number) FROM sizylift.training_week WHERE user_id = ?", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.workout_set WHERE user_id = ? AND completion_status = 'skipped'", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.workout WHERE user_id = ? AND workout_date >= ?", Integer.class,
                owner, LocalDate.now(AdminSampleScenario.ZONE))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.published_aggregate WHERE user_id = ? AND author_user_id IS NOT NULL", Integer.class, owner)).isZero();
        assertThat(count("notification_outbox", coach)).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM sizylift.program WHERE user_id = ? AND id = ?", String.class, owner, kept)).isEqualTo("Свой шаблон");
        var revision = jdbc.queryForObject("SELECT max(revision) FROM sizylift.published_aggregate WHERE user_id = ?", Long.class, owner);
        mvc.perform(post(path(owner.toString())).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyGenerated").value(true))
                .andExpect(jsonPath("$.currentProgramId").value(response.get("currentProgramId").asText()));
        assertThat(jdbc.queryForObject("SELECT max(revision) FROM sizylift.published_aggregate WHERE user_id = ?", Long.class, owner)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT email FROM sizylift.user_account WHERE id = ?", String.class, owner)).isEqualTo("kept@example.test");
        mvc.perform(get("/api/v2/web/timeline").param("from", response.get("currentStart").asText())
                .param("to", LocalDate.parse(response.get("currentStart").asText()).plusDays(41).toString())
                .with(jwt().jwt(token -> token.issuer(ISSUER).subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planned.length()").value(18));
        mvc.perform(get("/api/v2/sync/changes").with(jwt().jwt(token -> token.issuer(ISSUER).subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changes").isNotEmpty());
    }

    @Test
    void collisionRollsBackEarlierProgramsAndDoesNotTouchExistingAggregate() throws Exception {
        var owner = account();
        var dataset = scenario.build(owner, LocalDate.now(AdminSampleScenario.ZONE), Instant.now());
        var collision = dataset.items().get(2);
        jdbc.update("INSERT INTO sizylift.published_aggregate (user_id, type, aggregate_id, version, deleted) VALUES (?, ?, ?, 1, true)",
                owner, collision.type(), collision.id());
        mvc.perform(post(path(owner.toString())).with(admin())).andExpect(status().isConflict());
        assertThat(count("program", owner)).isZero();
        assertThat(count("workout", owner)).isZero();
        assertThat(count("admin_sample_data", owner)).isZero();
    }

    @Test
    void databaseFailureAfterProgramWritesRollsBackTheEntireScenario() throws Exception {
        var owner = account();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION sizylift.reject_sample_workout() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.user_id = '%s'::uuid THEN RAISE EXCEPTION 'test failure' USING ERRCODE = '23514'; END IF;
                    RETURN NEW;
                END $$
                """.formatted(owner));
        jdbc.execute("CREATE TRIGGER sample_failure BEFORE INSERT ON sizylift.workout FOR EACH ROW EXECUTE FUNCTION sizylift.reject_sample_workout()");
        try {
            mvc.perform(post(path(owner.toString())).with(admin())).andExpect(status().isConflict());
            assertThat(count("program", owner)).isZero();
            assertThat(count("workout", owner)).isZero();
            assertThat(count("published_aggregate", owner)).isZero();
            assertThat(count("admin_sample_data", owner)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER sample_failure ON sizylift.workout");
            jdbc.execute("DROP FUNCTION sizylift.reject_sample_workout()");
        }
    }

    @Test
    void concurrentClicksCreateOnlyOneDataset() throws Exception {
        var owner = account();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> samples.generate(owner.toString(), "admin-a"));
            var second = executor.submit(() -> samples.generate(owner.toString(), "admin-b"));
            var results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(AdminSampleDataService.Result::alreadyGenerated).count()).isEqualTo(1);
        }
        assertThat(count("program", owner)).isEqualTo(2);
    }

    @Test
    void unopenedAndDisabledAccountsAreNotSilentlyCreated() throws Exception {
        var subject = UUID.randomUUID().toString();
        when(directory.users()).thenReturn(List.of(new KeycloakDirectory.Person(subject, "Новый", "new@example.test",
                true, Instant.now(), "active")));
        mvc.perform(post(path(subject)).with(admin())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.user_account WHERE subject = ?", Integer.class, subject)).isZero();
        var owner = account();
        when(directory.users()).thenReturn(List.of(new KeycloakDirectory.Person(owner.toString(), "Отключён", "new@example.test",
                true, Instant.now(), "disabled")));
        mvc.perform(post(path(owner.toString())).with(admin())).andExpect(status().isConflict());
        assertThat(count("program", owner)).isZero();
        mvc.perform(post(path("missing")).with(admin())).andExpect(status().isNotFound());
    }

    private UUID account() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO sizylift.user_account (id, issuer, subject, email) VALUES (?, ?, ?, ?)",
                id, ISSUER, id.toString(), "kept@example.test");
        when(directory.users()).thenReturn(List.of(new KeycloakDirectory.Person(id.toString(), "Тестовый пользователь",
                "current@example.test", true, Instant.now(), "active")));
        return id;
    }

    private int count(String table, UUID owner) {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift." + table + " WHERE user_id = ?", Integer.class, owner);
    }

    private String path(String subject) { return "/api/v2/admin/users/" + subject + "/sample-data"; }
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor admin() {
        return jwt().jwt(token -> token.issuer(ISSUER).subject("admin-owner").claim("azp", "sizy-admin")
                .claim("realm_access", Map.of("roles", List.of("sizy-admin"))));
    }
}
