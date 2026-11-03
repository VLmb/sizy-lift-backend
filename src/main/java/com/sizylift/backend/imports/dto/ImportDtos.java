package com.sizylift.backend.imports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.entity.LiftClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import static com.sizylift.backend.imports.dto.ImportLimits.*;

public final class ImportDtos {
    private ImportDtos() { }

    public record Create(@NotNull UUID requestId, UUID ownerId, @NotBlank String source,
                         @Size(max = 200_000) String text, @Size(max = 500) String url,
                         @Size(max = NAME_CHARS) String name, @NotNull LocalDate startDate) { }
    public record Answer(@NotNull UUID requestId, @Min(1) long version, @Min(0) int round,
                         @NotBlank @Size(max = NOTE_CHARS) String answer) { }
    public record Patch(@Min(1) long version, @Size(max = NAME_CHARS) String name, LocalDate startDate,
                        @Size(max = RESOLUTIONS) List<@NotNull @Valid Decision> decisions) { }
    public record Decision(@NotBlank @Size(max = 100) String key, @NotBlank String kind, UUID exerciseId,
                           @Size(max = NAME_CHARS) String name, String resultType, LiftClass liftClass) { }
    public record Commit(@Min(1) long version) { }
    public record Created(UUID id, String status, long version) { }
    public record Receipt(UUID id, UUID ownerId, UUID programId, long programRevision) { }
    public record Failure(String code, String message) { }
    public record View(UUID id, UUID ownerId, String status, long version, int round, String name,
                       LocalDate startDate, Instant expiresAt, boolean needsClarification,
                       String clarificationMessage, ImportDraftV1 draft, com.sizylift.backend.imports.entity.ImportSession.Source source,
                       Map<String, Decision> decisions,
                       boolean canCommit, ProgramPayloadV1 program, List<String> warnings,
                       Failure error, long inputTokens, long outputTokens, long reservedCostMicros,
                       Receipt receipt) { }
}
