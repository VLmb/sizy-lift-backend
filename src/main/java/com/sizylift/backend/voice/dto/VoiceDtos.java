package com.sizylift.backend.voice.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Контракт голосового ввода с приложением: docs/voice.md. */
public final class VoiceDtos {
    private VoiceDtos() { }

    /**
     * Метаданные записи (часть multipart «metadata»). {@code requestId} — идемпотентность повтора той же записи;
     * {@code ownerId} — владелец тренировки (пусто — сам вызывающий); {@code pendingExercises} — свои упражнения
     * владельца, ещё не дошедшие до сервера синхронизацией.
     */
    public record ParseRequest(@NotNull UUID requestId, UUID ownerId,
                               @Size(max = 200) List<@Valid PendingExercise> pendingExercises) { }

    public record PendingExercise(@NotNull UUID id, @NotBlank @Size(max = 100) String name,
                                  @NotBlank @Size(max = 30) String resultType) { }

    /**
     * Упражнения по порядку речи. {@code speechDetected=false} — в записи не нашлось речи. {@code unrecognized} —
     * куски, которые не отнесены ни к одному упражнению.
     */
    public record ParseResponse(UUID requestId, UUID ownerId, boolean speechDetected, List<Item> items,
                                List<String> unrecognized) { }

    /**
     * {@code exerciseId == null} — упражнение не найдено: {@code spoken} — как сказано, {@code candidates} —
     * варианты выбора, {@code proposal} — предложение создать своё. {@code confidence}: high, medium (показать
     * пометку «проверьте»), low (не найдено). {@code restSeconds} — отдых для всех подходов упражнения.
     * {@code notes} — заметки к упражнению; {@code issues} — названные значения, которые не подходят его типу.
     */
    public record Item(UUID exerciseId, String exerciseName, String resultType, String spoken, String confidence,
                       Integer restSeconds, List<Line> lines, List<String> notes, List<String> unrecognized,
                       List<String> issues, List<Candidate> candidates, Proposal proposal) { }

    /**
     * Строка одинаковых подходов: {@code count} подходов (null — число не названо, вставить один и выделить).
     * {@code reps}–{@code repsMax} — повторы или диапазон; {@code toFailure} — до отказа. {@code type} —
     * warmup или working, если сказано. null в любом поле — «не названо», ничего не подставлять.
     */
    public record Line(Integer count, Integer reps, Integer repsMax, boolean toFailure, Double weightKg,
                       Double percent, Integer durationSeconds, Integer distanceMeters, Double rpe, Double rir,
                       String type, String note) { }

    public record Candidate(UUID exerciseId, String name) { }

    /** {@code source}: model — название и тип предложила модель; spoken — слова речи, тип не известен. */
    public record Proposal(String name, String resultType, String source) { }

    /** Пользователь заменил надиктованное упражнение: {@code spoken} запоминается как его синоним. */
    public record AliasRequest(UUID ownerId, @NotNull UUID exerciseId, @NotBlank @Size(max = 100) String spoken) { }

    public record Failure(String code, String message) { }
}
