package com.sizylift.backend.imports.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.entity.ImportSession.Source;

public interface ProgramParserModel {
    Result parse(Input input);

    record CatalogEntry(UUID id, String name) { }
    record Input(Source source, List<CatalogEntry> catalog, LocalDate startDate,
                 ImportDraftV1 draft, List<Clarification> history, String repairMessage) { }
    record Result(String json, int inputTokens, int outputTokens, int cachedTokens) { }
}
