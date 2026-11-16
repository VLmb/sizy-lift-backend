package com.sizylift.backend.imports.service;

import java.nio.charset.StandardCharsets;

import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.model.ProgramParserModel;
import org.springframework.stereotype.Component;

/**
 * Оценки одного вызова модели до его отправки. Размер входа в токенах — по числу символов с запасом:
 * токенайзер DeepSeek на русском тексте и таблицах даёт не меньше двух символов на токен, поэтому оценка сверху.
 * Резерв бюджета — один токен на байт, максимальный вывод и пиковый тариф: это верхняя граница, а не счёт провайдера.
 */
@Component
public class ImportCost {
    /** Символов на токен в худшем случае; уточняется по prompt_tokens реальных ответов. */
    private static final double CHARS_PER_TOKEN = 2.0;
    private static final long MILLION = 1_000_000;
    private final ImportProperties limits;

    public ImportCost(ImportProperties limits) { this.limits = limits; }

    public long bytes(ProgramParserModel.Request request) {
        return (long) request.system().getBytes(StandardCharsets.UTF_8).length + request.user().getBytes(StandardCharsets.UTF_8).length;
    }

    public long tokens(ProgramParserModel.Request request) {
        return (long) Math.ceil((request.system().length() + request.user().length()) / CHARS_PER_TOKEN);
    }

    /** Вход вместе с максимальным выводом не помещается в контекстное окно модели. */
    public boolean tooLarge(ProgramParserModel.Request request) {
        return tokens(request) + limits.maxOutputTokens() > limits.contextTokens();
    }

    public long micros(ProgramParserModel.Request request) {
        long micros = Math.addExact(Math.multiplyExact(bytes(request), limits.inputMicrosPerMillion()),
                Math.multiplyExact((long) limits.maxOutputTokens(), limits.outputMicrosPerMillion()));
        return Math.addExact(micros, MILLION - 1) / MILLION;
    }
}
