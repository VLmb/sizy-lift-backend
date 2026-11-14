package com.sizylift.backend.voice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Голосовой ввод (docs/voice.md). {@code keytermChars} — бюджет подсказок словаря для Deepgram: у провайдера
 * лимит 500 токенов на все подсказки, 900 символов кириллицы в него укладываются с запасом.
 */
@ConfigurationProperties("sizylift.voice")
public record VoiceProperties(@DefaultValue("false") boolean enabled, @DefaultValue("100") int dailyLimit,
        @DefaultValue("5000000") int maxAudioBytes, @DefaultValue("10m") Duration idempotencyTtl,
        @DefaultValue("10000") int idempotencyEntries, @DefaultValue("900") int keytermChars,
        @DefaultValue Deepgram deepgram, @DefaultValue Fallback fallback, @DefaultValue Debug debug) {

    public record Deepgram(@DefaultValue("") String apiKey, @DefaultValue("https://api.deepgram.com") String url,
                           @DefaultValue("nova-3") String model, @DefaultValue("ru") String language,
                           @DefaultValue("15s") Duration timeout) { }

    /** Запасная модель для непонятых кусков: вызывается, только если они есть. */
    public record Fallback(@DefaultValue("true") boolean enabled, @DefaultValue("") String apiKey,
                           @DefaultValue("https://api.deepseek.com") String url,
                           @DefaultValue("deepseek-flash") String model, @DefaultValue("4s") Duration timeout,
                           @DefaultValue("1024") int maxOutputTokens) { }

    /**
     * Отладка (docs/voice-debug.md): текст речи, разбор и ответ — в лог {@code com.sizylift.backend.voice.debug},
     * записи — файлами в {@code audioDir}. Только локально: в профиле prod сервер с ней не стартует.
     */
    public record Debug(@DefaultValue("false") boolean enabled, @DefaultValue("") String audioDir) { }

    public VoiceProperties {
        if (dailyLimit < 1 || maxAudioBytes < 1 || idempotencyTtl.isNegative() || idempotencyEntries < 1
                || keytermChars < 0 || deepgram.timeout().isNegative() || fallback.timeout().isNegative()
                || fallback.maxOutputTokens() < 1) {
            throw new IllegalArgumentException("Некорректные настройки голосового ввода");
        }
    }
}
