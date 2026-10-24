package com.sizylift.backend.imports.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.imports.entity.CatalogExercise;
import com.sizylift.backend.imports.model.ParsedReply;
import com.sizylift.backend.imports.model.ParsedReply.Exercise;
import com.sizylift.backend.imports.model.ParsedReply.Load;
import com.sizylift.backend.imports.model.ParsedReply.Program;
import com.sizylift.backend.imports.model.ParsedReply.SetLine;
import com.sizylift.backend.imports.model.ParsedReply.Week;
import com.sizylift.backend.imports.model.ParsedReply.Workout;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Детерминированная сборка черновика (ADR-0008, раздел 6): веса, округление, топ-сет, RPE, даты, упражнения. */
class DraftAssemblerTest {

    static final LocalDate START = LocalDate.parse("2026-11-02");
    static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    static final CatalogExercise SQUAT = new CatalogExercise(UUID.randomUUID(), "Присед со штангой", "Присед",
            "weight_reps");
    static final CatalogExercise BENCH = new CatalogExercise(UUID.randomUUID(), "Жим лёжа", null, "weight_reps");
    static final CatalogExercise PLANK = new CatalogExercise(UUID.randomUUID(), "Планка", null, "duration");
    static final CatalogExercise SIMILAR = new CatalogExercise(UUID.randomUUID(), "Тяга штанги в наклоне", null,
            "weight_reps");
    static final Map<String, CatalogExercise> CATALOG = Map.of("присед", SQUAT, "жим", BENCH, "планка", PLANK);

    @Test
    void percentOfOneRepMaxBecomesWeightRoundedToTwoAndAHalfKilos() {
        var sets = squatSets(Map.of(SQUAT.id(), 182_000), line(3, 5, "working", percent(75)));

        // 75% от 182 кг = 136,5 кг → 137,5 кг
        assertThat(sets).hasSize(3).allMatch(set -> set.targetWeightGrams() == 137_500 && set.repetitions() == 5);
        assertThat(sets).extracting(ProgramPayloadV1.Set::position).containsExactly(0, 1, 2);
    }

    @Test
    void backoffIsComputedFromTheComputedTopSet() {
        var sets = squatSets(Map.of(SQUAT.id(), 200_000),
                line(1, 3, "top", percent(90)),
                line(2, 5, "backoff", new Load(null, null, null, -10.0)));

        assertThat(sets.getFirst().targetWeightGrams()).isEqualTo(180_000);
        // −10% от 180 кг = 162 кг → 162,5 кг
        assertThat(sets.subList(1, 3)).allMatch(set -> set.targetWeightGrams() == 162_500
                && set.setType().equals("backoff"));
    }

    @Test
    void withoutOneRepMaxThePercentStaysInTheNoteAndAWarningIsGiven() {
        var result = assemble(Map.of(), program(exercise("Присед", line(2, 5, null, percent(72.5)))));

        var sets = sets(result);
        assertThat(sets).allMatch(set -> set.targetWeightGrams() == null && "72.5% от 1ПМ".equals(set.note())
                && set.setType().equals("working"));
        assertThat(result.warnings()).extracting(warning -> warning.code()).contains("no_one_rep_max");
    }

    @Test
    void relativeLoadWithoutATopSetStaysInTheNote() {
        var result = assemble(Map.of(), program(exercise("Присед",
                line(3, 5, "backoff", new Load(null, null, null, -10.0)))));

        assertThat(sets(result)).allMatch(set -> "-10% от топ-сета".equals(set.note()));
        assertThat(result.warnings()).extracting(warning -> warning.code()).contains("no_top_set");
    }

    @Test
    void rpeIsStoredInTenthsWithHalfPointSteps() {
        var result = assemble(Map.of(), program(exercise("Жим",
                line(1, 3, "top", new Load(null, 8.3, null, null)),
                line(1, 3, null, new Load(null, 9.75, null, null)))));

        assertThat(sets(result)).extracting(ProgramPayloadV1.Set::targetRpeTenths).containsExactly(85, 100);
    }

    @Test
    void absoluteWeightIsRoundedAndNotAppliedToExercisesWithoutWeight() {
        var result = assemble(Map.of(), program(
                exercise("Жим", line(1, 8, null, new Load(null, null, 101.0, null))),
                exercise("Планка", line(3, null, null, new Load(null, null, 10.0, null)))));

        var exercises = exercises(result);
        assertThat(exercises.get(0).sets().getFirst().targetWeightGrams()).isEqualTo(100_000);
        assertThat(exercises.get(1).sets()).allMatch(set -> set.targetWeightGrams() == null
                && "10 кг".equals(set.note()));
    }

    @Test
    void weeksAndDaysAreDatedFromTheStart() {
        var wednesday = new Workout(3, "Ср", List.of(exercise("Жим", line(1, 5, null, null))));
        var friday = new Workout(5, "Пт", List.of(exercise("Жим", line(1, 5, null, null))));
        var monday = new Workout(1, "Пн", List.of(exercise("Присед", line(1, 5, null, null))));
        var weeks = List.of(new Week(2, null, List.of(wednesday)), new Week(1, null, List.of(friday, monday)));
        var parsed = new ParsedReply(List.of(), List.of(), new Program("Программа", null, weeks));

        var program = assemble(Map.of(), parsed).draft().program();

        var dated = program.cycles().getFirst().weeks();
        assertThat(dated).extracting(ProgramPayloadV1.Week::number).containsExactly(1, 2);
        assertThat(dated.get(0).startDate()).isEqualTo(START);
        assertThat(dated.get(0).endDate()).isEqualTo(START.plusDays(6));
        assertThat(dated.get(0).workouts()).extracting(ProgramPayloadV1.PlannedWorkout::workoutDate)
                .containsExactly(START, START.plusDays(4));
        assertThat(dated.get(1).workouts().getFirst().workoutDate()).isEqualTo(START.plusDays(9));
        assertThat(program.startDate()).isEqualTo(START);
        assertThat(program.status()).isEqualTo("draft");
    }

    @Test
    void unknownExerciseStaysInTheProgramAsANewOwnExerciseWithSuggestions() {
        var result = assemble(Map.of(), program(
                exercise("Тяга в наклоне", line(3, 8, null, new Load(null, null, 60.0, null))),
                exercise("Присед", line(3, 5, null, null))));

        var exercises = exercises(result);
        var created = result.draft().newExercises();
        assertThat(created).hasSize(1);
        var newExercise = created.getFirst();
        assertThat(newExercise.exercise().name()).isEqualTo("Тяга в наклоне");
        assertThat(newExercise.exercise().resultType()).isEqualTo("weight_reps");
        assertThat(exercises).extracting(ProgramPayloadV1.Exercise::exerciseId)
                .containsExactly(newExercise.id(), SQUAT.id());
        assertThat(exercises.getFirst().exerciseName()).isEqualTo("Тяга в наклоне");
        assertThat(exercises.getFirst().sets()).hasSize(3)
                .allMatch(set -> set.targetWeightGrams() == 60_000 && set.repetitions() == 8);
        var warning = result.warnings().stream().filter(w -> w.code().equals("new_exercise")).findFirst()
                .orElseThrow();
        assertThat(warning.exercise()).isEqualTo("Тяга в наклоне");
        assertThat(warning.exerciseId()).isEqualTo(newExercise.id());
        assertThat(warning.suggestions()).extracting(suggestion -> suggestion.exerciseId())
                .containsExactly(SIMILAR.id());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(result.draft().program())).isEmpty();
            assertThat(validator.validate(newExercise.exercise())).isEmpty();
        }
    }

    @Test
    void sameUnknownNameGivesOneNewExerciseAndOneWarning() {
        var result = assemble(Map.of(), program(
                exercise("Гуд морнинг", line(3, 8, null, null)),
                exercise("гуд  морнинг", line(2, 10, null, null)),
                exercise("Растяжка", line(1, null, null, null))));

        assertThat(result.draft().newExercises()).extracting(created -> created.exercise().name())
                .containsExactly("Гуд морнинг", "Растяжка");
        assertThat(result.draft().newExercises().get(1).exercise().resultType()).as("без повторов и веса")
                .isEqualTo("free");
        var exercises = exercises(result);
        assertThat(exercises.get(0).exerciseId()).isEqualTo(exercises.get(1).exerciseId());
        assertThat(result.warnings()).filteredOn(w -> w.code().equals("new_exercise")).hasSize(2);
    }

    @Test
    void percentOfANewExerciseStaysInTheNote() {
        var result = assemble(Map.of(), program(exercise("Жим Ларсена", line(3, 5, null, percent(70)))));

        assertThat(sets(result)).allMatch(set -> set.targetWeightGrams() == null && "70% от 1ПМ".equals(set.note()));
    }

    @Test
    void idsAreGeneratedByTheServerAndAssumptionsBecomeWarnings() {
        var parsed = new ParsedReply(List.of(), List.of("Тренировки в пн и пт"),
                program(exercise("Присед", line(2, 5, null, null))).program());

        var result = assemble(Map.of(), parsed);

        assertThat(result.draft().programId()).isNotNull();
        assertThat(sets(result)).extracting(ProgramPayloadV1.Set::id).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(result.warnings()).extracting(warning -> warning.message()).contains("Тренировки в пн и пт");
    }

    private static List<ProgramPayloadV1.Set> squatSets(Map<UUID, Integer> oneRepMaxes, SetLine... lines) {
        return sets(assemble(oneRepMaxes, program(exercise("Присед", lines))));
    }

    private static DraftAssembler.Result assemble(Map<UUID, Integer> oneRepMaxes, ParsedReply parsed) {
        return new DraftAssembler(DraftAssemblerTest::match, oneRepMaxes, START, NOW).assemble(parsed);
    }

    private static ExerciseMatcher.Match match(String name) {
        var found = CATALOG.get(ExerciseMatcher.normalize(name));
        return found == null ? new ExerciseMatcher.Match(Optional.empty(), List.of(SIMILAR))
                : new ExerciseMatcher.Match(Optional.of(found), List.of());
    }

    private static ParsedReply program(Exercise... exercises) {
        return new ParsedReply(List.of(), List.of(), new Program("Программа", null,
                List.of(new Week(1, null, List.of(new Workout(1, "День 1", List.of(exercises)))))));
    }

    private static Exercise exercise(String name, SetLine... lines) {
        return new Exercise(name, null, List.of(lines));
    }

    private static SetLine line(int count, Integer reps, String type, Load load) {
        return new SetLine(count, reps, type, load);
    }

    private static Load percent(double value) {
        return new Load(value, null, null, null);
    }

    private static List<ProgramPayloadV1.Exercise> exercises(DraftAssembler.Result result) {
        return result.draft().program().cycles().getFirst().weeks().getFirst().workouts().getFirst().blocks()
                .stream().flatMap(block -> block.exercises().stream()).toList();
    }

    private static List<ProgramPayloadV1.Set> sets(DraftAssembler.Result result) {
        return exercises(result).stream().flatMap(exercise -> exercise.sets().stream()).toList();
    }
}
