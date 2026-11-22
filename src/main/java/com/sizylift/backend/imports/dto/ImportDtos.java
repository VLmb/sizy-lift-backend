package com.sizylift.backend.imports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.imports.entity.ImportLanguage;
import com.sizylift.backend.publication.entity.LiftClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import static com.sizylift.backend.imports.dto.ImportLimits.*;

public final class ImportDtos {
    private ImportDtos() { }

    public record Create(@NotNull UUID requestId, UUID ownerId, @NotBlank String source,
                         @Size(max = 200_000) String text, @Size(max = 500) String url,
                         @Size(max = NAME_CHARS) String name, @NotNull LocalDate startDate,
                         @Pattern(regexp = "ru|en") String language) {
        public Create(UUID requestId, UUID ownerId, String source, String text, String url, String name, LocalDate startDate) {
            this(requestId, ownerId, source, text, url, name, startDate, null);
        }
        public String uiLanguage() { return ImportLanguage.of(language).code(); }
    }
    public record Answer(@NotNull UUID requestId, @Min(1) long version, @Min(0) int round,
                         @NotBlank @Size(max = NOTE_CHARS) String answer) { }
    public record Patch(@Min(1) long version, @Size(max = NAME_CHARS) String name, LocalDate startDate,
                        @Size(max = RESOLUTIONS) List<@NotNull @Valid Decision> decisions,
                        @Valid ExerciseEdit exerciseEdit) { }
    public record ExerciseEdit(@NotBlank @Size(max = 150) String workoutKey,
                               @NotBlank @Size(max = 150) String exerciseKey,
                               UUID exerciseId, @Valid SetEdit set) { }
    public record SetEdit(@NotBlank @Size(max = 150) String key, @Min(0) int index,
                          @NotEmpty @Size(max = 9) Map<@NotBlank String, @NotNull @Size(max = 32) String> values) { }
    public record Decision(@NotBlank @Size(max = 100) String key, @NotBlank String kind, UUID exerciseId,
                           @Size(max = NAME_CHARS) String name, String resultType, LiftClass liftClass) { }
    public record Commit(@Min(1) long version) { }
    /** Лист книги для выбора: index — номер листа в книге, cells — заполненные ячейки, preview — первые строки. */
    public record Sheet(int index, String name, int cells, List<String> preview) { }
    /** Выбранные листы книги; version — текущая версия сессии. */
    public record SelectSheets(@Min(1) long version, @NotEmpty @Size(max = 100) List<@NotNull Integer> sheets) { }
    /** Повторный разбор недель, которые не удалось разобрать; version — текущая версия сессии. */
    public record RetryWeeks(@Min(1) long version) { }
    public record Created(UUID id, String status, long version) { }
    public record Receipt(UUID id, UUID ownerId, UUID programId, long programRevision) { }
    public record Failure(String code, String message) { }
    /**
     * Прогресс текущего разбора: stage — plan (пишется каркас) или weeks (заполняются недели); exercises — найдено
     * упражнений; weeksDone из weeksTotal — готовые недели каркаса. weeksTotal неизвестен, пока нет каркаса.
     */
    public record Progress(String stage, int exercises, int weeksDone, Integer weeksTotal) { }
    /**
     * Незавершённый импорт в списке «вернуться к разбору»: name — название из разбора, если пользователь своё не задал;
     * openChoices — сколько упражнений ждёт выбора на проверке.
     */
    public record Summary(UUID id, UUID ownerId, String status, long version, String name, LocalDate startDate,
                          Instant expiresAt, Progress progress, int openChoices, Failure error) { }
    public record View(UUID id, UUID ownerId, String status, long version, int round, String name,
                       LocalDate startDate, Instant expiresAt, boolean needsClarification,
                       String clarificationMessage, ImportDraftV1 draft, com.sizylift.backend.imports.entity.ImportSession.Source source,
                       Map<String, Decision> decisions,
                       boolean canCommit, ProgramPayloadV1 program, List<String> warnings,
                       Failure error, long inputTokens, long outputTokens, long reservedCostMicros,
                       Receipt receipt, Progress progress, List<Sheet> sheets, boolean canEditExercises) { }
}
