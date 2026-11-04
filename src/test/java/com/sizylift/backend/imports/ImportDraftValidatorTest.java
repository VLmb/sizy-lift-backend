package com.sizylift.backend.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.imports.entity.ImportSession.SourceItem;
import com.sizylift.backend.imports.model.ProgramParserModel.CatalogEntry;
import com.sizylift.backend.imports.service.ImportDraftValidator;
import com.sizylift.backend.imports.service.ImportDraftValidator.InvalidDraft;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static com.sizylift.backend.imports.ImportFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ImportDraftValidatorTest {
    private static final UUID BENCH = UUID.fromString("14000000-0000-4000-8000-000000000002");
    private final JsonMapper json = JsonMapper.builder().build();
    private final ImportDraftValidator validator = new ImportDraftValidator(json);
    private final Source source = new Source(List.of(
            new SourceItem("text:1", "Блок 1"),
            new SourceItem("text:2", "Пн: Присед 3×5 100 кг @8, жим 5×5 АМРАП"),
            new SourceItem("text:3", "Чт: Присед 4×8-12 отдых 90"),
            new SourceItem("text:4", "Блок 2: Присед 1×AMRAP 75%")));
    private final List<CatalogEntry> catalog = List.of(new CatalogEntry("c1", SQUAT, "Присед"), new CatalogEntry("c2", BENCH, "Жим лёжа"));

    @Test
    void serverAssignsKeysOffsetsCyclesAndCatalogIdsFromTheCompactAnswer() {
        var squat = exercise("e1", "Присед", "matched", "c1", List.of(), "", "", List.of("text:2"));
        var bench = exercise("e2", "жим", "ambiguous", "", List.of("c2", "c404"), "", "", List.of("text:2"));
        var unused = exercise("e3", "Присед", "new", "", List.of(), "Присед", "weight_reps", List.of("text:3"));
        var answer = program(List.of(squat, bench, unused), List.of(
                week(1, "Блок 1", day(0, block("single", "superset", item("e1", List.of("text:2"), "n=3 r=5 kg=100 rpe=8 type=working | пауза внизу")),
                        block("single", "", item("e2", List.of("text:2"), "n=5 r=amrap"))),
                        day(3, block("single", "", item("e1", List.of("text:3"), "n=4 r=8-12 rest=90")))),
                week(2, "Блок 1", day(0, block("single", "", item("e1", List.of("text:2"), "n=3 r=5 kg=102,5")))),
                week(3, "Блок 2", day(1, block("group", "superset", item("e1", List.of("text:4"), "n=1 r=AMRAP pct=75 type=top"))))));

        var draft = validator.parse(json.writeValueAsString(answer), source, catalog, List.of());

        assertThat(draft.needsClarification()).isFalse();
        assertThat(draft.exerciseResolutions()).extracting(r -> r.key()).containsExactly("e1", "e2");
        assertThat(draft.exerciseResolutions().get(0).exerciseId()).isEqualTo(SQUAT.toString());
        assertThat(draft.exerciseResolutions().get(1).candidates()).containsExactly(BENCH.toString());
        var cycles = draft.programDraft().cycles();
        assertThat(cycles).extracting(c -> c.name()).containsExactly("Блок 1", "Блок 2");
        assertThat(cycles.get(0).weeks()).extracting(w -> w.number()).containsExactly(1, 2);
        var first = cycles.get(0).weeks().get(0);
        assertThat(first.workouts()).extracting(d -> d.dayOffset()).containsExactly(0, 3);
        assertThat(cycles.get(1).weeks().get(0).workouts().getFirst().dayOffset()).isEqualTo(15);
        var block = first.workouts().get(0).blocks().get(0);
        assertThat(block.groupLabel()).isEmpty();
        var set = block.exercises().getFirst().sets().getFirst();
        assertThat(List.of(set.count(), set.repetitions(), set.weightKg(), set.rpe(), set.setType(), set.note()))
                .containsExactly("3", "5", "100", "8", "working", "пауза внизу");
        assertThat(set.percentOneRm()).isEmpty();
        assertThat(set.sourceRefs()).containsExactly("text:2");
        assertThat(first.workouts().get(0).blocks().get(1).exercises().getFirst().sets().getFirst().repetitions()).isEqualTo("AMRAP");
        var top = cycles.get(1).weeks().get(0).workouts().getFirst().blocks().getFirst();
        assertThat(top.groupLabel()).isEqualTo("superset");
        assertThat(top.exercises().getFirst().sets().getFirst().percentOneRm()).isEqualTo("75");
        var keys = new ArrayList<String>();
        for (var cycle : cycles) {
            keys.add(cycle.key());
            for (var week : cycle.weeks()) {
                keys.add(week.key());
                for (var day : week.workouts()) {
                    keys.add(day.key());
                    for (var b : day.blocks()) {
                        keys.add(b.key());
                        for (var ex : b.exercises()) { keys.add(ex.key()); ex.sets().forEach(s -> keys.add(s.key())); }
                    }
                }
            }
        }
        assertThat(keys).doesNotHaveDuplicates().allMatch(key -> key.matches("[A-Za-z0-9_:-]{1,100}"));
    }

    @Test
    void aQuestionDiscardsAnyPartialProgram() {
        var answer = draft(matched("text:2"));
        answer.put("question", "  С какого дня начинать?  ");
        firstItem(answer).put("sets", List.of("bad"));
        var draft = validator.parse(json.writeValueAsString(answer), source, catalog, List.of());
        assertThat(draft.needsClarification()).isTrue();
        assertThat(draft.clarificationMessage()).isEqualTo("С какого дня начинать?");
        assertThat(draft.programDraft().cycles()).isEmpty();
        assertThat(draft.exerciseResolutions()).isEmpty();
    }

    @Test
    void answersCanBeQuotedAsReferences() {
        var answer = draft(exercise("e1", "Присед", "matched", "c1", List.of(), "", "", List.of("text:2")));
        firstItem(answer).put("refs", List.of("text:2", "answer:1"));
        var history = List.of(new Clarification(UUID.randomUUID(), 0, "Вес?", "100 кг"));
        assertThat(validator.parse(json.writeValueAsString(answer), source, catalog, history).needsClarification()).isFalse();
        assertThatThrownBy(() -> validator.parse(json.writeValueAsString(answer), source, catalog, List.of()))
                .isInstanceOfSatisfying(InvalidDraft.class, e -> assertThat(e.errors()).anyMatch(m -> m.contains("ref «answer:1»")));
    }

    @Test
    void reportsEveryConcreteProblemInTheModelsOwnTerms() {
        var answer = program(List.of(exercise("e1", "Становая", "matched", "c9", List.of(), "", "", List.of("text:2", "text:99"))),
                List.of(week(1, "", day(0, block("single", "", item("e7", List.of(), "n=0 r=5-3 kg=сто type=heavy foo",
                        "n=3 n=4", " ")))), week(1, "", day(0))));
        assertThatThrownBy(() -> validator.parse(json.writeValueAsString(answer), source, catalog, List.of()))
                .isInstanceOfSatisfying(InvalidDraft.class, e -> {
                    assertThat(e.errors()).hasSize(10);
                    assertThat(String.join("\n", e.errors())).contains(
                            "exercises[e1]: ref «text:99» нет в ИСТОЧНИКЕ",
                            "exercises[e1]: status=matched, но match «c9» не alias из КАТАЛОГА",
                            "weeks[week=1].days[0].blocks[0].items[0]: ex «e7» нет в exercises",
                            "items[0]: refs пуст",
                            "sets[0] «n=0 r=5-3 kg=сто type=heavy foo»: фрагмент «foo»",
                            "n — целое число подходов",
                            "r — число, диапазон",
                            "kg — неотрицательное число",
                            "type — warmup",
                            "sets[1] «n=3 n=4»: поле n указано дважды");
                    assertThat(e.repairHint()).startsWith("Предыдущий ответ отклонён сервером.").contains("\n- exercises[e1]");
                });
    }

    @Test
    void anExerciseNameMustBeQuotedFromItsReferences() {
        var answer = draft(exercise("e1", "Тяга", "new", "", List.of(), "Тяга", "", List.of("text:2")));
        assertThatThrownBy(() -> validator.parse(json.writeValueAsString(answer), source, catalog, List.of()))
                .isInstanceOfSatisfying(InvalidDraft.class, e -> assertThat(e.errors().getFirst()).contains("name «Тяга» не найдено"));
    }

    @Test
    void structuralErrorsComeFromTheSchema() {
        assertThatThrownBy(() -> validator.parse("{\"question\":\"\"}", source, catalog, List.of()))
                .isInstanceOfSatisfying(InvalidDraft.class, e -> assertThat(e.errors()).isNotEmpty().allMatch(m -> m.startsWith("Схема: ")));
        assertThatThrownBy(() -> validator.parse("not json", source, catalog, List.of())).isInstanceOf(InvalidDraft.class);
    }

    @Test
    void aProgramWithoutExercisesIsNotAccepted() {
        var answer = program(List.of(), List.of(week(1, "", day(0))));
        assertThatThrownBy(() -> validator.parse(json.writeValueAsString(answer), source, catalog, List.of()))
                .isInstanceOfSatisfying(InvalidDraft.class, e -> assertThat(e.errors()).anyMatch(m -> m.contains("нет ни одного упражнения")));
    }

    private static Map<String, Object> program(List<Map<String, Object>> exercises, List<Map<String, Object>> weeks) {
        var result = new LinkedHashMap<String, Object>();
        result.put("question", ""); result.put("name", "Программа"); result.put("description", "");
        result.put("exercises", exercises); result.put("weeks", weeks);
        return result;
    }
    @SafeVarargs
    private static Map<String, Object> week(int number, String cycle, Map<String, Object>... days) {
        return Map.of("week", number, "cycle", cycle, "name", "", "days", List.of(days));
    }
    @SafeVarargs
    private static Map<String, Object> day(int day, Map<String, Object>... blocks) {
        return Map.of("day", day, "title", "", "blocks", List.of(blocks));
    }
    @SafeVarargs
    private static Map<String, Object> block(String type, String label, Map<String, Object>... items) {
        return Map.of("type", type, "label", label, "title", "", "note", "", "items", List.of(items));
    }
    private static Map<String, Object> item(String ex, List<String> refs, String... sets) {
        return Map.of("ex", ex, "note", "", "refs", refs, "sets", List.of(sets));
    }
}
