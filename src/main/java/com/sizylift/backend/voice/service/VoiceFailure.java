package com.sizylift.backend.voice.service;

import org.springframework.http.HttpStatus;

/** Только безопасные сообщения: ответы провайдеров, звук и текст речи в ответ и в логи не попадают. */
public class VoiceFailure extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public VoiceFailure(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static VoiceFailure disabled() {
        return new VoiceFailure(HttpStatus.SERVICE_UNAVAILABLE, "voice_disabled", "Голосовой ввод выключен.");
    }
    public static VoiceFailure invalidAudio(String message) {
        return new VoiceFailure(HttpStatus.BAD_REQUEST, "invalid_audio", message);
    }
    public static VoiceFailure invalid(String message) {
        return new VoiceFailure(HttpStatus.BAD_REQUEST, "invalid_voice_request", message);
    }
    public static VoiceFailure limit() {
        return new VoiceFailure(HttpStatus.TOO_MANY_REQUESTS, "voice_daily_limit", "Достигнут суточный лимит голосового ввода.");
    }
    public static VoiceFailure notFound() {
        return new VoiceFailure(HttpStatus.NOT_FOUND, "owner_not_found", "Нет доступа к тренировкам этого пользователя.");
    }
    /** Сервис распознавания не ответил или ответил ошибкой: тренировка не меняется, запись можно отправить снова. */
    public static VoiceFailure recognition() {
        return new VoiceFailure(HttpStatus.BAD_GATEWAY, "recognition_failed", "Не удалось распознать запись. Попробуйте ещё раз.");
    }
}
