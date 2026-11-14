package com.sizylift.backend.voice.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sizylift.backend.voice.dto.VoiceDtos.ParseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Отладка голосового ввода (sizylift.voice.debug, docs/voice-debug.md): логгер
 * {@code com.sizylift.backend.voice.debug} пишет текст речи, разбор до запасной модели, её вход и ответ и итог;
 * при заданной папке каждая запись сохраняется файлом {@code <время>_<requestId>.<расширение>}, рядом —
 * {@code .json} со всем остальным. В production не включается: это слова пользователя.
 */
public class VoiceDebugTrace implements VoiceTrace {
    private static final Logger log = LoggerFactory.getLogger("com.sizylift.backend.voice.debug");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ObjectMapper json;
    private final Path directory;
    private final Clock clock;
    private final Map<UUID, Map<String, Object>> open = new ConcurrentHashMap<>();

    public VoiceDebugTrace(ObjectMapper json, Path directory, Clock clock) {
        this.json = json;
        this.directory = directory;
        this.clock = clock;
    }

    @Override
    public void audio(UUID requestId, byte[] audio, String contentType) {
        var record = record(requestId);
        var name = STAMP.format(clock.instant()) + "_" + requestId;
        record.put("file", name);
        record.put("contentType", contentType);
        record.put("bytes", audio.length);
        log.info("voice {} audio: {} bytes, {}", requestId, audio.length, contentType);
        if (directory == null) return;
        try {
            Files.createDirectories(directory);
            var path = directory.resolve(name + "." + extension(contentType));
            Files.write(path, audio);
            log.info("voice {} audio saved: {}", requestId, path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("voice {} audio not saved: {}", requestId, e.toString());
        }
    }

    @Override
    public void transcript(UUID requestId, String text, List<String> keyterms, long nanos) {
        var record = record(requestId);
        record.put("transcript", text);
        record.put("keyterms", keyterms);
        record.put("recognitionMs", nanos / 1_000_000);
        log.info("voice {} transcript ({} ms): «{}»", requestId, nanos / 1_000_000, text);
        log.debug("voice {} keyterms: {}", requestId, keyterms);
    }

    @Override
    public void parsed(UUID requestId, ParseResponse beforeFallback) {
        record(requestId).put("parsed", beforeFallback);
        log.info("voice {} parsed: {}", requestId, json.writeValueAsString(beforeFallback));
    }

    @Override
    public void fallback(UUID requestId, String input, String arguments) {
        var record = record(requestId);
        record.put("fallbackInput", input);
        record.put("fallbackAnswer", arguments);
        log.info("voice {} fallback input:\n{}", requestId, input);
        log.info("voice {} fallback answer: {}", requestId, arguments);
    }

    @Override
    public void response(UUID requestId, ParseResponse response, VoicePipeline.Fallback fallback) {
        var record = record(requestId);
        record.put("fallback", fallback.name());
        record.put("response", response);
        log.info("voice {} response (fallback {}): {}", requestId, fallback, json.writeValueAsString(response));
        close(requestId);
    }

    @Override
    public void failed(UUID requestId, RuntimeException error) {
        record(requestId).put("error", error.toString());
        log.info("voice {} failed: {}", requestId, error.toString());
        close(requestId);
    }

    private Map<String, Object> record(UUID requestId) {
        return open.computeIfAbsent(requestId, id -> {
            var record = new LinkedHashMap<String, Object>();
            record.put("requestId", id);
            record.put("at", clock.instant().toString());
            return java.util.Collections.synchronizedMap(record);
        });
    }

    private void close(UUID requestId) {
        var record = open.remove(requestId);
        if (record == null || directory == null || !(record.get("file") instanceof String name)) return;
        try {
            Files.writeString(directory.resolve(name + ".json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(record));
        } catch (IOException | RuntimeException e) {
            log.warn("voice {} trace not saved: {}", requestId, e.toString());
        }
    }

    static String extension(String contentType) {
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("webm")) return "webm";
        if (type.contains("ogg")) return "ogg";
        if (type.contains("mp4") || type.contains("m4a") || type.contains("aac")) return "m4a";
        if (type.contains("wav")) return "wav";
        if (type.contains("mpeg") || type.contains("mp3")) return "mp3";
        return "bin";
    }
}
