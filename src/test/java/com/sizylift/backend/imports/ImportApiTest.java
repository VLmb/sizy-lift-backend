package com.sizylift.backend.imports;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.imports.dto.ImportDtos.Commit;
import com.sizylift.backend.imports.dto.ImportDtos.Receipt;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.service.ImportCommitService;
import com.sizylift.backend.imports.service.ImportService;
import com.sizylift.backend.imports.service.ImportWorker;
import com.sizylift.backend.imports.service.ProgramDraftCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import static com.sizylift.backend.imports.ImportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"sizylift.imports.enabled=true", "sizylift.imports.worker-enabled=false",
        "sizylift.imports.workouts-per-chunk=2",
        "sizylift.web.allowed-origins=https://web.test,https://pwa.test",
        "sizylift.imports.deepseek.api-key=test-only", "sizylift.imports.deepseek.url=http://127.0.0.1"})
@AutoConfigureMockMvc
class ImportApiTest extends CoachWorkspaceApiSupport {
    private static final String BASE = "/api/v2/imports";
    @MockitoBean ProgramParserModel parser;
    @Autowired ImportWorker worker;
    @Autowired ImportCommitService commits;
    @Autowired ImportService imports;
    @Autowired com.sizylift.backend.imports.repository.ImportRepository sessions;
    @Autowired com.sizylift.backend.imports.config.ImportProperties limits;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping routes;
    String owner;

    @BeforeEach
    void setup() {
        owner = newSubject();
        reset(parser);
        jdbc.update("DELETE FROM sizylift.import_session");
    }

    @Test
    void englishInterfaceLanguageIsPersistedAndReachesTheWorker() throws Exception {
        var request = UUID.randomUUID();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("requestId", request.toString()); body.put("source", "text");
        body.put("text", TEXT); body.put("startDate", "2026-11-09"); body.put("language", "en");
        var response = postJson(owner, BASE, body).andExpect(status().isAccepted()).andReturn();
        var id = UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asString());
        assertThat(sessions.language(id)).isEqualTo("en");
        assertThat(sessions.find(id).orElseThrow().name()).isEqualTo("Imported program");
        when(parser.parse(any(), any())).thenAnswer(call -> {
            var sent = call.getArgument(0, ProgramParserModel.Request.class);
            assertThat(sent.input().source().language()).isEqualTo("en");
            assertThat(sent.user()).contains("ЯЗЫК ИНТЕРФЕЙСА\nen\n");
            return result(draftWithSet("Подход: кол=3 | повт=8-12 | вес=100 кг | заметка=Личная заметка", matched()));
        });
        assertThat(worker.processOne()).isTrue();
        assertThat(sessions.find(id).orElseThrow().normalized().language()).isEqualTo("en");
        var preview = view(owner, id);
        // Комментариев к подходу нет: пометки подхода — в комментарии упражнения.
        var set = preview.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0");
        assertThat(set.get("note").isNull()).isTrue();
        assertThat(set.get("repetitions").asInt()).isEqualTo(8);
        assertThat(set.get("repetitionsMax").asInt()).isEqualTo(12);
        assertThat(preview.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/planComment").asString())
                .contains("Личная заметка").doesNotContain("Reps: 8-12", "Set type not specified");
        // Повтор после смены языка не создаёт новую сессию и не меняет первый выбор.
        body.remove("language");
        postJson(owner, BASE, body).andExpect(status().isAccepted());
        assertThat(sessions.language(id)).isEqualTo("en");
    }

    @Test
    void allRegisteredApiRoutesAllowPwaPreflightAndKeepAuthentication() throws Exception {
        var inventory = new java.util.TreeSet<String>();
        for (var mapping : routes.getHandlerMethods().keySet()) {
            for (var pattern : mapping.getPatternValues()) {
                if (!pattern.startsWith("/api/")) continue;
                var path = pattern.replaceAll("\\{[^}]+}", "00000000-0000-4000-8000-000000000001");
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    inventory.add(method.name() + " " + path);
                }
            }
        }
        assertThat(inventory.size()).isGreaterThan(30);
        for (var route : inventory) {
            var parts = route.split(" ", 2);
            for (var origin : List.of("https://web.test", "https://pwa.test")) {
                mvc.perform(options(parts[1]).header("Origin", origin)
                                .header("Access-Control-Request-Method", parts[0])
                                .header("Access-Control-Request-Headers", "authorization,content-type"))
                        .andExpect(status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .string("Access-Control-Allow-Origin", origin));
            }
            mvc.perform(options(parts[1]).header("Origin", "https://untrusted.invalid")
                            .header("Access-Control-Request-Method", parts[0]))
                    .andExpect(status().isForbidden());
            // Страница приглашения открыта без входа (CR-02): неизвестный токен — 404, а не 401.
            var open = route.equals("GET /api/v2/coaching/invites/00000000-0000-4000-8000-000000000001/public");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                            .header("Origin", "https://pwa.test"))
                    .andExpect(open ? status().isNotFound() : status().isUnauthorized());
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/pwa-api-routes.json"),
                json.writeValueAsString(inventory));
    }

    @Test
    void coachCanCreateListAndRevokeAnInviteFromPwaOrigin() throws Exception {
        var coach = newSubject();
        var origin = "https://pwa.test";
        call(coach, put("/api/v2/coaching/coach-mode").header("Origin", origin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        var invite = body(call(coach, post("/api/v2/coaching/invites").header("Origin", origin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()));
        assertThat(invite.get("token").asString()).matches("[A-Za-z0-9_-]{22}");
        assertThat(invite.get("expiresAt").asString()).isNotBlank();
        var list = body(call(coach, get("/api/v2/coaching/invites").header("Origin", origin))
                .andExpect(status().isOk()));
        assertThat(list.size()).isEqualTo(1);
        call(coach, delete("/api/v2/coaching/invites/" + list.get(0).get("id").asString())
                        .header("Origin", origin)).andExpect(status().isNoContent());
    }

    @Test
    void acceptsBrowserImportsFromWebAndPwaButRejectsOtherOrigins() throws Exception {
        var originHeader = org.springframework.http.HttpHeaders.ORIGIN;
        var allowOrigin = org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
        for (var origin : List.of("https://web.test", "https://pwa.test")) {
            mvc.perform(options(BASE).header(originHeader, origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(allowOrigin, origin));
            var created = body(call(owner, post(BASE).header(originHeader, origin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(input(TEXT, null, UUID.randomUUID()))))
                    .andExpect(status().isAccepted())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(allowOrigin, origin)));
            assertThat(created.get("id").asString()).isNotBlank();
            assertThat(created.get("status").asString()).isEqualTo("queued");
            assertThat(created.get("version").asLong()).isPositive();
        }
        var count = jdbc.queryForObject("SELECT count(*) FROM sizylift.import_session", Integer.class);
        call(owner, post(BASE).header(originHeader, "https://untrusted.test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input(TEXT, null, UUID.randomUUID()))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.import_session", Integer.class)).isEqualTo(count);
        mvc.perform(options(BASE).header(originHeader, "https://untrusted.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(parser);
    }

    @Test
    void importsTextUsingTheCatalogAndSavesAProgramOnlyOnCommit() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        var user = userId(owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.program WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(worker.processOne()).isTrue();
        var view = view(owner, id);
        assertThat(view.get("status").asString()).isEqualTo("review");
        assertThat(view.get("canCommit").asBoolean()).isTrue();
        var set = view.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0");
        assertThat(set.get("targetWeightGrams").asInt()).isEqualTo(100000);
        assertThat(set.get("targetRpeAuto").asBoolean()).isFalse();
        var receipt = body(postJson(owner, BASE + "/" + id + "/commit", Map.of("version", view.get("version").asLong()))
                .andExpect(status().isOk()));
        var programId = receipt.get("programId").asString();
        var program = body(call(owner, get("/api/v2/web/programs/" + programId)).andExpect(status().isOk()));
        assertThat(program.at("/program/status").asString()).isEqualTo("draft");
        assertThat(program.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/exerciseId").asString()).isEqualTo(SQUAT.toString());
        var repeat = body(postJson(owner, BASE + "/" + id + "/commit", Map.of("version", 1)).andExpect(status().isOk()));
        assertThat(repeat).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.program WHERE user_id=?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT normalized IS NULL AND source_text IS NULL AND history='[]'::jsonb FROM sizylift.import_session WHERE id=?",
                Boolean.class, id)).isTrue();
        verify(parser, times(2)).parse(any(), any());
    }

    @Test
    void clarificationReplaysTheOriginalSourceAndAllAnswersAndDeduplicatesTheAnswer() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(clarification("От какого веса считать снижение?"), draft(matched())));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var first = view(owner, id);
        assertThat(first.get("needsClarification").asBoolean()).isTrue();
        var answer = Map.of("requestId", UUID.randomUUID(), "version", first.get("version").asLong(), "round", 0,
                "answer", "От первого рабочего подхода");
        postJson(owner, BASE + "/" + id + "/clarifications", answer).andExpect(status().isAccepted());
        postJson(owner, BASE + "/" + id + "/clarifications", answer).andExpect(status().isAccepted());
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("review");
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        // Вопрос, понимание после ответа и недели.
        verify(parser, times(3)).parse(captor.capture(), any());
        var requests = captor.getAllValues();
        assertThat(requests.get(1).input().source()).isEqualTo(requests.get(0).input().source());
        assertThat(requests.get(1).system()).isEqualTo(requests.get(0).system());
        assertThat(requests.get(1).input().history()).hasSize(1);
        assertThat(requests.get(1).input().history().getFirst().answer()).isEqualTo("От первого рабочего подхода");
        assertThat(requests.get(1).input().history().getFirst().question()).isEqualTo("От какого веса считать снижение?");
        assertThat(requests.get(1).user()).contains("Вопрос: От какого веса считать снижение?\nОтвет: От первого рабочего подхода");
    }

    @Test
    void contextsOfTwoImportsForTheSameUserDoNotMix() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(clarification("Уточните первый источник"), clarification("Уточните второй источник")));
        var first = create(owner, TEXT, null, UUID.randomUUID());
        var second = create(owner, "Второй источник", null, UUID.randomUUID());
        worker.processOne(); worker.processOne();
        var firstView = view(owner, first);
        postJson(owner, BASE + "/" + first + "/clarifications", Map.of("requestId", UUID.randomUUID(),
                "version", firstView.get("version").asLong(), "round", 0, "answer", "Ответ только для первого")).andExpect(status().isAccepted());
        doAnswer(call -> {
            var input = call.getArgument(0, ProgramParserModel.Request.class).input();
            assertThat(input.history()).hasSize(1);
            assertThat(input.source().items().getFirst().text()).isEqualTo(TEXT);
            return result(draft(matched()));
        }).when(parser).parse(any(), any());
        worker.processOne();
        assertThat(view(owner, second).get("clarificationMessage").asString()).isEqualTo("Уточните второй источник");
    }

    @Test
    void ambiguousExerciseCanBeChosenWithoutCallingTheModelAgain() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(ambiguous())));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("canCommit").asBoolean()).isFalse();
        var chosen = body(call(owner, patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", view.get("version").asLong(), "decisions",
                        List.of(Map.of("key", "e1", "kind", "existing", "exerciseId", SQUAT)))))).andExpect(status().isOk()));
        assertThat(chosen.get("canCommit").asBoolean()).isTrue();
        verify(parser, times(3)).parse(any(), any());
    }

    @Test
    void customExerciseStaysInTheDraftUntilCommitAndIsCopiedToTheAthlete() throws Exception {
        var coach = newSubject();
        link(coach, owner);
        var athlete = userId(owner);
        var coachId = userId(coach);
        when(parser.parse(any(), any())).thenAnswer(reply(draft(custom("Альфа", "вес_повторы"))));
        var id = create(coach, "Альфа 3×5 100 кг @8", athlete, UUID.randomUUID());
        worker.processOne();
        var view = view(coach, id);
        var exerciseId = UUID.fromString(view.at("/decisions/e1/exerciseId").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE id=?", Integer.class, exerciseId)).isZero();
        var receipt = body(postJson(coach, BASE + "/" + id + "/commit", Map.of("version", view.get("version").asLong()))
                .andExpect(status().isOk()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE user_id IN (?,?) AND name='Альфа'",
                Integer.class, coachId, athlete)).isEqualTo(2);
        var response = body(call(coach, get("/api/v2/web/programs/" + receipt.get("programId").asString()).param("ownerId", athlete.toString()))
                .andExpect(status().isOk()));
        assertThat(response.get("authorId").asString()).isEqualTo(coachId.toString());
        assertThat(response.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/exerciseId").asString()).isNotEqualTo(exerciseId.toString());
    }

    @Test
    void skippedExerciseIsLeftOutOfTheProgram() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(custom("Альфа", "вес_повторы"), custom("Омега", "вес_повторы"))));
        var id = create(owner, "Альфа и Омега: 3×5 100 кг @8", null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        var skipped = body(call(owner, patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", view.get("version").asLong(), "decisions",
                        List.of(Map.of("key", "e1", "kind", "skip")))))).andExpect(status().isOk()));
        assertThat(skipped.get("canCommit").asBoolean()).isTrue();
        var receipt = body(postJson(owner, BASE + "/" + id + "/commit", Map.of("version", skipped.get("version").asLong()))
                .andExpect(status().isOk()));
        var user = userId(owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE user_id=? AND name='Альфа'", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE user_id=? AND name='Омега'", Integer.class, user)).isEqualTo(1);
        var program = body(call(owner, get("/api/v2/web/programs/" + receipt.get("programId").asString())).andExpect(status().isOk()));
        assertThat(program.toString()).doesNotContain("Альфа").contains("Омега");
    }

    @Test
    void rowMadeIntoBlockBecomesFreeBlockOfTheWorkout() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(custom("Разминка", "вес_повторы"), custom("Омега", "вес_повторы"))));
        var id = create(owner, "Разминка; Омега: 3×5 100 кг @8", null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        var decided = body(call(owner, patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", view.get("version").asLong(), "decisions",
                        List.of(Map.of("key", "e1", "kind", "block", "name", "Разминка")))))).andExpect(status().isOk()));
        assertThat(decided.get("canCommit").asBoolean()).isTrue();
        var receipt = body(postJson(owner, BASE + "/" + id + "/commit", Map.of("version", decided.get("version").asLong()))
                .andExpect(status().isOk()));
        var user = userId(owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE user_id=? AND name='Разминка'", Integer.class, user)).isZero();
        var program = body(call(owner, get("/api/v2/web/programs/" + receipt.get("programId").asString())).andExpect(status().isOk()));
        var blocks = program.at("/program/cycles/0/weeks/0/workouts/0/blocks");
        assertThat(blocks.get(0).get("blockType").asString()).isEqualTo("free");
        assertThat(blocks.get(0).get("title").asString()).isEqualTo("Разминка");
        assertThat(blocks.get(0).get("exercises").size()).isZero();
        assertThat(program.toString()).contains("Омега");
    }

    @Test
    void genuineIdCollisionRollsBackEarlierExercisesAndAllFeedEntries() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(custom("Альфа", "вес_повторы"), custom("Омега", "вес_повторы"))));
        var id = create(owner, "Альфа и Омега: 3×5 100 кг @8", null, UUID.randomUUID());
        worker.processOne();
        var collision = List.of(ProgramDraftCompiler.id(id, "exercise:e1"), ProgramDraftCompiler.id(id, "exercise:e2"))
                .stream().sorted(com.sizylift.backend.access.service.AccessPolicy.LOCK_ORDER).toList().getLast();
        var stranger = newSubject();
        legacy(stranger, "exercise", collision, 1, fixture("exercise"));
        var user = userId(owner);
        var view = view(owner, id);
        postJson(owner, BASE + "/" + id + "/commit", Map.of("version", view.get("version").asLong())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.program WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.published_aggregate WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.sync_feed WHERE owner_user_id=?", Integer.class, user)).isZero();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("review");
    }

    @Test
    void concurrentCommitsReturnTheSameProgram() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        long version = view(owner, id).get("version").asLong();
        var user = userId(owner);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Receipt> task = () -> { start.await(); return commits.commit(user, id, new Commit(version)); };
            var first = executor.submit(task); var second = executor.submit(task); start.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.program WHERE user_id=?", Integer.class, user)).isEqualTo(1);
    }

    @Test
    void revocationAndForeignAccessCannotReadOrCommitAnImport() throws Exception {
        var coach = newSubject();
        var link = link(coach, owner);
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        var id = create(coach, TEXT, userId(owner), UUID.randomUUID());
        worker.processOne();
        call(owner, get(BASE + "/" + id)).andExpect(status().isNotFound());
        call(owner, delete("/api/v2/coaching/links/" + link)).andExpect(status().isNoContent());
        call(coach, get(BASE + "/" + id)).andExpect(status().isNotFound());
        postJson(coach, BASE + "/" + id + "/commit", Map.of("version", 3)).andExpect(status().isNotFound());
    }

    @Test
    void repeatedCreateIsIdempotentButTheSameKeyCannotChangeTheSource() throws Exception {
        var request = UUID.randomUUID();
        var first = create(owner, TEXT, null, request);
        assertThat(create(owner, TEXT, null, request)).isEqualTo(first);
        var changed = input("Другой текст", null, request);
        postJson(owner, BASE, changed).andExpect(status().isConflict());
        verifyNoInteractions(parser);
    }

    @Test
    void cancellationFencesALateModelResponse() throws Exception {
        var id = create(owner, TEXT, null, UUID.randomUUID());
        var user = userId(owner);
        when(parser.parse(any(), any())).thenAnswer(call -> {
            imports.cancel(user, id);
            return result(draft(matched()));
        });
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("status").asString()).isEqualTo("cancelled");
        assertThat(view.get("draft").isNull()).isTrue();
        assertThat(worker.processOne()).isFalse();
    }

    @Test
    void expiredSessionsReturnGoneAndTheirInputsAreErased() throws Exception {
        var id = create(owner, TEXT, null, UUID.randomUUID());
        jdbc.update("UPDATE sizylift.import_session SET expires_at=now()-interval '1 second' WHERE id=?", id);
        call(owner, get(BASE + "/" + id)).andExpect(status().isGone());
        worker.cleanup();
        assertThat(jdbc.queryForObject("SELECT source_text IS NULL AND normalized IS NULL FROM sizylift.import_session WHERE id=?", Boolean.class, id)).isTrue();
        verifyNoInteractions(parser);
    }

    @Test
    void invalidModelOutputIsRetriedOnceAndNeverBecomesAProgram() throws Exception {
        when(parser.parse(any(), any())).thenReturn(new ProgramParserModel.Result("{}", 100, 10, 0, false));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("queued");
        jdbc.update("UPDATE sizylift.import_session SET next_attempt_at=now() WHERE id=?", id);
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("failed");
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        verify(parser, times(2)).parse(captor.capture(), any());
        assertThat(captor.getAllValues().get(0).input().repairMessage()).isEmpty();
        assertThat(captor.getAllValues().get(1).input().repairMessage()).contains("не по шаблону");
    }

    @Test
    void aPlanThatDoesNotFitEvenWithoutWeeksFailsWithoutFurtherPaidRepeats() throws Exception {
        when(parser.parse(any(), any())).thenReturn(new ProgramParserModel.Result("Программа: Огромная\nУпражнения:\n- При", 5000, 32768, 0, true));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("status").asString()).isEqualTo("failed");
        assertThat(view.at("/error/code").asString()).isEqualTo("input_too_large");
        assertThat(view.get("outputTokens").asLong()).isEqualTo(32768);
        assertThat(worker.processOne()).isFalse();
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        verify(parser, times(1)).parse(captor.capture(), any());
        assertThat(captor.getValue().input().task().kind()).isEqualTo(ProgramParserModel.Task.Kind.UNDERSTAND);
    }

    @Test
    void aBrokenLineBecomesAWarningInsteadOfAPaidRetry() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draftWithSet("Подход: 3x5 100kg\n" + DEFAULT_SET, matched())));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("status").asString()).isEqualTo("review");
        assertThat(view.at("/draft/warnings").size()).isEqualTo(1);
        assertThat(view.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets").size()).isEqualTo(3);
        verify(parser, times(2)).parse(any(), any());
    }

    @Test
    void aLargeProgramIsPlannedFirstAndItsWeeksAreParsedInParallel() throws Exception {
        var started = new CountDownLatch(3);
        var observed = new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        var ids = new java.util.concurrent.atomic.AtomicReference<UUID>();
        when(parser.parse(any(), any())).thenAnswer(call -> {
            var input = call.getArgument(0, ProgramParserModel.Request.class).input();
            if (input.task().kind() != ProgramParserModel.Task.Kind.WEEKS) {
                observed.add(view(owner, ids.get()).get("progress"));
                return result(plan(List.of(matched()), 3, 2));
            }
            if (input.task().weeks().equals(List.of(1))) observed.add(view(owner, ids.get()).get("progress"));
            started.countDown();
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).as("части разбираются параллельно").isTrue();
            var weeks = input.task().weeks();
            // Часть повторно объявляет известное упражнение: сервер узнаёт его по названию и не дублирует.
            return result(weeks(weeks, weeks.contains(3) ? List.of(matched()) : List.of(), matched()));
        });
        var id = create(owner, TEXT, null, UUID.randomUUID());
        ids.set(id);
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("status").asString()).isEqualTo("review");
        assertThat(view.get("progress").isNull()).isTrue();
        assertThat(observed.get(0).get("stage").asString()).isEqualTo("plan");
        assertThat(observed.get(0).get("weeksTotal").isNull()).isTrue();
        assertThat(observed.get(1).get("stage").asString()).isEqualTo("weeks");
        assertThat(observed.get(1).get("weeksTotal").asInt()).isEqualTo(3);
        assertThat(observed.get(1).get("exercises").asInt()).isEqualTo(1);
        assertThat(view.get("canCommit").asBoolean()).isTrue();
        assertThat(view.at("/draft/exerciseResolutions").size()).isEqualTo(1);
        assertThat(view.at("/draft/plan").size()).isEqualTo(3);
        var cycles = view.at("/program/cycles");
        assertThat(cycles.size()).isEqualTo(2);
        assertThat(cycles.get(0).get("weeks").size()).isEqualTo(2);
        assertThat(cycles.get(1).at("/weeks/0/workouts/0/blocks/0/exercises/0/exerciseId").asString()).isEqualTo(SQUAT.toString());
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        verify(parser, times(4)).parse(captor.capture(), any());
        assertThat(captor.getAllValues().stream().map(ProgramParserModel.Request::input)
                .filter(input -> input.task().kind() == ProgramParserModel.Task.Kind.WEEKS)
                .map(input -> input.task().weeks()).toList()).containsExactlyInAnyOrder(List.of(1), List.of(2), List.of(3));
        assertThat(jdbc.queryForObject("SELECT outline IS NULL FROM sizylift.import_session WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(view.get("outputTokens").asLong()).isEqualTo(4 * 300);
    }

    @Test
    void aTruncatedPartIsSplitAndAPartWithoutItsWeekIsAskedAgain() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(call -> {
            var input = call.getArgument(0, ProgramParserModel.Request.class).input();
            var weeks = input.task().weeks();
            if (input.task().kind() != ProgramParserModel.Task.Kind.WEEKS) return result(plan(List.of(matched()), 2, 1));
            if (weeks.size() == 2) return new ProgramParserModel.Result("Тренировки:\nНеделя: 1\nДень: 1", 1000, 32768, 0, true);
            if (weeks.equals(List.of(2)) && input.repairMessage().isEmpty()) return result("Тренировки:\n");
            return result(weeks(weeks, List.of(), matched()));
        });
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("review");
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        verify(parser, times(5)).parse(captor.capture(), any());
        var repaired = captor.getAllValues().stream().map(ProgramParserModel.Request::input)
                .filter(input -> input.task().weeks().equals(List.of(2))).toList();
        assertThat(repaired).hasSize(2);
        assertThat(repaired.get(1).repairMessage()).contains("нет недель 2");
    }

    @Test
    void unfinishedImportsAreListedToReturnToThem() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(ambiguous())));
        var reviewed = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var waiting = create(owner, TEXT, null, UUID.randomUUID());
        create(newSubject(), TEXT, null, UUID.randomUUID());

        var list = body(call(owner, get(BASE)).andExpect(status().isOk()));
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("id").asString()).isEqualTo(waiting.toString());
        assertThat(list.get(0).get("status").asString()).isEqualTo("queued");
        var review = list.get(1);
        assertThat(review.get("id").asString()).isEqualTo(reviewed.toString());
        assertThat(review.get("status").asString()).isEqualTo("review");
        assertThat(review.get("openChoices").asInt()).isEqualTo(1);
        // Название по умолчанию заменено названием из разбора.
        assertThat(review.get("name").asString()).isEqualTo("Программа");
        assertThat(view(owner, reviewed).get("name").asString()).isEqualTo("Программа");

        var key = view(owner, reviewed).at("/draft/exerciseResolutions/0/key").asString();
        call(owner, patch(BASE + "/" + reviewed).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "version", view(owner, reviewed).get("version").asLong(),
                "decisions", List.of(Map.of("key", key, "kind", "existing", "exerciseId", SQUAT.toString()))))))
                .andExpect(status().isOk());
        call(owner, delete(BASE + "/" + waiting)).andExpect(status().isNoContent());
        list = body(call(owner, get(BASE)).andExpect(status().isOk()));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("openChoices").asInt()).isZero();
        assertThat(body(call(owner, get(BASE).param("ownerId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())).size()).isZero();
    }

    @Test
    void aNameGivenByTheUserIsKeptAfterParsing() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        var request = new java.util.HashMap<>(input(TEXT, null, UUID.randomUUID()));
        request.put("name", "Моя программа");
        var id = UUID.fromString(body(postJson(owner, BASE, request).andExpect(status().isAccepted())).get("id").asString());
        worker.processOne();
        assertThat(view(owner, id).get("name").asString()).isEqualTo("Моя программа");
    }

    @Test
    void aWeekThatCouldNotBeParsedIsShownAsFailedAndCanBeParsedAgain() throws Exception {
        var fixed = new java.util.concurrent.atomic.AtomicBoolean();
        when(parser.parse(any(), any())).thenAnswer(call -> {
            var input = call.getArgument(0, ProgramParserModel.Request.class).input();
            if (input.task().kind() != ProgramParserModel.Task.Kind.WEEKS) return result(plan(List.of(matched()), 4, 3));
            var weeks = new java.util.ArrayList<>(input.task().weeks());
            if (!fixed.get()) weeks.remove(Integer.valueOf(4));
            return result(weeks(weeks, List.of(), matched()));
        });
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var partial = view(owner, id);
        assertThat(partial.get("status").asString()).isEqualTo("review");
        assertThat(partial.get("canCommit").asBoolean()).isTrue();
        assertThat(partial.at("/draft/plan/3/status").asString()).isEqualTo("failed");
        // Неделя видна в каркасе со статусом failed — отдельного предупреждения о ней нет.
        assertThat(partial.at("/draft/warnings").toString()).doesNotContain("недели");
        assertThat(partial.at("/program/cycles/1/weeks").size()).isEqualTo(1);
        fixed.set(true);
        postJson(owner, BASE + "/" + id + "/weeks/retry", Map.of("version", partial.get("version").asLong()))
                .andExpect(status().isAccepted());
        worker.processOne();
        var complete = view(owner, id);
        assertThat(complete.get("status").asString()).isEqualTo("review");
        assertThat(complete.at("/draft/plan/3/status").asString()).isEqualTo("done");
        assertThat(complete.at("/program/cycles/1/weeks").size()).isEqualTo(2);
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        verify(parser, atLeastOnce()).parse(captor.capture(), any());
        assertThat(captor.getAllValues().getLast().input().task().weeks()).containsExactly(4);
    }

    @Test
    void theCatalogStageMatchesOnlyWhatTheDictionaryCouldNotAndThePartsFollowTheRules() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(call -> {
            var request = call.getArgument(0, ProgramParserModel.Request.class);
            var input = request.input();
            return switch (input.task().kind()) {
                case UNDERSTAND -> result("""
                        Программа: Сила
                        Правило: вес — у первого упражнения дня
                        Упражнения:
                        - Присед со штангой | тип=вес_повторы
                        - Жим лёжа (объём) | тип=вес_повторы
                        План:
                        Неделя: 1 | дни=1
                        """);
                case MATCH -> {
                    assertThat(input.task().exercises()).containsExactly("Жим лёжа (объём) | тип=вес_повторы");
                    assertThat(request.user()).doesNotContain(TEXT);
                    yield result("- Жим лёжа (объём) → Жим штанги лёжа | тип=вес_повторы\n");
                }
                case WEEKS -> {
                    assertThat(request.user()).contains("Правило: вес — у первого упражнения дня",
                            "- Жим лёжа (объём) | тип=вес_повторы");
                    yield result("Тренировки:\nНеделя: 1\nДень: 1\nУпражнение: Присед со штангой\n" + DEFAULT_SET
                            + "\nУпражнение: Жим лёжа (объём)\n" + DEFAULT_SET + "\n");
                }
            };
        });
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("status").asString()).isEqualTo("review");
        assertThat(view.at("/draft/exerciseResolutions/0/exerciseId").asString()).isEqualTo(SQUAT.toString());
        assertThat(view.at("/draft/exerciseResolutions/1/status").asString()).isEqualTo("matched");
        assertThat(view.at("/draft/rules/0").asString()).isEqualTo("вес — у первого упражнения дня");
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        verify(parser, times(3)).parse(captor.capture(), any());
        assertThat(captor.getAllValues()).extracting(request -> request.input().task().kind()).containsExactly(
                ProgramParserModel.Task.Kind.UNDERSTAND, ProgramParserModel.Task.Kind.MATCH, ProgramParserModel.Task.Kind.WEEKS);
    }

    @Test
    void cancellingDuringThePartsStopsFurtherCallsAndKeepsTheSessionCancelled() throws Exception {
        var id = create(owner, TEXT, null, UUID.randomUUID());
        var user = userId(owner);
        when(parser.parse(any(), any())).thenAnswer(call -> {
            var input = call.getArgument(0, ProgramParserModel.Request.class).input();
            if (input.task().kind() != ProgramParserModel.Task.Kind.WEEKS) return result(plan(List.of(matched()), 1, 1));
            imports.cancel(user, id);
            return result(weeks(input.task().weeks(), List.of(), matched()));
        });
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("cancelled");
        verify(parser, times(2)).parse(any(), any());
    }

    @Test
    void multipartCsvIsReadAsCellsWithoutRequiringColumnNames() throws Exception {
        var metadata = new java.util.HashMap<>(input(null, null, UUID.randomUUID()));
        metadata.put("source", "file");
        metadata.remove("text");
        var file = new MockMultipartFile("file", "arbitrary.csv", "text/csv", TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var part = new MockMultipartFile("metadata", "", "application/json", json.writeValueAsBytes(metadata));
        var created = body(mvc.perform(multipart(BASE).file(file).file(part)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token -> token.issuer(ISSUER).subject(owner).claim("email", owner + "@example.com"))))
                .andExpect(status().isAccepted()));
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        worker.processOne();
        var id = UUID.fromString(created.get("id").asString());
        assertThat(view(owner, id).get("canCommit").asBoolean()).isTrue();
    }

    @Test
    void convertsRirAndRestWhilePreservingRepRangesAndMissingWeight() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draftWithSet("Подход: кол=3 | повт=8-12 | RIR=1–2 | отдых=3–5 мин", matched())));
        var id = create(owner, "Присед 3×8-12 RIR 1–2, отдых 3–5 мин", null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("canCommit").asBoolean()).isTrue();
        var actual = view.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0");
        assertThat(actual.get("repetitions").asInt()).isEqualTo(8);
        assertThat(actual.get("repetitionsMax").asInt()).isEqualTo(12);
        assertThat(actual.get("targetWeightGrams").isNull()).isTrue();
        assertThat(actual.get("targetRpeTenths").asInt()).isEqualTo(85);
        assertThat(actual.get("restMs").asInt()).isEqualTo(240_000);
        assertThat(actual.get("note").isNull()).isTrue();
        assertThat(view.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/planComment").asString())
                .contains("RIR: 1-2").doesNotContain("8-12", "3–5 мин");
    }

    @Test
    void ambiguousExerciseCanBecomeACustomExerciseWithAStableId() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(ambiguous())));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var before = view(owner, id);
        var chosen = body(call(owner, patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", before.get("version").asLong(), "decisions", List.of(
                        Map.of("key", "e1", "kind", "new", "name", "Присед с паузой", "resultType", "weight_reps", "liftClass", "basic"))))))
                .andExpect(status().isOk()));
        assertThat(chosen.get("canCommit").asBoolean()).isTrue();
        assertThat(chosen.at("/decisions/e1/exerciseId").asString()).isEqualTo(ProgramDraftCompiler.id(id, "exercise:e1").toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE user_id=?", Integer.class, userId(owner))).isZero();
        postJson(owner, BASE + "/" + id + "/commit", Map.of("version", chosen.get("version").asLong())).andExpect(status().isOk());
        verify(parser, times(3)).parse(any(), any());
    }

    @Test
    void excessiveClarificationStopsAndEachRoundContainsAllPreviousAnswers() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(clarification("Что означает запись?")));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        for (int round = 0; round < 3; round++) {
            var current = view(owner, id);
            postJson(owner, BASE + "/" + id + "/clarifications", Map.of("requestId", UUID.randomUUID(),
                    "version", current.get("version").asLong(), "round", round, "answer", "Пояснение " + round))
                    .andExpect(status().isAccepted());
            worker.processOne();
        }
        var view = view(owner, id);
        assertThat(view.get("status").asString()).isEqualTo("failed");
        assertThat(view.at("/error/code").asString()).isEqualTo("clarification_limit");
        var captured = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        verify(parser, times(4)).parse(captured.capture(), any());
        assertThat(captured.getAllValues().getLast().input().history().stream().map(h -> h.answer()).toList())
                .containsExactly("Пояснение 0", "Пояснение 1", "Пояснение 2");
    }

    @Test
    void activeQuotaIsAtomicAndRetriesDoNotConsumeAnotherSlot() throws Exception {
        var request = UUID.randomUUID();
        var id = create(owner, TEXT, null, request);
        create(owner, TEXT, null, UUID.randomUUID());
        assertThat(create(owner, TEXT, null, request)).isEqualTo(id);
        postJson(owner, BASE, input(TEXT, null, UUID.randomUUID())).andExpect(status().isTooManyRequests());
        verifyNoInteractions(parser);
    }

    @Test
    void exhaustedBudgetPreventsAnotherProviderCall() throws Exception {
        var id = create(owner, TEXT, null, UUID.randomUUID());
        jdbc.update("UPDATE sizylift.import_session SET reserved_cost_micros=1000000 WHERE id=?", id);
        worker.processOne();
        assertThat(view(owner, id).at("/error/code").asString()).isEqualTo("import_budget");
        verifyNoInteractions(parser);
    }

    @Test
    void invalidNumericValuesAreSkippedNotInvented() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draftWithSet("Подход: кол=3 | повт=5 | вес=-987 кг", matched())));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("status").asString()).isEqualTo("review");
        assertThat(view.at("/draft/warnings").size()).isEqualTo(1);
        assertThat(view.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0/targetWeightGrams").isNull()).isTrue();
    }

    @Test
    void invalidSheetUrlIsRejectedBeforeTheModelIsCalled() throws Exception {
        var request = new java.util.HashMap<>(input(null, null, UUID.randomUUID()));
        request.put("source", "sheet"); request.remove("text"); request.put("url", "http://169.254.169.254/latest/meta-data");
        var created = body(postJson(owner, BASE, request).andExpect(status().isAccepted()));
        worker.processOne();
        assertThat(view(owner, UUID.fromString(created.get("id").asString())).at("/error/code").asString()).isEqualTo("sheet_unavailable");
        verifyNoInteractions(parser);
    }

    @Test
    void anExpiredWorkerLeaseIsRecoveredAndItsLateResultCannotOverwriteTheNewOne() throws Exception {
        var id = create(owner, TEXT, null, UUID.randomUUID());
        var old = transactions.execute(status -> sessions.claim(java.time.Instant.now(), limits).orElseThrow());
        jdbc.update("UPDATE sizylift.import_session SET lease_until=now()-interval '1 second' WHERE id=?", id);
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        assertThat(worker.processOne()).isTrue();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("review");
        assertThat(sessions.finish(old, "failed", null, Map.of(), "late", "late", 0, 0, 0, java.time.Instant.now())).isFalse();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("review");
    }

    @Test
    void numbersWrittenAsWordsCanBeInterpretedByTheModel() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        var id = create(owner, "Присед: три подхода по пять повторений со ста килограммами, RPE восемь", null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("canCommit").asBoolean()).isTrue();
        assertThat(view.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0/targetWeightGrams").asInt()).isEqualTo(100000);
    }

    @Test
    void parallelWorkersRespectTheGlobalCapacityAndDoNotClaimAnImportTwice() throws Exception {
        var entered = new CountDownLatch(limits.concurrentRequests());
        var release = new CountDownLatch(1);
        when(parser.parse(any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return result(draft(matched()));
        });
        for (int i = 0; i <= limits.concurrentRequests(); i++) create(newSubject(), TEXT, null, UUID.randomUUID());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            try {
                for (int i = 0; i < limits.concurrentRequests(); i++) futures.add(pool.submit(worker::processOne));
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(worker.processOne()).isFalse();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.import_session WHERE status='processing'", Integer.class))
                        .isEqualTo(limits.concurrentRequests());
                verify(parser, times(limits.concurrentRequests())).parse(any(), any());
            } finally { release.countDown(); }
            for (var future : futures) assertThat(future.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(worker.processOne()).isTrue();
        assertThat(worker.processOne()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.import_session WHERE status='review'", Integer.class))
                .isEqualTo(limits.concurrentRequests() + 1);
    }

    @Test
    void theEventStreamSendsTheStateAtOnceAndOnEveryChangeAndClosesWhenTheParseIsDone() throws Exception {
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        var stream = call(owner, get(BASE + "/" + id + "/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> stream.getResponse().getContentAsString().contains("\"status\":\"queued\""));
        assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(stream.getResponse().getContentAsString()).startsWith("event:state\nid:queued:");
        worker.processOne();
        // Разбор закончен: последнее событие — review, после него сервер сам закрывает поток.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> stream.getResponse().getContentAsString().contains("\"status\":\"review\""));
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        var events = stream.getResponse().getContentAsString().split("\n\n");
        assertThat(events.length).isGreaterThanOrEqualTo(2);
        assertThat(events[events.length - 1]).contains("\"status\":\"review\"", "\"canCommit\":true");

        // Готовая сессия — одно событие и сразу конец потока; чужая — 404 до начала потока.
        var settled = call(owner, get(BASE + "/" + id + "/events").accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        assertThat(settled.getResponse().getContentAsString()).containsOnlyOnce("event:state").contains("\"status\":\"review\"");
        call(newSubject(), get(BASE + "/" + id + "/events").accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isNotFound());
    }

    @Test
    void aWorkbookWithSeveralProgramsWaitsForSheetsAndSendsOnlyTheChosenOneToTheModel() throws Exception {
        byte[] bytes;
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            for (var client : List.of("Иванов", "Петров", "Пустой")) {
                var sheet = book.createSheet(client);
                if (client.equals("Пустой")) continue;
                sheet.createRow(0).createCell(0).setCellValue("Программа: " + client);
                sheet.createRow(2).createCell(0).setCellValue("Присед 3×5 100 кг");
            }
            var output = new java.io.ByteArrayOutputStream();
            book.write(output); bytes = output.toByteArray();
        }
        var metadata = new java.util.HashMap<>(input(null, null, UUID.randomUUID()));
        metadata.put("source", "file"); metadata.remove("text");
        var created = body(mvc.perform(multipart(BASE)
                .file(new MockMultipartFile("file", "clients.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
                .file(new MockMultipartFile("metadata", "", "application/json", json.writeValueAsBytes(metadata)))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token -> token.issuer(ISSUER).subject(owner).claim("email", owner + "@example.com"))))
                .andExpect(status().isAccepted()));
        var id = UUID.fromString(created.get("id").asString());
        assertThat(worker.processOne()).isTrue();
        var waiting = view(owner, id);
        assertThat(waiting.get("status").asString()).isEqualTo("sheet_selection");
        assertThat(waiting.get("sheets").size()).as("пустой лист не предлагается").isEqualTo(2);
        assertThat(waiting.at("/sheets/1/name").asString()).isEqualTo("Петров");
        assertThat(waiting.at("/sheets/1/preview/0").asString()).isEqualTo("Программа: Петров");
        verifyNoInteractions(parser);
        assertThat(worker.processOne()).as("без выбора листов задание в очереди не появляется").isFalse();

        postJson(owner, BASE + "/" + id + "/sheets", Map.of("version", waiting.get("version").asLong(), "sheets", List.of(7)))
                .andExpect(status().isBadRequest());
        when(parser.parse(any(), any())).thenAnswer(reply(draft(matched())));
        postJson(owner, BASE + "/" + id + "/sheets", Map.of("version", waiting.get("version").asLong(), "sheets", List.of(1)))
                .andExpect(status().isAccepted());
        assertThat(worker.processOne()).isTrue();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("review");
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Request.class);
        verify(parser, times(2)).parse(captor.capture(), any());
        var sent = captor.getAllValues().getFirst();
        assertThat(sent.user()).contains("Программа: Петров").doesNotContain("Иванов");
        assertThat(jdbc.queryForObject("SELECT normalized::text FROM sizylift.import_session WHERE id=?", String.class, id))
                .doesNotContain("Иванов");
    }

    @Test
    void xlsxTranscriptionReachesTheModelAndTheCommittedPayloadWithoutLosingValues() throws Exception {
        byte[] bytes;
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = book.createSheet("Неделя 1");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("Присед");
            row.createCell(1).setCellValue("3×5");
            row.createCell(2).setCellValue(100.5);
            row.createCell(3).setCellValue(0.75);
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("0%"));
            row.getCell(3).setCellStyle(style);
            var output = new java.io.ByteArrayOutputStream();
            book.write(output); bytes = output.toByteArray();
        }
        var metadata = new java.util.HashMap<>(input(null, null, UUID.randomUUID()));
        metadata.put("source", "file"); metadata.remove("text");
        var created = body(mvc.perform(multipart(BASE)
                .file(new MockMultipartFile("file", "plan.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
                .file(new MockMultipartFile("metadata", "", "application/json", json.writeValueAsBytes(metadata)))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token -> token.issuer(ISSUER).subject(owner).claim("email", owner + "@example.com"))))
                .andExpect(status().isAccepted()));
        when(parser.parse(any(), any())).thenAnswer(call -> {
            var source = call.getArgument(0, ProgramParserModel.Request.class).input().source();
            assertThat(source.items()).containsExactly(
                    new com.sizylift.backend.imports.entity.ImportSession.SourceItem("sheet:0", "Неделя 1"),
                    new com.sizylift.backend.imports.entity.ImportSession.SourceItem("sheet:0!A3", "Присед"),
                    new com.sizylift.backend.imports.entity.ImportSession.SourceItem("sheet:0!B3", "3×5"),
                    new com.sizylift.backend.imports.entity.ImportSession.SourceItem("sheet:0!C3", "100,5"),
                    new com.sizylift.backend.imports.entity.ImportSession.SourceItem("sheet:0!D3", "75%"));
            return result(draftWithSet("Подход: кол=3 | повт=5 | вес=100.5 кг | процент=75", matched()));
        });
        var id = UUID.fromString(created.get("id").asString());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("canCommit").asBoolean()).isTrue();
        var receipt = body(postJson(owner, BASE + "/" + id + "/commit", Map.of("version", view.get("version").asLong()))
                .andExpect(status().isOk()));
        var program = body(call(owner, get("/api/v2/web/programs/" + receipt.get("programId").asString())).andExpect(status().isOk()));
        var sets = program.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets");
        assertThat(sets.size()).isEqualTo(3);
        for (var set : sets) {
            assertThat(set.get("targetWeightGrams").asInt()).isEqualTo(100500);
            assertThat(set.get("targetPercentOneRmTenths").asInt()).isEqualTo(750);
            assertThat(set.get("repetitions").asInt()).isEqualTo(5);
        }
    }

    private UUID create(String subject, String text, UUID target, UUID request) throws Exception {
        var created = body(postJson(subject, BASE, input(text, target, request)).andExpect(status().isAccepted()));
        return UUID.fromString(created.get("id").asString());
    }
    private Map<String, Object> input(String text, UUID target, UUID request) {
        var result = new java.util.HashMap<String, Object>();
        result.put("requestId", request); result.put("ownerId", target); result.put("source", "text");
        result.put("text", text); result.put("name", "Импортированная программа"); result.put("startDate", "2026-11-09");
        return result;
    }
    private JsonNode view(String subject, UUID id) throws Exception {
        return body(call(subject, get(BASE + "/" + id)).andExpect(status().isOk()));
    }
    /** Ответы по очереди; последний повторяется. Текст передаётся слушателю, как поток настоящей модели. */
    private org.mockito.stubbing.Answer<ProgramParserModel.Result> reply(String... answers) {
        var queue = new java.util.ArrayDeque<>(List.of(answers));
        return call -> {
            var text = queue.size() > 1 ? queue.poll() : queue.peek();
            return stream(call, text);
        };
    }
    /** Ответ модели; строки уже переданы слушателю в {@link #stream}. */
    private static ProgramParserModel.Result result(String text) {
        return new ProgramParserModel.Result(text, 1000, 300, 100, false);
    }
    private static ProgramParserModel.Result stream(org.mockito.invocation.InvocationOnMock call, String text) {
        var listener = call.getArgument(1, ProgramParserModel.Listener.class);
        if (listener != null) listener.onText(text);
        return result(text);
    }
}
