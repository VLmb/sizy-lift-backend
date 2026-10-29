package com.sizylift.backend.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Подходы для расчётного ПМ (docs/coach-workspace.md, раздел 3); сам ПМ считает клиент. */
public final class MaxCandidateDtos {

    private MaxCandidateDtos() {
    }

    /** Выполненный подход упражнения класса {@code sbd} или {@code basic}; единицы как в payload v1. */
    public record MaxCandidateSet(UUID exerciseId, UUID workoutId, LocalDate workoutDate, int weightGrams,
                                  int repetitions, Integer rpeTenths) {
    }

    public record MaxCandidatesResponse(LocalDate from, List<MaxCandidateSet> sets) {
    }
}
