package com.sizylift.backend.voice.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.sizylift.backend.voice.config.VoiceProperties;
import com.sizylift.backend.voice.dto.VoiceDtos.ParseResponse;
import org.springframework.stereotype.Component;

/**
 * Повтор той же записи (тот же requestId после обрыва связи) получает готовый ответ без второго платного
 * распознавания. Ответы держатся только в памяти: в них есть слова речи, а речь не хранится. Production —
 * один экземпляр сервера; при нескольких повтор на другой экземпляр просто распознается заново.
 */
@Component
public class VoiceIdempotency {
    private record Key(UUID caller, UUID requestId) { }

    private record Entry(CompletableFuture<ParseResponse> result, long expiresAt) { }

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final int maxEntries;

    public VoiceIdempotency(VoiceProperties properties) {
        this.ttlNanos = properties.idempotencyTtl().toNanos();
        this.maxEntries = properties.idempotencyEntries();
    }

    public ParseResponse run(UUID caller, UUID requestId, Supplier<ParseResponse> work) {
        long now = System.nanoTime();
        if (entries.size() >= maxEntries) entries.values().removeIf(e -> e.expiresAt() - now < 0);
        var key = new Key(caller, requestId);
        var mine = new Entry(new CompletableFuture<>(), now + ttlNanos);
        var existing = entries.compute(key, (k, e) -> e == null || e.expiresAt() - now < 0 ? mine : e);
        if (existing != mine) return join(existing.result());
        try {
            var response = work.get();
            mine.result().complete(response);
            return response;
        } catch (RuntimeException e) {
            // Сбой не запоминается: запись можно отправить снова.
            entries.remove(key, mine);
            mine.result().completeExceptionally(e);
            throw e;
        }
    }

    private static ParseResponse join(CompletableFuture<ParseResponse> result) {
        try {
            return result.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }
}
