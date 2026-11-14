package com.sizylift.backend.voice.service;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.voice.dto.VoiceDtos.ParseResponse;

/**
 * Что видно по ходу одной диктовки. По умолчанию ничего не делает: звук и текст речи не хранятся
 * (docs/voice.md). Отладочная запись — {@link VoiceDebugTrace}, только вне production.
 */
public interface VoiceTrace {
    VoiceTrace NONE = new VoiceTrace() { };

    /** Запись, как она пришла от клиента. */
    default void audio(UUID requestId, byte[] audio, String contentType) { }

    /** Текст от распознавания речи и подсказки словаря, с которыми он получен. */
    default void transcript(UUID requestId, String text, List<String> keyterms, long nanos) { }

    /** Разбор текста до запасной модели. */
    default void parsed(UUID requestId, ParseResponse beforeFallback) { }

    /** Вход и ответ запасной модели (только если она вызывалась). */
    default void fallback(UUID requestId, String input, String arguments) { }

    /** Итог, который уходит клиенту. */
    default void response(UUID requestId, ParseResponse response, VoicePipeline.Fallback fallback) { }

    /** Распознавание не удалось: запись остаётся для разбора. */
    default void failed(UUID requestId, RuntimeException error) { }
}
