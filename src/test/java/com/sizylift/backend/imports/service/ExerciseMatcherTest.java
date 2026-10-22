package com.sizylift.backend.imports.service;

import java.util.List;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.imports.repository.ImportCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Сопоставление названий с каталогом на настоящем PostgreSQL с {@code pg_trgm}. */
@SpringBootTest
class ExerciseMatcherTest extends PostgresIntegrationTest {

    static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");

    @Autowired
    ImportCatalogRepository catalog;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void exactNameMatchesRegardlessOfCaseSpacesAndYo() {
        var matcher = new ExerciseMatcher(catalog, List.of(newUser()));

        assertThat(matcher.match("  присед   СО штангой ").exercise()).get()
                .extracting(exercise -> exercise.id()).isEqualTo(SQUAT);
    }

    @Test
    void similarNameMatchesAboveTheThresholdAndOthersGetSuggestions() {
        var matcher = new ExerciseMatcher(catalog, List.of(newUser()));

        assertThat(matcher.match("Приседания со штангой").exercise()).get()
                .extracting(exercise -> exercise.id()).isEqualTo(SQUAT);
        var unknown = matcher.match("Сгибание запястий с резиной на одной ноге");
        assertThat(unknown.exercise()).isEmpty();
        assertThat(unknown.suggestions()).hasSizeLessThanOrEqualTo(ExerciseMatcher.SUGGESTIONS);
    }

    @Test
    void ownExercisesOfTheOwnerAreKnownButOthersAreNot() {
        var owner = newUser();
        var stranger = newUser();
        var own = UUID.randomUUID();
        var foreign = UUID.randomUUID();
        exercise(own, owner, "Мой хитрый жим");
        exercise(foreign, stranger, "Чужой секретный тяг");

        var matcher = new ExerciseMatcher(catalog, List.of(owner));

        assertThat(matcher.match("мой хитрый жим").exercise()).get().extracting(e -> e.id()).isEqualTo(own);
        assertThat(matcher.names()).contains("Мой хитрый жим").doesNotContain("Чужой секретный тяг");
        assertThat(matcher.match("Чужой секретный тяг").exercise()).isEmpty();
    }

    private void exercise(UUID id, UUID owner, String name) {
        jdbc.update("INSERT INTO sizylift.exercise (id, user_id, name, result_type) VALUES (?, ?, ?, 'weight_reps')",
                id, owner, name);
    }

    private UUID newUser() {
        return jdbc.queryForObject("INSERT INTO sizylift.user_account (issuer, subject) VALUES ('test', ?) "
                + "RETURNING id", UUID.class, UUID.randomUUID().toString());
    }
}
