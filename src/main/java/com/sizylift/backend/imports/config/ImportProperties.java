package com.sizylift.backend.imports.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ИИ-импорт программ (ADR-0008, docs/imports.md). Ключ модели в production — {@code DEEPSEEK_API_KEY}.
 *
 * @param maxInputChars     предел нормализованного входа (текст или сетка таблицы)
 * @param maxFileBytes      предел файла и ответа Google Таблиц
 * @param maxSheets         листов {@code .xlsx}, которые читаются
 * @param maxCells          непустых ячеек во всех листах
 * @param maxRounds         раундов вопросов; дальше — черновик с предположениями
 * @param dailyLimit        импортов на пользователя за сутки
 * @param concurrentPerUser одновременных импортов одного пользователя
 * @param modelConcurrency  одновременных вызовов модели на весь сервер
 * @param modelWait         сколько ждать свободного места у модели
 * @param sessionTtl        срок жизни сессии
 */
@ConfigurationProperties("sizylift.imports")
public record ImportProperties(int maxInputChars, int maxFileBytes, int maxSheets, int maxCells, int maxRounds,
                               int dailyLimit, int concurrentPerUser, int modelConcurrency, Duration modelWait,
                               Duration sessionTtl, Model model, Sheets sheets) {

    /**
     * OpenAI-совместимый Chat Completions API. {@code name} — модель ({@code deepseek-flash}), {@code apiKey} —
     * ключ, {@code maxTokens} — предел ответа, {@code retryPause} — пауза перед единственным повтором.
     */
    public record Model(URI url, String name, String apiKey, int maxTokens, Duration connectTimeout,
                        Duration readTimeout, Duration retryPause) {
    }

    /** Экспорт Google Таблиц в CSV; {@code maxRedirects} — переходы только на разрешённые хосты Google. */
    public record Sheets(Duration connectTimeout, Duration readTimeout, int maxRedirects) {
    }
}
