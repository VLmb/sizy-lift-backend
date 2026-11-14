package com.sizylift.backend.voice.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.sizylift.backend.voice.model.GapResolver;
import com.sizylift.backend.voice.model.SpeechRecognizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Отладочная запись диктовки: звук файлом, рядом — текст речи, разбор и ответ. */
class VoiceDebugTraceTest {
    private static final UUID REQUEST = UUID.fromString("70000000-0000-4000-8000-0000000000d1");
    private static final UUID OWNER = UUID.fromString("70000000-0000-4000-8000-0000000000d2");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T08:30:00Z"), ZoneOffset.UTC);
    private static final GapResolver NO_MODEL = new GapResolver() {
        @Override public Result resolve(String input) { throw new Unavailable(); }
        @Override public boolean enabled() { return false; }
    };

    @Test
    void savesTheRecordingWithTranscriptParseAndResponse(@TempDir Path directory) throws Exception {
        var json = JsonMapper.builder().build();
        SpeechRecognizer recognizer = (audio, type, keyterms) -> "Присед три по пять на сто, отдых две минуты";
        var pipeline = new VoicePipeline(recognizer, NO_MODEL, json, new VoiceDebugTrace(json, directory, CLOCK));

        pipeline.run(REQUEST, OWNER, new byte[] {1, 2, 3}, "audio/webm;codecs=opus", VoiceTestCatalog.system());

        var base = "20261114-083000_" + REQUEST;
        assertThat(Files.readAllBytes(directory.resolve(base + ".webm"))).containsExactly(1, 2, 3);
        var trace = json.readTree(Files.readString(directory.resolve(base + ".json")));
        assertThat(trace.get("transcript").asString()).isEqualTo("Присед три по пять на сто, отдых две минуты");
        assertThat(trace.get("contentType").asString()).isEqualTo("audio/webm;codecs=opus");
        assertThat(trace.get("parsed").get("items").get(0).get("spoken").asString()).isEqualTo("присед");
        assertThat(trace.get("response").get("items").get(0).get("restSeconds").asInt()).isEqualTo(120);
        assertThat(trace.get("fallback").asString()).isEqualTo("SKIPPED");
    }

    @Test
    void keepsTheRecordingWhenRecognitionFails(@TempDir Path directory) throws Exception {
        var json = JsonMapper.builder().build();
        SpeechRecognizer recognizer = (audio, type, keyterms) -> { throw VoiceFailure.recognition(); };
        var pipeline = new VoicePipeline(recognizer, NO_MODEL, json, new VoiceDebugTrace(json, directory, CLOCK));

        assertThatThrownBy(() -> pipeline.run(REQUEST, OWNER, new byte[] {9}, "audio/mp4", VoiceTestCatalog.system()))
                .isInstanceOf(VoiceFailure.class);

        var base = "20261114-083000_" + REQUEST;
        assertThat(directory.resolve(base + ".m4a")).exists();
        assertThat(Files.readString(directory.resolve(base + ".json"))).contains("\"error\"");
    }

    @Test
    void extensionFollowsTheContentType() {
        assertThat(VoiceDebugTrace.extension("audio/mp4")).isEqualTo("m4a");
        assertThat(VoiceDebugTrace.extension("video/mp4")).isEqualTo("m4a");
        assertThat(VoiceDebugTrace.extension("audio/ogg;codecs=opus")).isEqualTo("ogg");
        assertThat(VoiceDebugTrace.extension("application/octet-stream")).isEqualTo("bin");
    }
}
