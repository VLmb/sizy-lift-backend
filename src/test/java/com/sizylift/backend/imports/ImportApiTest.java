package com.sizylift.backend.imports;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.imports.dto.ImportDraftV1;
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
import tools.jackson.databind.node.ObjectNode;

import static com.sizylift.backend.imports.ImportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"sizylift.imports.enabled=true", "sizylift.imports.worker-enabled=false",
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
    String owner;

    @BeforeEach
    void setup() {
        owner = newSubject();
        reset(parser);
        jdbc.update("DELETE FROM sizylift.import_session");
    }

    @Test
    void importsTextUsingTheCatalogAndSavesAProgramOnlyOnCommit() throws Exception {
        when(parser.parse(any())).thenReturn(result(draft(matched("text:1"))));
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
        verify(parser, times(1)).parse(any());
    }

    @Test
    void clarificationReplaysTheOriginalSourceAndAllAnswersAndDeduplicatesTheAnswer() throws Exception {
        when(parser.parse(any())).thenReturn(result(clarification("От какого веса считать снижение?")), result(draft(matched("text:1"))));
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
        var captor = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Input.class);
        verify(parser, times(2)).parse(captor.capture());
        var requests = captor.getAllValues();
        assertThat(requests.get(1).source()).isEqualTo(requests.get(0).source());
        assertThat(requests.get(1).catalog()).isEqualTo(requests.get(0).catalog());
        assertThat(requests.get(1).history()).hasSize(1);
        assertThat(requests.get(1).history().getFirst().answer()).isEqualTo("От первого рабочего подхода");
        assertThat(requests.get(1).draft().needsClarification()).isTrue();
    }

    @Test
    void contextsOfTwoImportsForTheSameUserDoNotMix() throws Exception {
        when(parser.parse(any())).thenReturn(result(clarification("Уточните первый источник")), result(clarification("Уточните второй источник")));
        var first = create(owner, TEXT, null, UUID.randomUUID());
        var second = create(owner, "Второй источник", null, UUID.randomUUID());
        worker.processOne(); worker.processOne();
        var firstView = view(owner, first);
        postJson(owner, BASE + "/" + first + "/clarifications", Map.of("requestId", UUID.randomUUID(),
                "version", firstView.get("version").asLong(), "round", 0, "answer", "Ответ только для первого")).andExpect(status().isAccepted());
        when(parser.parse(any())).thenAnswer(call -> {
            var input = call.getArgument(0, ProgramParserModel.Input.class);
            assertThat(input.history()).hasSize(1);
            assertThat(input.source().items().getFirst().text()).isEqualTo(TEXT);
            return result(draft(matched("text:1")));
        });
        worker.processOne();
        assertThat(view(owner, second).get("clarificationMessage").asString()).isEqualTo("Уточните второй источник");
    }

    @Test
    void ambiguousExerciseCanBeChosenWithoutCallingTheModelAgain() throws Exception {
        var ambiguous = new ImportDraftV1.Resolution("squat", "Присед", "ambiguous", "", List.of(SQUAT.toString()), "", "", List.of("text:1"));
        when(parser.parse(any())).thenReturn(result(draft(ambiguous)));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("canCommit").asBoolean()).isFalse();
        var chosen = body(call(owner, patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", view.get("version").asLong(), "decisions",
                        List.of(Map.of("key", "squat", "kind", "existing", "exerciseId", SQUAT)))))).andExpect(status().isOk()));
        assertThat(chosen.get("canCommit").asBoolean()).isTrue();
        verify(parser, times(1)).parse(any());
    }

    @Test
    void customExerciseStaysInTheDraftUntilCommitAndIsCopiedToTheAthlete() throws Exception {
        var coach = newSubject();
        link(coach, owner);
        var athlete = userId(owner);
        var coachId = userId(coach);
        when(parser.parse(any())).thenReturn(result(draft(custom("custom", "Альфа", "weight_reps"))));
        var id = create(coach, "Альфа 3×5 100 кг @8", athlete, UUID.randomUUID());
        worker.processOne();
        var view = view(coach, id);
        var exerciseId = UUID.fromString(view.at("/decisions/custom/exerciseId").asString());
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
    void genuineIdCollisionRollsBackEarlierExercisesAndAllFeedEntries() throws Exception {
        when(parser.parse(any())).thenReturn(result(draft(custom("one", "Альфа", "weight_reps"), custom("two", "Омега", "weight_reps"))));
        var id = create(owner, "Альфа и Омега: 3×5 100 кг @8", null, UUID.randomUUID());
        worker.processOne();
        var collision = List.of(ProgramDraftCompiler.id(id, "exercise:one"), ProgramDraftCompiler.id(id, "exercise:two"))
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
        when(parser.parse(any())).thenReturn(result(draft(matched("text:1"))));
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
        when(parser.parse(any())).thenReturn(result(draft(matched("text:1"))));
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
        when(parser.parse(any())).thenAnswer(call -> {
            imports.cancel(user, id);
            return result(draft(matched("text:1")));
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
        when(parser.parse(any())).thenReturn(new ProgramParserModel.Result("{}", 100, 10, 0));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("queued");
        jdbc.update("UPDATE sizylift.import_session SET next_attempt_at=now() WHERE id=?", id);
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("failed");
        verify(parser, times(2)).parse(any());
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
        when(parser.parse(any())).thenReturn(result(draft(matched("csv:1:1"))));
        worker.processOne();
        var id = UUID.fromString(created.get("id").asString());
        assertThat(view(owner, id).get("canCommit").asBoolean()).isTrue();
    }

    @Test
    void missingValuesAndRangesRemainUnknownAndRirIsPreservedAsANote() throws Exception {
        var draft = (ObjectNode) json.valueToTree(draft(matched("text:1")));
        var set = (ObjectNode) draft.at("/programDraft/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0");
        set.put("repetitions", "8-12").put("weightKg", "").put("rpe", "").put("rir", "2");
        when(parser.parse(any())).thenReturn(new ProgramParserModel.Result(draft.toString(), 100, 100, 0));
        var id = create(owner, "Присед 3×8-12 RIR 2", null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("canCommit").asBoolean()).isTrue();
        var actual = view.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0");
        for (var field : List.of("repetitions", "targetWeightGrams", "targetRpeTenths", "restMs"))
            assertThat(actual.get(field).isNull()).as(field).isTrue();
        assertThat(actual.get("note").asString()).contains("8-12", "RIR: 2");
    }

    @Test
    void ambiguousExerciseCanBecomeACustomExerciseWithAStableId() throws Exception {
        var r = new ImportDraftV1.Resolution("squat", "Присед", "ambiguous", "", List.of(SQUAT.toString()), "", "", List.of("text:1"));
        when(parser.parse(any())).thenReturn(result(draft(r)));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        var before = view(owner, id);
        var chosen = body(call(owner, patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", before.get("version").asLong(), "decisions", List.of(
                        Map.of("key", "squat", "kind", "new", "name", "Присед с паузой", "resultType", "weight_reps", "liftClass", "basic"))))))
                .andExpect(status().isOk()));
        assertThat(chosen.get("canCommit").asBoolean()).isTrue();
        assertThat(chosen.at("/decisions/squat/exerciseId").asString()).isEqualTo(ProgramDraftCompiler.id(id, "exercise:squat").toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.exercise WHERE user_id=?", Integer.class, userId(owner))).isZero();
        postJson(owner, BASE + "/" + id + "/commit", Map.of("version", chosen.get("version").asLong())).andExpect(status().isOk());
        verify(parser, times(1)).parse(any());
    }

    @Test
    void excessiveClarificationStopsAndEachRoundContainsAllPreviousAnswers() throws Exception {
        when(parser.parse(any())).thenReturn(result(clarification("Что означает запись?")));
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
        var captured = org.mockito.ArgumentCaptor.forClass(ProgramParserModel.Input.class);
        verify(parser, times(4)).parse(captured.capture());
        assertThat(captured.getAllValues().getLast().history().stream().map(h -> h.answer()).toList())
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
    void untrustedCatalogIdsAndInvalidNumericValuesAreRejected() throws Exception {
        var invalid = (ObjectNode) json.valueToTree(draft(matched("text:1")));
        ((ObjectNode) invalid.at("/exerciseResolutions/0")).put("exerciseId", UUID.randomUUID().toString());
        when(parser.parse(any())).thenReturn(new ProgramParserModel.Result(invalid.toString(), 100, 100, 0));
        var id = create(owner, TEXT, null, UUID.randomUUID());
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("queued");
        var invented = (ObjectNode) json.valueToTree(draft(matched("text:1")));
        ((ObjectNode) invented.at("/programDraft/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0")).put("weightKg", "-987");
        when(parser.parse(any())).thenReturn(new ProgramParserModel.Result(invented.toString(), 100, 100, 0));
        jdbc.update("UPDATE sizylift.import_session SET next_attempt_at=now() WHERE id=?", id);
        worker.processOne();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.program WHERE user_id=?", Integer.class, userId(owner))).isZero();
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
        when(parser.parse(any())).thenReturn(result(draft(matched("text:1"))));
        assertThat(worker.processOne()).isTrue();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("review");
        assertThat(sessions.finish(old, "failed", null, Map.of(), "late", "late", 0, 0, 0, java.time.Instant.now())).isFalse();
        assertThat(view(owner, id).get("status").asString()).isEqualTo("review");
    }

    @Test
    void numbersWrittenAsWordsCanBeInterpretedByTheModel() throws Exception {
        when(parser.parse(any())).thenReturn(result(draft(matched("text:1"))));
        var id = create(owner, "Присед: три подхода по пять повторений со ста килограммами, RPE восемь", null, UUID.randomUUID());
        worker.processOne();
        var view = view(owner, id);
        assertThat(view.get("canCommit").asBoolean()).isTrue();
        assertThat(view.at("/program/cycles/0/weeks/0/workouts/0/blocks/0/exercises/0/sets/0/targetWeightGrams").asInt()).isEqualTo(100000);
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
    private ProgramParserModel.Result result(ImportDraftV1 draft) {
        return new ProgramParserModel.Result(json.writeValueAsString(draft), 1000, 300, 100);
    }
}
