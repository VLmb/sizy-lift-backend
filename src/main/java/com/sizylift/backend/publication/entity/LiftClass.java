package com.sizylift.backend.publication.entity;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Класс упражнения: имеет ли смысл расчётный ПМ и планирование в % от него (docs/coach-workspace.md, раздел 1).
 * {@code SBD} и {@code BASIC} допустимы только у упражнений с результатом «вес × повторения».
 */
public enum LiftClass {

    /** Соревновательное движение: присед, жим лёжа, становая. */
    SBD("sbd"),
    /** Многосуставное упражнение со свободным весом. */
    BASIC("basic"),
    /** Без ПМ: изоляция, тренажёры, свой вес, кардио, мобильность. */
    OTHER("other");

    /** Единственный тип результата, у которого бывает ПМ. */
    public static final String ONE_REP_MAX_RESULT_TYPE = "weight_reps";

    private final String code;

    LiftClass(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /** Есть ли у класса ПМ и %ПМ. */
    public boolean hasOneRepMax() {
        return this != OTHER;
    }

    /** Допустим ли класс для упражнения с таким типом результата. */
    public boolean allows(String resultType) {
        return !hasOneRepMax() || ONE_REP_MAX_RESULT_TYPE.equals(resultType);
    }

    /** @throws IllegalArgumentException для кода не из перечисления: в БД его не пускает CHECK */
    public static LiftClass of(String code) {
        return Arrays.stream(values())
                .filter(value -> value.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown lift class"));
    }
}
