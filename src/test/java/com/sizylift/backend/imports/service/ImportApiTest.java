package com.sizylift.backend.imports.service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.imports.model.DeepSeekParserModel;
import com.sizylift.backend.imports.model.ProgramParserModel;
import org.dhatim.fastexcel.Workbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ИИ-импорт через API с заглушкой DeepSeek (MockRestServiceServer): реальных вызовов модели в CI нет
 * (ADR-0008, раздел 6). Проверяется и запрос к модели: формат, JSON-режим, отсутствие данных пользователя.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final String COMPLETIONS = "https://model.test/chat/completions";
    static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");
    static final String TEXT = "Присед 5x5 75%, неделя 1, понедельник";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MockRestServiceServer model;

    String subject;

    @BeforeEach
    void newUser() {
        subject = UUID.randomUUID().toString();
        model.reset();
    }

    @Test
    void questionsThenAnswersThenDraft() throws Exception {
        model.expect(once(), requestTo(COMPLETIONS))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(containsString("Присед со штангой")))
                .andExpect(jsonPath("$.messages[1].content").value(containsString(TEXT)))
                .andExpect(content().string(not(containsString(subject))))
                .andRespond(reply(questions(), 1000, 100));
        model.expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[2].role").value("assistant"))
                .andExpect(jsonPath("$.messages[3].content").value(containsString("\"optionId\":\"a\"")))
                .andRespond(reply(squatProgram(Map.of("percentOf1rm", 75)), 1200, 400));

        var first = start(Map.of("text", TEXT, "startDate", "2026-11-02")).andExpect(status().isOk());
        var started = body(first);
        assertThat(started.get("status").asString()).isEqualTo("questions");
        assertThat(started.get("questions").get(0).get("options").size()).isEqualTo(2);
        var id = started.get("id").asString();

        var answered = body(answers(id, "[{\"questionId\":\"q1\",\"optionId\":\"a\"}]").andExpect(status().isOk()));

        model.verify();
        assertThat(answered.get("status").asString()).isEqualTo("draft");
        assertThat(answered.get("round").asInt()).isEqualTo(2);
        var program = answered.get("draft").get("program");
        assertThat(answered.get("draft").get("programId").asString()).isNotBlank();
        assertThat(program.get("status").asString()).isEqualTo("draft");
        assertThat(program.get("startDate").asString()).isEqualTo("2026-11-02");
        var exercise = firstExercise(program);
        assertThat(exercise.get("exerciseId").asString()).isEqualTo(SQUAT.toString());
        assertThat(exercise.get("sets").size()).isEqualTo(5);
        assertThat(codes(answered)).contains("no_one_rep_max");
        var session = jdbc.queryForMap("SELECT status, tokens_in, tokens_out, draft IS NOT NULL AS has_draft "
                + "FROM sizylift.import_session WHERE id = ?::uuid", id);
        assertThat(session).containsEntry("status", "draft").containsEntry("tokens_in", 2200)
                .containsEntry("tokens_out", 500).containsEntry("has_draft", true);

        answers(id, "[{\"questionId\":\"q1\",\"optionId\":\"a\"}]").andExpect(status().isConflict());
    }

    @Test
    void percentUsesTheOneRepMaxOfTheProgramOwner() throws Exception {
        jdbc.update("INSERT INTO sizylift.one_rep_max (user_id, exercise_id, weight_grams, achieved_on, updated_at) "
                + "VALUES (?, ?, 180000, '2026-10-01', now())", userId(subject), SQUAT);
        model.expect(once(), requestTo(COMPLETIONS))
                .andExpect(content().string(not(containsString("180"))))
                .andRespond(reply(squatProgram(Map.of("percentOf1rm", 75)), 10, 10));

        var draft = body(start(Map.of("text", TEXT)).andExpect(status().isOk()));

        assertThat(firstExercise(draft.get("draft").get("program")).get("sets").get(0).get("targetWeightGrams")
                .asInt()).isEqualTo(135_000);
        assertThat(codes(draft)).doesNotContain("no_one_rep_max");
    }

    @Test
    void invalidReplyIsRetriedOnceWithTheErrors() throws Exception {
        model.expect(once(), requestTo(COMPLETIONS)).andRespond(reply("{\"program\": {\"weeks\": []}}", 10, 10));
        model.expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[3].content").value(containsString("не прошёл проверку")))
                .andRespond(reply(squatProgram(Map.of("rpe", 8)), 10, 10));

        var draft = body(start(Map.of("text", TEXT)).andExpect(status().isOk()));

        model.verify();
        assertThat(draft.get("status").asString()).isEqualTo("draft");
    }

    @Test
    void replyInvalidTwiceIsAClearErrorAndTheSessionFails() throws Exception {
        model.expect(times(2), requestTo(COMPLETIONS)).andRespond(reply("не json", 10, 10));

        var error = body(start(Map.of("text", TEXT)).andExpect(status().isUnprocessableContent()));

        assertThat(error.get("error").asString()).isEqualTo("model_output_invalid");
        assertThat(lastSessionStatus()).isEqualTo("failed");
    }

    @Test
    void providerFailureIsRetriedOnceThenUnavailable() throws Exception {
        model.expect(times(2), requestTo(COMPLETIONS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var error = body(start(Map.of("text", TEXT)).andExpect(status().isServiceUnavailable()));

        model.verify();
        assertThat(error.get("error").asString()).isEqualTo("import_unavailable");
    }

    @Test
    void wrongKeyIsNotRetried() throws Exception {
        model.expect(once(), requestTo(COMPLETIONS)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        start(Map.of("text", TEXT)).andExpect(status().isServiceUnavailable());

        model.verify();
    }

    @Test
    void afterThreeQuestionRoundsTheModelMustReturnAProgram() throws Exception {
        for (int i = 0; i < 3; i++) {
            model.expect(once(), requestTo(COMPLETIONS))
                    .andExpect(content().string(not(containsString("Больше вопросов не задавай"))))
                    .andRespond(reply(questions(), 10, 10));
        }
        model.expect(once(), requestTo(COMPLETIONS))
                .andExpect(content().string(containsString("Больше вопросов не задавай")))
                .andRespond(reply(squatProgram(Map.of()), 10, 10));

        var id = body(start(Map.of("text", TEXT))).get("id").asString();
        answers(id, "[{\"questionId\":\"q1\",\"optionId\":\"a\"}]").andExpect(status().isOk());
        var third = body(answers(id, "[{\"questionId\":\"q1\",\"text\":\"своё\"}]"));
        assertThat(third.get("status").asString()).isEqualTo("questions");
        var last = body(answers(id, "[{\"questionId\":\"q1\",\"optionId\":\"b\"}]").andExpect(status().isOk()));

        model.verify();
        assertThat(last.get("status").asString()).isEqualTo("draft");
    }

    @Test
    void dailyLimitIsChecked() throws Exception {
        var user = userId(subject);
        for (int i = 0; i < 20; i++) {
            jdbc.update("INSERT INTO sizylift.import_session (user_id, target_owner_id, source, input, start_date, "
                    + "status, expires_at) VALUES (?, ?, 'text', 'x', current_date, 'draft', now() + interval '1 day')",
                    user, user);
        }

        var error = body(start(Map.of("text", TEXT)).andExpect(status().isTooManyRequests()));

        assertThat(error.get("error").asString()).isEqualTo("daily_limit");
        model.verify();
    }

    @Test
    void coachImportsForAnActiveAthleteOnly() throws Exception {
        var athlete = UUID.randomUUID().toString();
        var athleteId = userId(athlete);
        var coachId = userId(subject);
        jdbc.update("INSERT INTO sizylift.one_rep_max (user_id, exercise_id, weight_grams, achieved_on, updated_at) "
                + "VALUES (?, ?, 100000, '2026-10-01', now())", athleteId, SQUAT);

        var forbidden = body(start(Map.of("text", TEXT, "targetOwnerId", athleteId.toString()))
                .andExpect(status().isForbidden()));
        assertThat(forbidden.get("error").asString()).isEqualTo("forbidden");

        jdbc.update("INSERT INTO sizylift.coaching_link (coach_user_id, athlete_user_id) VALUES (?, ?)", coachId,
                athleteId);
        model.expect(once(), requestTo(COMPLETIONS)).andRespond(reply(squatProgram(Map.of("percentOf1rm", 80)),
                10, 10));
        var draft = body(start(Map.of("text", TEXT, "targetOwnerId", athleteId.toString()))
                .andExpect(status().isOk()));

        assertThat(firstExercise(draft.get("draft").get("program")).get("sets").get(0).get("targetWeightGrams")
                .asInt()).as("1ПМ подопечного").isEqualTo(80_000);
    }

    @Test
    void sessionsAreVisibleOnlyToTheirAuthorAndExpire() throws Exception {
        model.expect(once(), requestTo(COMPLETIONS)).andRespond(reply(questions(), 10, 10));
        var id = body(start(Map.of("text", TEXT))).get("id").asString();

        var owner = subject;
        subject = UUID.randomUUID().toString();
        answers(id, "[{\"questionId\":\"q1\",\"optionId\":\"a\"}]").andExpect(status().isNotFound());

        subject = owner;
        jdbc.update("UPDATE sizylift.import_session SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid",
                id);
        answers(id, "[{\"questionId\":\"q1\",\"optionId\":\"a\"}]").andExpect(status().isGone());
    }

    @Test
    void inputIsValidatedBeforeCallingTheModel() throws Exception {
        start(Map.of("text", TEXT, "url", "https://docs.google.com/spreadsheets/d/1AbCdEfGhIjKlMnOp"))
                .andExpect(status().isBadRequest());
        assertThat(body(start(Map.of("url", "https://169.254.169.254/latest")).andExpect(status().isBadRequest()))
                .get("error").asString()).isEqualTo("invalid_sheet_url");
        assertThat(body(start(Map.of("text", "x".repeat(20001))).andExpect(status().isBadRequest()))
                .get("error").asString()).isEqualTo("input_too_long");
        assertThat(body(start(Map.of("text", "   "))).get("error").asString()).isEqualTo("empty_input");
        mvc.perform(post("/api/v2/imports").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        model.verify();
    }

    @Test
    void xlsxUploadBecomesAGridForTheModel() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var workbook = new Workbook(bytes, "test", "1.0")) {
            var sheet = workbook.newWorksheet("Неделя 1");
            sheet.value(0, 0, "Присед");
            sheet.value(0, 1, "5x5");
        }
        model.expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[1].content")
                        .value(containsString("Лист: Неделя 1\nA1: Присед\nB1: 5x5")))
                .andRespond(reply(squatProgram(Map.of()), 10, 10));

        var draft = body(mvc.perform(multipart("/api/v2/imports")
                        .file(new MockMultipartFile("file", "program.xlsx", null, bytes.toByteArray()))
                        .param("startDate", "2026-11-02").with(token()))
                .andExpect(status().isOk()));

        assertThat(draft.get("status").asString()).isEqualTo("draft");
        model.verify();
    }

    private ResultActions start(Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v2/imports").with(token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private ResultActions answers(String id, String answers) throws Exception {
        return mvc.perform(post("/api/v2/imports/" + id + "/answers").with(token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":" + answers + "}"));
    }

    private org.springframework.test.web.client.ResponseCreator reply(String content, int in, int out) {
        var body = json.createObjectNode();
        body.putArray("choices").addObject().put("finish_reason", "stop").putObject("message")
                .put("role", "assistant").put("content", content);
        body.putObject("usage").put("prompt_tokens", in).put("completion_tokens", out);
        return withSuccess(json.writeValueAsString(body), MediaType.APPLICATION_JSON);
    }

    private String questions() {
        return """
                {"questions":[{"id":"q1","text":"Сколько недель?","options":[{"id":"a","text":"4"},
                {"id":"b","text":"8"}]}],"assumptions":[],"program":null}""";
    }

    private String squatProgram(Map<String, Number> load) {
        var program = json.createObjectNode();
        program.putArray("questions");
        program.putArray("assumptions").add("Понедельник");
        var exercise = program.putObject("program").put("name", "Импорт").putArray("weeks").addObject()
                .put("number", 1).putArray("workouts").addObject().put("day", 1).put("title", "День 1")
                .putArray("exercises").addObject().put("name", "Присед со штангой");
        var set = exercise.putArray("sets").addObject().put("count", 5).put("reps", 5).put("type", "working");
        if (!load.isEmpty()) {
            var loadNode = set.putObject("load");
            load.forEach((key, value) -> loadNode.put(key, value.doubleValue()));
        }
        return json.writeValueAsString(program);
    }

    private static JsonNode firstExercise(JsonNode program) {
        return program.get("cycles").get(0).get("weeks").get(0).get("workouts").get(0).get("blocks").get(0)
                .get("exercises").get(0);
    }

    private static List<String> codes(JsonNode response) {
        return response.get("warnings").valueStream().map(warning -> warning.get("code").asString()).toList();
    }

    private String lastSessionStatus() throws Exception {
        return jdbc.queryForObject("SELECT status FROM sizylift.import_session WHERE user_id = ? "
                + "ORDER BY created_at DESC LIMIT 1", String.class, userId(subject));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private UUID userId(String user) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me")
                .with(token(user))).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM sizylift.user_account WHERE issuer = ? AND subject = ?",
                UUID.class, ISSUER, user);
    }

    private JwtRequestPostProcessor token() {
        return token(subject);
    }

    private static JwtRequestPostProcessor token(String user) {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(user).claim("email", user + "@example.com"));
    }

    /** Модель — настоящий клиент DeepSeek поверх заглушки HTTP. */
    @TestConfiguration
    static class StubModel {

        final RestClient.Builder builder = RestClient.builder().baseUrl("https://model.test")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key");
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        @Bean
        MockRestServiceServer modelServer() {
            return server;
        }

        @Bean
        @Primary
        ProgramParserModel stubbedProgramParserModel() {
            return new DeepSeekParserModel(builder.build(), ImportTestProperties.defaults().model());
        }
    }
}
