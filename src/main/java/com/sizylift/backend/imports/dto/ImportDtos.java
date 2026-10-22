package com.sizylift.backend.imports.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.imports.entity.ImportStatus;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Контракт /api/v2/imports (docs/imports.md). */
public final class ImportDtos {

    public static final int MAX_ANSWERS = 20;
    public static final int MAX_ANSWER_TEXT = 1000;
    public static final int MAX_ID = 50;

    private ImportDtos() {
    }

    /**
     * Импорт текста или Google Таблицы. Ровно одно из {@code text} и {@code url}. {@code startDate} — дата
     * первой недели (по умолчанию сегодня), {@code targetOwnerId} — подопечный, если импортирует тренер.
     */
    public record StartRequest(String text, String url, LocalDate startDate, UUID targetOwnerId) {
    }

    public record AnswersRequest(@NotNull @Size(min = 1, max = MAX_ANSWERS) List<@NotNull @Valid Answer> answers) {
    }

    /** Ответ на вопрос: выбранный вариант или свой текст. */
    public record Answer(@NotBlank @Size(max = MAX_ID) String questionId, @Size(max = MAX_ID) String optionId,
                         @Size(max = MAX_ANSWER_TEXT) String text) {
    }

    /** {@code questions} — при статусе {@code questions}, {@code draft} — при {@code draft}. */
    public record ImportResponse(UUID id, ImportStatus status, int round, List<Question> questions, Draft draft,
                                 List<Warning> warnings, Instant expiresAt) {
    }

    public record Question(String id, String text, List<Option> options) {
    }

    public record Option(String id, String text) {
    }

    /**
     * Черновик программы в формате payload {@code program} v1 с id, сгенерированными сервером. Клиент показывает
     * предпросмотр и сохраняет его обычной записью с {@code programId}; сервер программу не сохраняет.
     * {@code newExercises} — упражнения, которых нет в библиотеке: программа уже ссылается на их id, клиент
     * сохраняет их как свои упражнения (payload {@code exercise} v1) до программы или заменяет на похожие.
     */
    public record Draft(UUID programId, ProgramPayloadV1 program, List<NewExercise> newExercises) {
    }

    /** Новое своё упражнение, готовое к записи с этим {@code id}. */
    public record NewExercise(UUID id, ExercisePayloadV1 exercise) {
    }

    /**
     * Предупреждение сборки. У {@code new_exercise}: {@code exercise} — название, {@code exerciseId} — id нового
     * упражнения в черновике, {@code suggestions} — похожие из библиотеки для замены.
     */
    public record Warning(String code, String message, String exercise, UUID exerciseId,
                          List<Suggestion> suggestions) {

        public static Warning of(String code, String message) {
            return new Warning(code, message, null, null, List.of());
        }

        public static Warning about(String code, String message, String exercise) {
            return new Warning(code, message, exercise, null, List.of());
        }
    }

    public record Suggestion(UUID exerciseId, String name) {
    }

    public record ErrorResponse(String error, String message) {
    }
}
