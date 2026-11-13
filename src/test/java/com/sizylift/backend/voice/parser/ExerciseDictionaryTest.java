package com.sizylift.backend.voice.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExerciseDictionaryTest {
    private static final List<String> SEEDS = List.of("/db/changelog/changes/004-seed-exercise-catalog.sql",
            "/db/changelog/changes/006-seed-exercise-catalog-2026-10-2.sql");
    /** Строка упражнения в seed: id, название, короткое, категория, тип. */
    private static final Pattern SEED_ROW = Pattern.compile(
            "\\('(14000000-[0-9a-f-]+)', '([^']*)', '([^']*)', '[^']*', '([a-z_]+)'");

    @Test
    void coversSystemCatalogWithSameNamesAndTypes() throws IOException {
        var byId = new HashMap<String, ExerciseDictionary.Exercise>();
        for (var e : ExerciseDictionary.loadSystem()) byId.put(e.id(), e);
        int seeded = 0;
        for (var seed : SEEDS) {
            var m = SEED_ROW.matcher(read(seed));
            while (m.find()) {
                seeded++;
                var e = byId.get(m.group(1));
                assertThat(e).as(m.group(2)).isNotNull();
                assertThat(e.name()).isEqualTo(m.group(2));
                assertThat(e.aliases().get(1)).isEqualTo(m.group(3));
                assertThat(e.resultType()).isEqualTo(m.group(4));
            }
        }
        assertThat(seeded).isEqualTo(74);
        assertThat(byId).hasSize(seeded);
    }

    /** Один вариант (как цепочка основ) не может вести к двум системным упражнениям. */
    @Test
    void aliasBelongsToOneExercise() {
        var owners = new HashMap<List<String>, String>();
        for (var e : ExerciseDictionary.loadSystem()) {
            for (var alias : e.aliases()) {
                if (!ExerciseDictionary.searchable(alias)) continue;
                var previous = owners.putIfAbsent(ExerciseDictionary.stems(alias), e.name());
                assertThat(previous == null || previous.equals(e.name()))
                        .as("«%s» у «%s» и «%s»", alias, previous, e.name()).isTrue();
            }
        }
    }

    @Test
    void synonymsAreNotServiceWords() {
        for (var e : ExerciseDictionary.loadSystem()) {
            // Название и короткое название — из каталога; синонимы пишем сами и проверяем строже.
            for (var alias : e.aliases().subList(2, e.aliases().size())) {
                assertThat(ExerciseDictionary.searchable(alias)).as(alias).isTrue();
            }
        }
    }

    @Test
    void stemsIgnoreCaseAndPrepositions() {
        assertThat(ExerciseDictionary.stems("Присед со штангой")).containsExactly("присед", "штанг");
        assertThat(ExerciseDictionary.stems("в приседе")).containsExactly("присед");
        assertThat(RussianStemmer.stem("становой")).isEqualTo(RussianStemmer.stem("становая"));
        assertThat(RussianStemmer.stem("гантелями")).isEqualTo(RussianStemmer.stem("гантелей"));
    }

    private static String read(String resource) throws IOException {
        try (var in = ExerciseDictionaryTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
