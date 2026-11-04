package com.sizylift.backend.imports.service;

import java.nio.charset.StandardCharsets;

import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.model.ProgramParserModel;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Верхняя оценка одного вызова модели: один токен на байт контекста, запас на инструкции и схемы,
 * максимальный вывод и пиковый тариф. Это резерв бюджета, а не счёт провайдера.
 */
@Component
public class ImportCost {
    private static final long PROMPT_OVERHEAD_BYTES = 32_768;
    private static final long MAX_REQUEST_BYTES = 900_000;
    private static final long MILLION = 1_000_000;
    private final ObjectMapper json;
    private final ImportProperties limits;

    public ImportCost(ObjectMapper json, ImportProperties limits) { this.json = json; this.limits = limits; }

    public long bytes(ProgramParserModel.Input input) {
        return json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8).length + PROMPT_OVERHEAD_BYTES;
    }

    public boolean tooLarge(ProgramParserModel.Input input) { return bytes(input) > MAX_REQUEST_BYTES; }

    public long micros(ProgramParserModel.Input input) {
        long micros = Math.addExact(Math.multiplyExact(bytes(input), limits.inputMicrosPerMillion()),
                Math.multiplyExact((long) limits.maxOutputTokens(), limits.outputMicrosPerMillion()));
        return Math.addExact(micros, MILLION - 1) / MILLION;
    }
}
