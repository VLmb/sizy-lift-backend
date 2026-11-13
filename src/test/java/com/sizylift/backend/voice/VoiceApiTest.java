package com.sizylift.backend.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.voice.model.GapResolver;
import com.sizylift.backend.voice.model.SpeechRecognizer;
import com.sizylift.backend.voice.parser.ExerciseDictionary;
import com.sizylift.backend.voice.service.VoiceFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API голосового ввода на настоящем PostgreSQL; распознавание и модель подменены — сеть не нужна. */
@SpringBootTest(properties = {"sizylift.voice.enabled=true", "sizylift.voice.daily-limit=4",
        "sizylift.voice.deepgram.api-key=test-only", "sizylift.voice.deepgram.url=http://127.0.0.1",
        "sizylift.voice.fallback.api-key=test-only", "sizylift.voice.fallback.url=http://127.0.0.1"})
@AutoConfigureMockMvc
class VoiceApiTest extends CoachWorkspaceApiSupport {
    private static final String PARSE = "/api/v2/voice/workout-parse";
    private static final String ALIASES = "/api/v2/voice/aliases";
    private static final String SQUAT = "14000000-0000-4000-8000-000000000001";
    private static final String BENCH = "14000000-0000-4000-8000-000000000002";
    private static final String HACK = "14000000-0000-4000-8000-000000000042";
    private static final byte[] AUDIO = {1, 2, 3};

    @MockitoBean SpeechRecognizer recognizer;
    @MockitoBean GapResolver resolver;
    String user;

    @BeforeEach
    void setup() {
        user = newSubject();
        reset(recognizer, resolver);
        when(resolver.enabled()).thenReturn(true);
        when(resolver.resolve(anyString())).thenThrow(new GapResolver.Unavailable());
    }

    @Test
    void dictationBecomesExercisesWithSetsWithoutModel() throws Exception {
        speech("Присед три по пять на сто, отдых две минуты, потом жим лёжа четыре по восемь на шестьдесят");
        var body = body(parse(user, UUID.randomUUID(), null, List.of()).andExpect(status().isOk()));
        assertThat(body.get("speechDetected").asBoolean()).isTrue();
        var squat = body.at("/items/0");
        assertThat(squat.get("exerciseId").asString()).isEqualTo(SQUAT);
        assertThat(squat.get("confidence").asString()).isEqualTo("high");
        assertThat(squat.get("restSeconds").asInt()).isEqualTo(120);
        assertThat(squat.at("/lines/0/count").asInt()).isEqualTo(3);
        assertThat(squat.at("/lines/0/reps").asInt()).isEqualTo(5);
        assertThat(squat.at("/lines/0/weightKg").asDouble()).isEqualTo(100.0);
        assertThat(body.at("/items/1/exerciseId").asString()).isEqualTo(BENCH);
        assertThat(body.at("/items/1/restSeconds").isNull()).isTrue();
        verify(resolver, never()).resolve(anyString());
    }

    @Test
    void unknownExerciseIsAskedFromModelAndCheckedAnswerApplied() throws Exception {
        speech("Жим арнольда четыре по десять по шестнадцать");
        doReturn(new GapResolver.Result("""
                {"gaps":[{"gap":"G1","kind":"exercise","match":"","newName":"Жим Арнольда","resultType":"weight_reps","note":"","sets":[]}]}""", 1, 1))
                .when(resolver).resolve(anyString());
        var item = body(parse(user, UUID.randomUUID(), null, List.of()).andExpect(status().isOk())).at("/items/0");
        assertThat(item.get("exerciseId").isNull()).isTrue();
        assertThat(item.at("/proposal/name").asString()).isEqualTo("Жим Арнольда");
        assertThat(item.at("/proposal/source").asString()).isEqualTo("model");
        assertThat(item.at("/lines/0/weightKg").asDouble()).isEqualTo(16.0);
        assertThat(item.get("candidates").size()).isPositive();
    }

    @Test
    void repeatedRequestIdIsRecognizedOnceAndFailureCanBeRetried() throws Exception {
        var request = UUID.randomUUID();
        when(recognizer.transcribe(any(), anyString(), any())).thenThrow(VoiceFailure.recognition())
                .thenReturn("присед пять по пять");
        assertThat(body(parse(user, request, null, List.of()).andExpect(status().isBadGateway())).get("code").asString())
                .isEqualTo("recognition_failed");
        var first = body(parse(user, request, null, List.of()).andExpect(status().isOk()));
        var second = body(parse(user, request, null, List.of()).andExpect(status().isOk()));
        assertThat(second).isEqualTo(first);
        verify(recognizer, times(2)).transcribe(any(), anyString(), any());
    }

    @Test
    void dailyLimitStopsRecognition() throws Exception {
        speech("присед пять по пять");
        for (int i = 0; i < 4; i++) parse(user, UUID.randomUUID(), null, List.of()).andExpect(status().isOk());
        var refused = body(parse(user, UUID.randomUUID(), null, List.of()).andExpect(status().isTooManyRequests()));
        assertThat(refused.get("code").asString()).isEqualTo("voice_daily_limit");
        verify(recognizer, times(4)).transcribe(any(), anyString(), any());
    }

    @Test
    void coachDictatesForAthleteAndStrangerCannot() throws Exception {
        var coach = newSubject();
        link(coach, user);
        var athlete = userId(user);
        speech("гакк три по десять на сто");
        var body = body(parse(coach, UUID.randomUUID(), athlete, List.of()).andExpect(status().isOk()));
        assertThat(body.get("ownerId").asString()).isEqualTo(athlete.toString());
        assertThat(body.at("/items/0/exerciseId").asString()).isEqualTo(HACK);
        assertThat(body(parse(newSubject(), UUID.randomUUID(), athlete, List.of()).andExpect(status().isNotFound()))
                .get("code").asString()).isEqualTo("owner_not_found");
    }

    @Test
    void replacedNameIsRememberedAsPersonalSynonym() throws Exception {
        speech("хакен три по десять на сто");
        assertThat(body(parse(user, UUID.randomUUID(), null, List.of()).andExpect(status().isOk()))
                .at("/items/0/exerciseId").isNull()).isTrue();
        postJson(user, ALIASES, Map.of("exerciseId", HACK, "spoken", "хакен")).andExpect(status().isNoContent());
        var item = body(parse(user, UUID.randomUUID(), null, List.of()).andExpect(status().isOk())).at("/items/0");
        assertThat(item.get("exerciseId").asString()).isEqualTo(HACK);
        assertThat(item.get("confidence").asString()).isEqualTo("high");
    }

    @Test
    void ownExerciseNotYetSyncedIsRecognized() throws Exception {
        var own = UUID.randomUUID();
        speech("жим арнольда четыре по десять по шестнадцать");
        var item = body(parse(user, UUID.randomUUID(), null,
                List.of(Map.of("id", own.toString(), "name", "Жим Арнольда", "resultType", "weight_reps")))
                .andExpect(status().isOk())).at("/items/0");
        assertThat(item.get("exerciseId").asString()).isEqualTo(own.toString());
        verify(recognizer).transcribe(any(), anyString(), org.mockito.ArgumentMatchers.argThat(
                (List<String> terms) -> terms.getFirst().equals("Жим Арнольда")));
    }

    @Test
    void badAudioIsRejectedBeforeRecognition() throws Exception {
        var metadata = metadata(UUID.randomUUID(), null, List.of());
        upload(user, multipart(PARSE).file(new MockMultipartFile("audio", "a.txt", "text/plain", AUDIO)).file(metadata))
                .andExpect(status().isBadRequest());
        upload(user, multipart(PARSE).file(new MockMultipartFile("audio", "a.m4a", "audio/mp4", new byte[0])).file(metadata))
                .andExpect(status().isBadRequest());
        verify(recognizer, never()).transcribe(any(), anyString(), any());
    }

    /** Миграция 037 и черновик синонимов, по которому идут тесты разбора, не расходятся. */
    @Test
    void systemSynonymsInDatabaseMatchDictionaryDraft() {
        var expected = new HashMap<String, List<String>>();
        for (var e : ExerciseDictionary.loadSystem()) {
            var synonyms = new ArrayList<>(e.aliases().subList(2, e.aliases().size()));
            synonyms.sort(null);
            if (!synonyms.isEmpty()) expected.put(e.id(), synonyms);
        }
        var actual = new HashMap<String, List<String>>();
        jdbc.query("SELECT exercise_id, alias FROM sizylift.voice_exercise_alias ORDER BY exercise_id, alias", row -> {
            actual.computeIfAbsent(row.getString(1), k -> new ArrayList<>()).add(row.getString(2));
        });
        actual.values().forEach(l -> l.sort(null));
        assertThat(actual).isEqualTo(expected);
    }

    private void speech(String text) {
        when(recognizer.transcribe(any(), anyString(), any())).thenReturn(text);
    }

    private ResultActions parse(String subject, UUID request, UUID owner, List<Map<String, String>> pending) throws Exception {
        return upload(subject, multipart(PARSE).file(new MockMultipartFile("audio", "dictation.m4a", "audio/mp4", AUDIO))
                .file(metadata(request, owner, pending)));
    }

    private ResultActions upload(String subject, MockMultipartHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(jwt().jwt(t -> t.issuer(ISSUER).subject(subject).claim("email", subject + "@example.com"))));
    }

    private MockMultipartFile metadata(UUID request, UUID owner, List<Map<String, String>> pending) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("requestId", request.toString());
        if (owner != null) body.put("ownerId", owner.toString());
        body.put("pendingExercises", pending);
        return new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE, json.writeValueAsBytes(body));
    }
}
