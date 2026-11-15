package com.sizylift.backend.voice.service;

import java.util.UUID;

import java.util.List;

import com.sizylift.backend.voice.config.VoiceProperties;
import com.sizylift.backend.voice.dto.VoiceDtos.ParseResponse;
import com.sizylift.backend.voice.model.GapResolver;
import com.sizylift.backend.voice.model.SpeechRecognizer;
import com.sizylift.backend.voice.model.TemplateNormalizer;
import com.sizylift.backend.voice.parser.WorkoutTextParser;
import com.sizylift.backend.voice.template.TemplateParser;
import com.sizylift.backend.voice.template.TemplatePrompt;
import tools.jackson.databind.ObjectMapper;

/**
 * Звук → текст → разбор → ответ. Разбор по флагу {@code sizylift.voice.parser}: алгоритм с запасной моделью
 * для непонятых кусков (только если они есть) или модель, которая переписывает фразу в строгий шаблон, и
 * строгий разборщик шаблона (docs/voice.md, «Разбор шаблоном»).
 * Без базы и Spring: собирается в {@link com.sizylift.backend.voice.config.VoiceConfiguration} и в живых тестах.
 * Текст речи живёт только внутри вызова.
 */
public class VoicePipeline {
    /** {@code TEMPLATE} — разбор моделью шаблона, запасная модель не участвует. */
    public enum Fallback { SKIPPED, DISABLED, RESOLVED, PARTIAL, UNRESOLVED, FAILED, TEMPLATE }

    /** Ответ и время этапов в наносекундах — для метрик. */
    public record Outcome(ParseResponse response, long recognitionNanos, long parseNanos, long fallbackNanos,
                          Fallback fallback, int inputTokens, int outputTokens) { }

    private final SpeechRecognizer recognizer;
    private final GapResolver resolver;
    private final ObjectMapper json;
    private final VoiceTrace trace;
    private final VoiceProperties.Parser parser;
    private final TemplateNormalizer normalizer;

    public VoicePipeline(SpeechRecognizer recognizer, GapResolver resolver, ObjectMapper json) {
        this(recognizer, resolver, json, VoiceTrace.NONE);
    }

    public VoicePipeline(SpeechRecognizer recognizer, GapResolver resolver, ObjectMapper json, VoiceTrace trace) {
        this(recognizer, resolver, json, trace, VoiceProperties.Parser.ALGORITHM, null);
    }

    public VoicePipeline(SpeechRecognizer recognizer, GapResolver resolver, ObjectMapper json, VoiceTrace trace,
                         VoiceProperties.Parser parser, TemplateNormalizer normalizer) {
        if (parser == VoiceProperties.Parser.MODEL && normalizer == null) {
            throw new IllegalArgumentException("Для разбора моделью нужна модель шаблона");
        }
        this.recognizer = recognizer;
        this.resolver = resolver;
        this.json = json;
        this.trace = trace;
        this.parser = parser;
        this.normalizer = normalizer;
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
        if (parser == VoiceProperties.Parser.MODEL && !text.isBlank()) return fromTemplate(requestId, ownerId, text, catalog);
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

    /**
     * Модель переписывает фразу в шаблон, строгий разборщик превращает его в ответ. Модель не ответила —
     * {@link VoiceFailure#recognition()}: тренировка не меняется, запись можно отправить снова.
     */
    private Outcome fromTemplate(UUID requestId, UUID ownerId, String text, VoiceCatalog catalog) {
        long start = System.nanoTime();
        TemplateNormalizer.Result result;
        try {
            result = normalizer.normalize(TemplatePrompt.build(catalog.prompt()), text);
        } catch (TemplateNormalizer.Unavailable e) {
            var failure = VoiceFailure.recognition();
            trace.failed(requestId, failure);
            throw failure;
        }
        long normalized = System.nanoTime();
        var parsed = TemplateParser.parse(result.template(), catalog);
        trace.template(requestId, result.template(), parsed.skipped(), result.inputTokens(), result.cachedInputTokens(),
                result.outputTokens());
        var response = new ParseResponse(requestId, ownerId, true, parsed.items(), List.of());
        return new Outcome(response, 0, System.nanoTime() - normalized, normalized - start, Fallback.TEMPLATE,
                result.inputTokens(), result.outputTokens());
    }

    private static Outcome outcome(VoiceDraft draft, UUID requestId, UUID ownerId, long parse, long fallback,
                                   Fallback kind, GapResolver.Result result) {
        return new Outcome(draft.toResponse(requestId, ownerId, true), 0, parse, fallback, kind,
                result == null ? 0 : result.inputTokens(), result == null ? 0 : result.outputTokens());
    }
}
