package com.sizylift.backend.voice.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.voice.config.VoiceProperties;
import com.sizylift.backend.voice.dto.VoiceDtos.AliasRequest;
import com.sizylift.backend.voice.dto.VoiceDtos.ParseRequest;
import com.sizylift.backend.voice.dto.VoiceDtos.ParseResponse;
import com.sizylift.backend.voice.dto.VoiceDtos.PendingExercise;
import com.sizylift.backend.voice.parser.TextFacts;
import com.sizylift.backend.voice.repository.VoiceRepository;
import com.sizylift.backend.web.repository.WebReadRepository;
import org.springframework.stereotype.Service;

/**
 * Синхронное распознавание диктовки без очереди. Порядок: доступ к упражнениям владельца, повтор той же
 * записи, суточный лимит, каталог, пайплайн. Транзакция БД не держится во время вызовов провайдеров.
 */
@Service
public class VoiceParseService {
    private static final Set<String> RESULT_TYPES = Set.of("weight_reps", "added_weight_reps", "duration", "distance_time", "free");
    private static final String AUDIO = "audio/";
    /** Без явного типа звука Deepgram определяет формат сам; m4a с iOS иногда приходит как video/mp4. */
    private static final Set<String> AUDIO_CONTAINERS = Set.of("application/octet-stream", "video/mp4", "video/webm");
    /** Старые личные синонимы, сверх этого числа, в словарь не попадают. */
    private static final int MAX_USER_ALIASES = 500;

    private final VoiceProperties properties;
    private final AccessPolicy access;
    private final WebReadRepository catalog;
    private final VoiceRepository repository;
    private final VoicePipeline pipeline;
    private final VoiceIdempotency idempotency;
    private final VoiceMetrics metrics;
    private final Clock clock;
    private volatile Map<UUID, List<String>> systemAliases;

    public VoiceParseService(VoiceProperties properties, AccessPolicy access, WebReadRepository catalog,
                             VoiceRepository repository, VoicePipeline pipeline, VoiceIdempotency idempotency,
                             VoiceMetrics metrics, Clock clock) {
        this.properties = properties;
        this.access = access;
        this.catalog = catalog;
        this.repository = repository;
        this.pipeline = pipeline;
        this.idempotency = idempotency;
        this.metrics = metrics;
        this.clock = clock;
    }

    public ParseResponse parse(UUID caller, ParseRequest request, byte[] audio, String contentType) {
        if (!properties.enabled()) {
            metrics.outcome("disabled");
            throw VoiceFailure.disabled();
        }
        if (audio.length == 0) throw VoiceFailure.invalidAudio("Пустая запись.");
        if (audio.length > properties.maxAudioBytes()) throw VoiceFailure.invalidAudio("Запись слишком длинная.");
        var type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!type.startsWith(AUDIO) && !AUDIO_CONTAINERS.contains(type)) throw VoiceFailure.invalidAudio("Неподдерживаемый формат записи.");
        var owner = owner(caller, request.ownerId());
        return idempotency.run(caller, request.requestId(), () -> recognize(caller, owner, request, audio, type));
    }

    /** Замена надиктованного упражнения: как оно было сказано — личный синоним вызывающего. */
    public void rememberAlias(UUID caller, AliasRequest request) {
        if (!properties.enabled()) throw VoiceFailure.disabled();
        owner(caller, request.ownerId());
        var key = TextFacts.aliasKey(request.spoken());
        if (key.isBlank()) throw VoiceFailure.invalid("В названии нет слов.");
        repository.saveUserAlias(caller, key, request.spoken().strip(), request.exerciseId(), clock.instant());
    }

    private ParseResponse recognize(UUID caller, UUID owner, ParseRequest request, byte[] audio, String type) {
        if (!repository.consume(caller, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), properties.dailyLimit())) {
            metrics.outcome("limit");
            throw VoiceFailure.limit();
        }
        long start = System.nanoTime();
        var voiceCatalog = catalog(caller, owner, request.pendingExercises());
        metrics.audio(audio.length);
        VoicePipeline.Outcome outcome;
        try {
            outcome = pipeline.run(request.requestId(), owner, audio, type, voiceCatalog);
        } catch (VoiceFailure e) {
            metrics.outcome("recognition_failed");
            throw e;
        }
        metrics.stage("recognition", outcome.recognitionNanos());
        metrics.stage("parse", outcome.parseNanos());
        if (outcome.fallbackNanos() > 0) metrics.stage("fallback", outcome.fallbackNanos());
        metrics.stage("total", System.nanoTime() - start);
        metrics.fallback(outcome.fallback(), outcome.inputTokens(), outcome.outputTokens());
        metrics.outcome(outcome.response().speechDetected() ? "ok" : "no_speech");
        return outcome.response();
    }

    private VoiceCatalog catalog(UUID caller, UUID owner, List<PendingExercise> pending) {
        var sources = new ArrayList<VoiceCatalog.Source>();
        for (var e : catalog.exercises(caller, owner)) {
            sources.add(new VoiceCatalog.Source(e.id(), e.ownerId() != null, e.name(), e.shortName(), e.resultType()));
        }
        for (var p : pending == null ? List.<PendingExercise>of() : pending) {
            if (RESULT_TYPES.contains(p.resultType()) && sources.stream().noneMatch(s -> s.id().equals(p.id()))) {
                sources.add(new VoiceCatalog.Source(p.id(), true, p.name().strip(), null, p.resultType()));
            }
        }
        return VoiceCatalog.build(sources, systemAliases(), repository.userAliases(caller, MAX_USER_ALIASES),
                properties.keytermChars());
    }

    /** Системные синонимы меняются только миграцией: читаются один раз. */
    private Map<UUID, List<String>> systemAliases() {
        var aliases = systemAliases;
        if (aliases == null) systemAliases = aliases = repository.systemAliases();
        return aliases;
    }

    private UUID owner(UUID caller, UUID owner) {
        var target = owner == null ? caller : owner;
        if (!access.canRead(caller, target, AggregateType.EXERCISE)) throw VoiceFailure.notFound();
        return target;
    }
}
