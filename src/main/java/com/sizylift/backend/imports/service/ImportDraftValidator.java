package com.sizylift.backend.imports.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.web.dto.WebDtos.CatalogExercise;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static com.sizylift.backend.imports.dto.ImportLimits.*;

@Component
public class ImportDraftValidator {
    private static final int MAX_JSON_CHARS = 1_000_000;
    private static final int MAX_NODES = 20_000;
    private static final int MAX_TEXT = NOTE_CHARS;
    private final Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("classpath:imports/program-import.v1.schema.json"));
    private final ObjectMapper json;

    public ImportDraftValidator(ObjectMapper json) { this.json = json; }

    public ImportDraftV1 parse(String text, Source source, List<CatalogExercise> catalog, List<Clarification> history) {
        try {
            require(text != null && text.length() <= MAX_JSON_CHARS);
            require(schema.validate(text, InputFormat.JSON).isEmpty());
            var draft = json.readValue(text, ImportDraftV1.class);
            validate(draft, source, catalog, history);
            return draft;
        } catch (InvalidDraft e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidDraft();
        }
    }

    private void validate(ImportDraftV1 draft, Source source, List<CatalogExercise> catalog, List<Clarification> history) {
        require(draft.schemaVersion() == 1);
        require(draft.needsClarification() ? !draft.clarificationMessage().isBlank() : draft.clarificationMessage().isEmpty());
        require(draft.clarificationMessage().length() <= MAX_TEXT);
        var refs = source.items().stream().collect(Collectors.toMap(item -> item.ref(), item -> item.text()));
        for (int i = 0; i < history.size(); i++) refs.put("answer:" + (i + 1), history.get(i).answer());
        var ids = catalog.stream().map(CatalogExercise::id).collect(Collectors.toSet());
        var resolutions = new HashSet<String>();
        for (var resolution : draft.exerciseResolutions()) {
            require(resolutions.add(resolution.key()));
            require(!resolution.originalName().isBlank() && resolution.originalName().length() <= NAME_CHARS);
            references(resolution.sourceRefs(), refs);
            var original = normalize(resolution.originalName());
            require(normalize(resolution.sourceRefs().stream().map(refs::get).collect(Collectors.joining(" "))).contains(original));
            if (resolution.status().equals("matched")) require(ids.contains(uuid(resolution.exerciseId())));
            else require(resolution.exerciseId().isEmpty());
            require(resolution.candidates().size() <= 10);
            for (var id : resolution.candidates()) require(ids.contains(uuid(id)));
            require(resolution.proposedName().length() <= NAME_CHARS);
        }
        require(resolutions.size() <= RESOLUTIONS);
        var keys = new HashSet<String>();
        var weekNumbers = new HashSet<Integer>();
        int exercises = 0;
        require(draft.programDraft().cycles().size() <= CYCLES);
        require(draft.programDraft().description().length() <= MAX_TEXT);
        require(draft.programDraft().name().length() <= NAME_CHARS);
        for (var cycle : draft.programDraft().cycles()) {
            key(keys, cycle.key());
            require(cycle.name().length() <= NAME_CHARS && cycle.weeks().size() <= WEEKS);
            for (var week : cycle.weeks()) {
                key(keys, week.key());
                require(weekNumbers.add(week.number()));
                require(week.name().length() <= NAME_CHARS && week.workouts().size() <= WORKOUTS_PER_WEEK);
                for (var day : week.workouts()) {
                    key(keys, day.key());
                    require(day.title().length() <= NAME_CHARS && day.blocks().size() <= BLOCKS);
                    require(draft.needsClarification() || day.dayOffset() >= (week.number() - 1) * DAYS_PER_WEEK && day.dayOffset() < week.number() * DAYS_PER_WEEK);
                    for (var block : day.blocks()) {
                        key(keys, block.key());
                        require(block.title().length() <= NAME_CHARS && block.description().length() <= MAX_TEXT);
                        require(block.exercises().size() <= EXERCISES_PER_BLOCK);
                        require(block.groupLabel().isEmpty() || block.blockType().equals("group"));
                        for (var exercise : block.exercises()) {
                            key(keys, exercise.key());
                            require(resolutions.contains(exercise.resolutionKey()));
                            require(exercise.comment().length() <= MAX_TEXT && exercise.sets().size() <= SETS_PER_EXERCISE);
                            references(exercise.sourceRefs(), refs);
                            exercises++;
                            for (var set : exercise.sets()) {
                                key(keys, set.key());
                                references(set.sourceRefs(), refs);
                                require(set.note().length() <= MAX_TEXT);
                                for (var field : List.of(set.count(), set.repetitions(), set.weightKg(), set.percentOneRm(),
                                        set.durationSeconds(), set.distanceMeters(), set.rpe(), set.rir(), set.restSeconds()))
                                    require(field.length() <= 100);
                                if (!draft.needsClarification()) quantities(set);
                            }
                        }
                    }
                }
            }
        }
        require(draft.needsClarification() || exercises > 0);
    }

    private void references(List<String> values, Map<String, String> refs) {
        require(!values.isEmpty() && values.size() <= 100);
        for (var value : values) require(refs.containsKey(value));
    }
    private void key(Set<String> keys, String key) { require(keys.size() < MAX_NODES && keys.add(key)); }
    private String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private void quantities(ImportDraftV1.Prescription set) {
        number(set.count());
        if (!set.count().isBlank()) {
            var count = new java.math.BigDecimal(set.count());
            require(count.scale() <= 0 && count.intValueExact() >= 1 && count.intValueExact() <= SETS_PER_EXERCISE);
        }
        for (var repetitions : set.repetitions().split("-")) {
            if (!repetitions.equals("AMRAP")) number(repetitions);
        }
        if (!set.repetitions().isBlank() && !set.repetitions().equals("AMRAP")) {
            require(set.repetitions().matches("[1-9]\\d{0,3}(-[1-9]\\d{0,3})?"));
            var range = set.repetitions().split("-");
            require(Integer.parseInt(range[0]) <= REPETITIONS && Integer.parseInt(range[range.length - 1]) <= REPETITIONS
                    && Integer.parseInt(range[0]) <= Integer.parseInt(range[range.length - 1]));
        }
        number(set.weightKg());
        number(set.percentOneRm());
        number(set.durationSeconds());
        number(set.restSeconds());
        number(set.distanceMeters());
        number(set.rpe());
        if (!set.rir().isBlank()) for (var rir : set.rir().split("-")) number(rir);
    }
    private void number(String text) {
        if (text.isBlank()) return;
        require(text.matches("\\d+(?:[.,]\\d+)?"));
    }
    private UUID uuid(String value) { try { return UUID.fromString(value); } catch (IllegalArgumentException e) { throw new InvalidDraft(); } }
    private static void require(boolean condition) { if (!condition) throw new InvalidDraft(); }
    public static class InvalidDraft extends RuntimeException {
        public InvalidDraft() { super("Ответ не соответствует контракту импорта"); }
    }
}
