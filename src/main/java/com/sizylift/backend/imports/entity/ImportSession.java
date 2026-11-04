package com.sizylift.backend.imports.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportDtos.Decision;
import com.sizylift.backend.imports.dto.ImportDtos.Progress;
import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.web.dto.WebDtos.CatalogExercise;

/**
 * Снимок строки сессии; изменение состояния выполняет репозиторий с проверкой версии или аренды.
 * repairHint — конкретные ошибки отклонённого ответа модели для следующей технической попытки этого раунда.
 * outline — план большой программы раунда; после перезапуска worker недели разбираются без повторного плана.
 * progress — прогресс текущего вызова модели, только пока сессия обрабатывается.
 */
public record ImportSession(UUID id, UUID userId, UUID ownerId, UUID requestId, String requestHash,
        String status, long version, String sourceKind, String sourceText, byte[] sourceBytes, String sourceName,
        Source normalized, List<CatalogExercise> catalog, ImportDraftV1 output, Map<String, Decision> decisions,
        List<Clarification> history, String name, LocalDate startDate, UUID programId, Long programRevision,
        int round, int attempts, long reservedCostMicros, long inputTokens, long outputTokens, long cachedTokens,
        String errorCode, String errorMessage, UUID leaseToken, Instant expiresAt, String repairHint,
        ImportPlan outline, Progress progress) {
    public record Source(List<SourceItem> items) { }
    public record SourceItem(String ref, String text) { }
    public record Clarification(UUID requestId, int round, String question, String answer) { }

    public ImportSession withResult(ImportDraftV1 draft, Map<String, Decision> selected) {
        return new ImportSession(id, userId, ownerId, requestId, requestHash, status, version, sourceKind, sourceText,
                sourceBytes, sourceName, normalized, catalog, draft, selected, history, name, startDate, programId,
                programRevision, round, attempts, reservedCostMicros, inputTokens, outputTokens, cachedTokens,
                errorCode, errorMessage, leaseToken, expiresAt, repairHint, outline, progress);
    }
}
