package com.sizylift.backend.voice.model;

import java.util.List;

/** Сервис распознавания речи за интерфейсом: провайдер выбирается конфигурацией. */
public interface SpeechRecognizer {
    /**
     * Текст речи или пустая строка, если речи нет. {@code keyterms} — подсказка словаря: названия упражнений.
     * При сбое — {@link com.sizylift.backend.voice.service.VoiceFailure#recognition()}.
     */
    String transcribe(byte[] audio, String contentType, List<String> keyterms);
}
