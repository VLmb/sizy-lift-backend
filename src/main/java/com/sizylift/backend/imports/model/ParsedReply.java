package com.sizylift.backend.imports.model;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Промежуточное описание программы, которое возвращает модель (ADR-0008, раздел 2): либо вопросы, либо
 * программа. Упражнения — названиями, нагрузка — как сказано в тексте; id и веса считает сервер.
 */
public record ParsedReply(
        @Size(max = MAX_QUESTIONS) List<@NotNull @Valid Question> questions,
        @Size(max = MAX_ASSUMPTIONS) List<@NotBlank @Size(max = TEXT) String> assumptions,
        @Valid Program program) {

    static final int MAX_QUESTIONS = 5;
    static final int MAX_OPTIONS = 4;
    static final int MIN_OPTIONS = 2;
    static final int MAX_ASSUMPTIONS = 20;
    static final int MAX_WEEKS = 104;
    static final int MAX_WORKOUTS = 14;
    static final int MAX_EXERCISES = 50;
    static final int MAX_SET_LINES = 30;
    static final int MAX_SET_COUNT = 20;
    static final int MAX_REPS = 1000;
    static final int DAYS_IN_WEEK = 7;
    static final int TEXT = 500;
    static final int SHORT = 200;
    static final int ID = 50;
    static final String SET_TYPE = "warmup|working|top|backoff|drop";

    public List<Question> questionsOrEmpty() {
        return questions == null ? List.of() : questions;
    }

    public List<String> assumptionsOrEmpty() {
        return assumptions == null ? List.of() : assumptions;
    }

    public record Question(@NotBlank @Size(max = ID) String id, @NotBlank @Size(max = TEXT) String text,
                           @NotNull @Size(min = MIN_OPTIONS, max = MAX_OPTIONS) List<@NotNull @Valid Option> options) {
    }

    public record Option(@NotBlank @Size(max = ID) String id, @NotBlank @Size(max = SHORT) String text) {
    }

    public record Program(@NotBlank @Size(max = SHORT) String name, @Size(max = TEXT) String description,
                          @NotNull @Size(min = 1, max = MAX_WEEKS) List<@NotNull @Valid Week> weeks) {
    }

    /** {@code number} — номер недели с 1; одинаковые недели модель повторяет явно. */
    public record Week(@Min(1) @Max(MAX_WEEKS) int number, @Size(max = SHORT) String name,
                       @NotNull @Size(min = 1, max = MAX_WORKOUTS) List<@NotNull @Valid Workout> workouts) {
    }

    /** {@code day} — день недели с 1 (понедельник недели — день старта + 7·(номер недели − 1)). */
    public record Workout(@Min(1) @Max(DAYS_IN_WEEK) int day, @Size(max = SHORT) String title,
                          @NotNull @Size(min = 1, max = MAX_EXERCISES) List<@NotNull @Valid Exercise> exercises) {
    }

    public record Exercise(@NotBlank @Size(max = SHORT) String name, @Size(max = TEXT) String comment,
                           @NotNull @Size(min = 1, max = MAX_SET_LINES) List<@NotNull @Valid SetLine> sets) {
    }

    /** {@code count} одинаковых подходов; {@code reps} может отсутствовать (например, «на время»). */
    public record SetLine(@Min(1) @Max(MAX_SET_COUNT) int count, @Min(1) @Max(MAX_REPS) Integer reps,
                          @Pattern(regexp = SET_TYPE) String type, @Valid Load load) {
    }

    /** Нагрузка: ровно одно поле или ничего; {@code relativeToTopSet} — проценты, например −10. */
    public record Load(
            @DecimalMin("1") @DecimalMax("150") Double percentOf1rm,
            @DecimalMin("1") @DecimalMax("10") Double rpe,
            @DecimalMin("0") @DecimalMax("1000") Double weightKg,
            @DecimalMin("-90") @DecimalMax("50") Double relativeToTopSet) {
    }
}
