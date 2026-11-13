package com.sizylift.backend.voice.service;

import java.util.UUID;

import com.sizylift.backend.voice.dto.VoiceDtos.ParseResponse;
import com.sizylift.backend.voice.model.GapResolver;
import com.sizylift.backend.voice.model.SpeechRecognizer;
import com.sizylift.backend.voice.parser.WorkoutTextParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Звук → текст → разбор → запасная модель для непонятых кусков (только если они есть) → ответ.
 * Без базы и Spring: собирается в {@link com.sizylift.backend.voice.config.VoiceConfiguration} и в живых тестах.
 * Текст речи живёт только внутри вызова.
 */
public class VoicePipeline {
    public enum Fallback { SKIPPED, DISABLED, RESOLVED, PARTIAL, UNRESOLVED, FAILED }

    /** Ответ и время этапов в наносекундах — для метрик. */
    public record Outcome(ParseResponse response, long recognitionNanos, long parseNanos, long fallbackNanos,
                          Fallback fallback, int inputTokens, int outputTokens) { }

    private final SpeechRecognizer recognizer;
    private final GapResolver resolver;
    private final ObjectMapper json;

    public VoicePipeline(SpeechRecognizer recognizer, GapResolver resolver, ObjectMapper json) {
        this.recognizer = recognizer;
        this.resolver = resolver;
        this.json = json;
    }

    public Outcome run(UUID requestId, UUID ownerId, byte[] audio, String contentType, VoiceCatalog catalog) {
        long start = System.nanoTime();
        var text = recognizer.transcribe(audio, contentType, catalog.keyterms());
        long recognized = System.nanoTime();
        var outcome = fromText(requestId, ownerId, text, catalog);
        return new Outcome(outcome.response(), recognized - start, outcome.parseNanos(), outcome.fallbackNanos(),
                outcome.fallback(), outcome.inputTokens(), outcome.outputTokens());
    }

    /** Всё после распознавания: разбор и, при непонятых кусках, запасная модель. */
    public Outcome fromText(UUID requestId, UUID ownerId, String text, VoiceCatalog catalog) {
        long start = System.nanoTime();
        if (text.isBlank()) return new Outcome(VoiceDraft.of(new WorkoutTextParser(catalog.dictionary()).parse(""))
                .toResponse(requestId, ownerId, false), 0, System.nanoTime() - start, 0, Fallback.SKIPPED, 0, 0);
        var draft = VoiceDraft.of(new WorkoutTextParser(catalog.dictionary()).parse(text));
        long parsed = System.nanoTime();
        if (!draft.hasGaps()) return outcome(draft, requestId, ownerId, parsed - start, 0, Fallback.SKIPPED, null);
        if (!resolver.enabled()) return outcome(draft, requestId, ownerId, parsed - start, 0, Fallback.DISABLED, null);
        var gaps = GapMerger.gaps(draft);
        try {
            var result = resolver.resolve(GapMerger.render(text, draft, gaps, catalog));
            if (result == null) throw new GapResolver.Unavailable();
            int resolved = GapMerger.apply(json, result.arguments(), gaps, draft, catalog);
            var fallback = resolved == gaps.size() ? Fallback.RESOLVED : resolved > 0 ? Fallback.PARTIAL : Fallback.UNRESOLVED;
            return outcome(draft, requestId, ownerId, parsed - start, System.nanoTime() - parsed, fallback, result);
        } catch (GapResolver.Unavailable e) {
            return outcome(draft, requestId, ownerId, parsed - start, System.nanoTime() - parsed, Fallback.FAILED, null);
        }
    }

    private static Outcome outcome(VoiceDraft draft, UUID requestId, UUID ownerId, long parse, long fallback,
                                   Fallback kind, GapResolver.Result result) {
        return new Outcome(draft.toResponse(requestId, ownerId, true), 0, parse, fallback, kind,
                result == null ? 0 : result.inputTokens(), result == null ? 0 : result.outputTokens());
    }
}
