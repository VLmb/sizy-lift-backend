package com.sizylift.backend.voice.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Корпус фраз из {@code voice/corpus.txt}: ожидаемый результат для каждой. Без Spring и базы. */
class WorkoutTextParserCorpusTest {
    private static final String CORPUS = "/voice/corpus.txt";
    private static final String PHRASE = "> ";
    private static final String COMMENT = "#";

    record Case(String phrase, List<String> expected) { }

    @Test
    void corpus() throws IOException {
        var parser = new WorkoutTextParser(ExerciseDictionary.system());
        var cases = cases();
        var failures = new ArrayList<String>();
        for (var c : cases) {
            var actual = ParsedWorkoutText.render(parser.parse(c.phrase()));
            if (!actual.equals(c.expected())) {
                failures.add(PHRASE + c.phrase() + "\n  ожидали:\n    " + String.join("\n    ", c.expected())
                        + "\n  получили:\n    " + String.join("\n    ", actual));
            }
        }
        assertThat(cases).hasSizeGreaterThan(0);
        assertThat(failures).as("Совпало %d из %d фраз", cases.size() - failures.size(), cases.size()).isEmpty();
    }

    @Test
    void sameInputGivesSameResult() throws IOException {
        var parser = new WorkoutTextParser(ExerciseDictionary.system());
        for (var c : cases()) assertThat(parser.parse(c.phrase())).isEqualTo(parser.parse(c.phrase()));
    }

    static List<Case> cases() throws IOException {
        String text;
        try (var in = WorkoutTextParserCorpusTest.class.getResourceAsStream(CORPUS)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var cases = new ArrayList<Case>();
        String phrase = null;
        var expected = new ArrayList<String>();
        for (var line : (text + "\n").lines().toList()) {
            if (line.startsWith(COMMENT)) continue;
            if (line.startsWith(PHRASE)) { phrase = line.substring(PHRASE.length()).strip(); continue; }
            if (line.isBlank()) {
                if (phrase != null) cases.add(new Case(phrase, List.copyOf(expected)));
                phrase = null;
                expected.clear();
                continue;
            }
            expected.add(line.strip());
        }
        if (phrase != null) cases.add(new Case(phrase, List.copyOf(expected)));
        return cases;
    }
}
