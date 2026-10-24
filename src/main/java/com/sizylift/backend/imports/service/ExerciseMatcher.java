package com.sizylift.backend.imports.service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.imports.entity.CatalogExercise;
import com.sizylift.backend.imports.repository.ImportCatalogRepository;

/**
 * Сопоставление названия из программы с упражнением (ADR-0008, раздел 2): точное совпадение названия →
 * краткое название (синоним каталога) → триграммы {@code pg_trgm} с порогом 0,6. Не нашли — похожие варианты
 * для предупреждения. Регистр, «ё» и лишние пробелы не важны.
 */
class ExerciseMatcher {

    static final double THRESHOLD = 0.6;
    static final int SUGGESTIONS = 3;

    private final ImportCatalogRepository catalog;
    private final List<UUID> owners;
    private final Map<String, CatalogExercise> exact = new HashMap<>();
    private final List<CatalogExercise> exercises;

    ExerciseMatcher(ImportCatalogRepository catalog, List<UUID> owners) {
        this.catalog = catalog;
        this.owners = owners;
        this.exercises = catalog.exercises(owners);
        exercises.forEach(exercise -> {
            exact.putIfAbsent(normalize(exercise.name()), exercise);
            if (exercise.shortName() != null) {
                exact.putIfAbsent(normalize(exercise.shortName()), exercise);
            }
        });
    }

    /** Названия для промпта модели. */
    List<String> names() {
        return exercises.stream().map(CatalogExercise::name).distinct().toList();
    }

    Match match(String name) {
        var found = exact.get(normalize(name));
        if (found != null) {
            return new Match(Optional.of(found), List.of());
        }
        var similar = catalog.similar(normalize(name), owners, SUGGESTIONS);
        if (!similar.isEmpty() && similar.getFirst().score() >= THRESHOLD) {
            return new Match(Optional.of(similar.getFirst().exercise()), List.of());
        }
        return new Match(Optional.empty(), similar.stream().map(ImportCatalogRepository.Scored::exercise).toList());
    }

    static String normalize(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }

    /** Найденное упражнение или похожие варианты. */
    record Match(Optional<CatalogExercise> exercise, List<CatalogExercise> suggestions) {
    }
}
