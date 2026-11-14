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
    private final VoiceTrace trace;

    public VoicePipeline(SpeechRecognizer recognizer, GapResolver resolver, ObjectMapper json) {
        this(recognizer, resolver, json, VoiceTrace.NONE);
    }

    public VoicePipeline(SpeechRecognizer recognizer, GapResolver resolver, ObjectMapper json, VoiceTrace trace) {
        this.recognizer = recognizer;
        this.resolver = resolver;
        this.json = json;
        this.trace = trace;
    }

    public Outcome run(UUID requestId, UUID ownerId, byte[] audio, String contentType, VoiceCatalog catalog) {
        long start = System.nanoTime();
        trace.audio(requestId, audio, contentType);
        String text;
        try {
            text = recognizer.transcribe(audio, contentType, catalog.keyterms());
        } catch (RuntimeException e) {
            trace.failed(requestId, e);
            throw e;
        }
        long recognized = System.nanoTime();
        trace.transcript(requestId, text, catalog.keyterms(), recognized - start);
        var outcome = fromText(requestId, ownerId, text, catalog);
        trace.response(requestId, outcome.response(), outcome.fallback());
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
        trace.parsed(requestId, draft.toResponse(requestId, ownerId, true));
        if (!draft.hasGaps()) return outcome(draft, requestId, ownerId, parsed - start, 0, Fallback.SKIPPED, null);
        if (!resolver.enabled()) return outcome(draft, requestId, ownerId, parsed - start, 0, Fallback.DISABLED, null);
        var gaps = GapMerger.gaps(draft);
        try {
            var input = GapMerger.render(text, draft, gaps, catalog);
            var result = resolver.resolve(input);
            if (result == null) throw new GapResolver.Unavailable();
            trace.fallback(requestId, input, result.arguments());
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
