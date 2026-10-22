package com.sizylift.backend.imports.entity;

import java.util.UUID;

/** Упражнение для сопоставления: системное или своё. */
public record CatalogExercise(UUID id, String name, String shortName, String resultType) {

    /** Вес задаётся только упражнениям с весом. */
    public boolean weighted() {
        return "weight_reps".equals(resultType) || "added_weight_reps".equals(resultType);
    }
}
