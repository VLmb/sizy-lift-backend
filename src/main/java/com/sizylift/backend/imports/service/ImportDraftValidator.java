package com.sizylift.backend.imports.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportModelDraft;
import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.imports.model.ProgramParserModel.CatalogEntry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static com.sizylift.backend.imports.dto.ImportLimits.*;

/**
 * Проверяет компактный ответ модели и переводит его в {@link ImportDraftV1}. Ключи узлов назначает сервер,
 * alias каталога заменяются UUID снимка. Ошибки сформулированы в терминах ответа модели: при техническом
 * повторе они передаются ей, чтобы повтор исправлял конкретные места, а не был случайной перегенерацией.
 */
@Component
public class ImportDraftValidator {
    private static final int MAX_JSON_CHARS = 1_000_000;
    private static final int MAX_NODES = 20_000;
    private static final int MAX_ERRORS = 10;
    private static final int MAX_FIELD = 100;
    private static final Pattern COUNT = Pattern.compile("[1-9]\\d{0,2}");
    private static final Pattern REPS = Pattern.compile("AMRAP|[1-9]\\d{0,3}(?:-[1-9]\\d{0,3})?");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern NUMBER_RANGE = Pattern.compile("\\d+(?:[.,]\\d+)?(?:-\\d+(?:[.,]\\d+)?)?");
    private static final Set<String> SET_TYPES = Set.of("warmup", "working", "top", "backoff", "drop");
    private static final Set<String> FIELDS = Set.of("n", "r", "kg", "pct", "sec", "m", "rpe", "rir", "rest", "type");
    private final Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("classpath:imports/program-import.compact.v1.schema.json"));
    private final ObjectMapper json;

    public ImportDraftValidator(ObjectMapper json) { this.json = json; }

    public ImportDraftV1 parse(String text, Source source, List<CatalogEntry> catalog, List<Clarification> history) {
        if (text == null || text.isBlank() || text.length() > MAX_JSON_CHARS)
            throw new InvalidDraft(List.of("Ответ пустой или слишком большой."));
        java.util.Collection<?> structural;
        try {
            structural = schema.validate(text, InputFormat.JSON);
        } catch (RuntimeException e) {
            throw new InvalidDraft(List.of("Ответ не является корректным JSON."));
        }
        if (!structural.isEmpty())
            throw new InvalidDraft(structural.stream().limit(MAX_ERRORS).map(error -> "Схема: " + error).toList());
        ImportModelDraft draft;
        try {
            draft = json.readValue(text, ImportModelDraft.class);
        } catch (RuntimeException e) {
            throw new InvalidDraft(List.of("Ответ не соответствует схеме emit_program_import."));
        }
        return new Translation(source, catalog, history).run(draft);
    }

    private static final class Translation {
        private final Map<String, String> refs = new HashMap<>();
        private final Map<String, UUID> aliases = new HashMap<>();
        private final List<String> errors = new ArrayList<>();
        private final Set<String> usedExercises = new HashSet<>();
        private int nodes;

        Translation(Source source, List<CatalogEntry> catalog, List<Clarification> history) {
            for (var item : source.items()) refs.put(item.ref(), item.text());
            for (int i = 0; i < history.size(); i++) refs.put("answer:" + (i + 1), history.get(i).answer());
            for (var entry : catalog) aliases.put(entry.alias(), entry.id());
        }

        ImportDraftV1 run(ImportModelDraft draft) {
            if (!draft.question().isBlank()) {
                // Частичный разбор при вопросе не нужен: после ответа модель всё равно разбирает источник заново.
                check(draft.question().length() <= NOTE_CHARS, "question", "слишком длинный вопрос");
                finish();
                return new ImportDraftV1(1, true, draft.question().trim(), new ImportDraftV1.Draft("", "", List.of()), List.of());
            }
            check(draft.name().length() <= NAME_CHARS, "name", "длиннее " + NAME_CHARS + " символов");
            check(draft.description().length() <= NOTE_CHARS, "description", "длиннее " + NOTE_CHARS + " символов");
            var resolutions = resolutions(draft.exercises());
            var cycles = cycles(draft.weeks(), resolutions.keySet());
            check(!usedExercises.isEmpty(), "weeks", "нет ни одного упражнения; разбери программу или задай question");
            finish();
            // Неиспользованное в программе упражнение не должно попадать в выбор и создаваться при commit.
            var used = resolutions.values().stream().filter(r -> usedExercises.contains(r.key())).toList();
            return new ImportDraftV1(1, false, "", new ImportDraftV1.Draft(draft.name().trim(), draft.description().trim(), cycles), used);
        }

        private Map<String, ImportDraftV1.Resolution> resolutions(List<ImportModelDraft.Exercise> exercises) {
            var result = new LinkedHashMap<String, ImportDraftV1.Resolution>();
            check(exercises.size() <= RESOLUTIONS, "exercises", "больше " + RESOLUTIONS + " упражнений");
            for (var exercise : exercises) {
                var where = "exercises[" + exercise.id() + "]";
                if (!check(!result.containsKey(exercise.id()), where, "повторный id")) continue;
                var name = exercise.name().trim();
                check(!name.isEmpty() && name.length() <= NAME_CHARS, where, "name пустое или длиннее " + NAME_CHARS + " символов");
                if (references(exercise.refs(), where) && !name.isEmpty()) {
                    var quoted = normalize(exercise.refs().stream().map(refs::get).collect(Collectors.joining(" ")));
                    check(quoted.contains(normalize(name)), where,
                            "name «" + name + "» не найдено в тексте refs; name — название как в источнике, refs — где оно написано");
                }
                var exerciseId = "";
                if (exercise.status().equals("matched")) {
                    var id = aliases.get(exercise.match());
                    if (check(id != null, where, "status=matched, но match «" + exercise.match() + "» не alias из КАТАЛОГА"))
                        exerciseId = id.toString();
                }
                // Кандидаты — только подсказка для выбора; неизвестный alias безопасно отбросить.
                var candidates = exercise.candidates().stream().map(aliases::get).filter(java.util.Objects::nonNull)
                        .distinct().limit(10).map(UUID::toString).toList();
                check(exercise.newName().length() <= NAME_CHARS, where, "newName длиннее " + NAME_CHARS + " символов");
                result.put(exercise.id(), new ImportDraftV1.Resolution(exercise.id(), name, exercise.status(), exerciseId,
                        candidates, exercise.newName().trim(), exercise.resultType(), List.copyOf(exercise.refs())));
            }
            return result;
        }

        private List<ImportDraftV1.Cycle> cycles(List<ImportModelDraft.Week> weeks, Set<String> exercises) {
            var cycles = new ArrayList<ImportDraftV1.Cycle>();
            var numbers = new HashSet<Integer>();
            String cycleName = null;
            List<ImportDraftV1.Week> current = null;
            for (var week : weeks) {
                var where = "weeks[week=" + week.week() + "]";
                check(numbers.add(week.week()), where, "номер недели повторяется; номера недель общие для всей программы");
                check(week.name().length() <= NAME_CHARS && week.cycle().length() <= NAME_CHARS, where,
                        "name или cycle длиннее " + NAME_CHARS + " символов");
                check(week.days().size() <= WORKOUTS_PER_WEEK, where, "больше " + WORKOUTS_PER_WEEK + " тренировок");
                if (current == null || !week.cycle().trim().equals(cycleName)) {
                    cycleName = week.cycle().trim();
                    current = new ArrayList<>();
                    cycles.add(new ImportDraftV1.Cycle(node("c" + (cycles.size() + 1)), cycleName, current));
                }
                var weekKey = "w" + week.week();
                var days = new ArrayList<ImportDraftV1.Day>();
                for (int d = 0; d < week.days().size(); d++)
                    days.add(day(week.days().get(d), weekKey + "d" + (d + 1), where + ".days[" + d + "]", week.week(), exercises));
                current.add(new ImportDraftV1.Week(node(weekKey), week.week(), week.name().trim(), days));
            }
            check(cycles.size() <= CYCLES, "weeks", "больше " + CYCLES + " циклов");
            return cycles;
        }

        private ImportDraftV1.Day day(ImportModelDraft.Day day, String key, String where, int week, Set<String> exercises) {
            check(day.title().length() <= NAME_CHARS, where, "title длиннее " + NAME_CHARS + " символов");
            check(day.blocks().size() <= BLOCKS, where, "больше " + BLOCKS + " блоков");
            var blocks = new ArrayList<ImportDraftV1.Block>();
            for (int b = 0; b < day.blocks().size(); b++) {
                var block = day.blocks().get(b);
                var blockKey = key + "b" + (b + 1);
                var blockWhere = where + ".blocks[" + b + "]";
                check(block.title().length() <= NAME_CHARS && block.note().length() <= NOTE_CHARS, blockWhere, "title или note слишком длинные");
                check(block.items().size() <= EXERCISES_PER_BLOCK, blockWhere, "больше " + EXERCISES_PER_BLOCK + " упражнений");
                var items = new ArrayList<ImportDraftV1.Exercise>();
                for (int i = 0; i < block.items().size(); i++)
                    items.add(item(block.items().get(i), blockKey + "x" + (i + 1), blockWhere + ".items[" + i + "]", exercises));
                // Метка группы осмысленна только для group; для остальных типов безопасно отбрасывается.
                var label = block.type().equals("group") ? block.label() : "";
                blocks.add(new ImportDraftV1.Block(node(blockKey), block.type(), label, block.title().trim(), block.note().trim(), items));
            }
            return new ImportDraftV1.Day(node(key), day.title().trim(), (week - 1) * DAYS_PER_WEEK + day.day(), blocks);
        }

        private ImportDraftV1.Exercise item(ImportModelDraft.Item item, String key, String where, Set<String> exercises) {
            var known = check(exercises.contains(item.ex()), where, "ex «" + item.ex() + "» нет в exercises");
            if (known) usedExercises.add(item.ex());
            check(item.note().length() <= NOTE_CHARS, where, "note длиннее " + NOTE_CHARS + " символов");
            references(item.refs(), where);
            check(item.sets().size() <= SETS_PER_EXERCISE, where, "больше " + SETS_PER_EXERCISE + " строк подходов");
            var sets = new ArrayList<ImportDraftV1.Prescription>();
            for (int s = 0; s < item.sets().size(); s++)
                sets.add(set(item.sets().get(s), key + "s" + (s + 1), where + ".sets[" + s + "]", item.refs()));
            return new ImportDraftV1.Exercise(node(key), item.ex(), item.note().trim(), List.copyOf(item.refs()), sets);
        }

        /** {@code "n=3 r=5 kg=100 rpe=8 type=working | заметка"}; пропущенное поле — неизвестное значение. */
        private ImportDraftV1.Prescription set(String raw, String key, String where, List<String> sourceRefs) {
            var bar = raw.indexOf('|');
            var spec = (bar < 0 ? raw : raw.substring(0, bar)).trim();
            var note = bar < 0 ? "" : raw.substring(bar + 1).trim();
            var values = new HashMap<String, String>();
            var label = where + " «" + abbreviate(raw) + "»";
            for (var token : spec.isEmpty() ? new String[0] : spec.split("\\s+")) {
                int eq = token.indexOf('=');
                var field = eq <= 0 ? "" : token.substring(0, eq).toLowerCase(Locale.ROOT);
                var value = eq <= 0 ? "" : token.substring(eq + 1);
                if (!check(FIELDS.contains(field) && !value.isEmpty() && value.length() <= MAX_FIELD, label,
                        "фрагмент «" + abbreviate(token) + "» не поле n,r,kg,pct,sec,m,rpe,rir,rest,type; текст переноси в заметку после |"))
                    continue;
                check(values.put(field, value) == null, label, "поле " + field + " указано дважды");
            }
            check(!values.isEmpty() || !note.isEmpty(), label, "пустая строка подхода");
            check(note.length() <= NOTE_CHARS, label, "заметка длиннее " + NOTE_CHARS + " символов");
            var count = values.getOrDefault("n", "");
            if (!count.isEmpty()) check(COUNT.matcher(count).matches() && Integer.parseInt(count) <= SETS_PER_EXERCISE, label,
                    "n — целое число подходов от 1 до " + SETS_PER_EXERCISE);
            var repetitions = values.getOrDefault("r", "");
            if (repetitions.equalsIgnoreCase("amrap")) repetitions = "AMRAP";
            if (!repetitions.isEmpty()) check(repetitions(repetitions), label, "r — число, диапазон вида 8-12 или AMRAP, не больше " + REPETITIONS);
            for (var field : List.of("kg", "pct", "sec", "m", "rpe", "rest")) {
                var value = values.getOrDefault(field, "");
                if (!value.isEmpty()) check(NUMBER.matcher(value).matches(), label, field + " — неотрицательное число цифрами; иное — в заметку");
            }
            var rir = values.getOrDefault("rir", "");
            if (!rir.isEmpty()) check(NUMBER_RANGE.matcher(rir).matches(), label, "rir — число или диапазон");
            var type = values.getOrDefault("type", "");
            if (!type.isEmpty()) check(SET_TYPES.contains(type), label, "type — warmup, working, top, backoff или drop");
            return new ImportDraftV1.Prescription(node(key), count, repetitions, values.getOrDefault("kg", ""),
                    values.getOrDefault("pct", ""), values.getOrDefault("sec", ""), values.getOrDefault("m", ""),
                    values.getOrDefault("rpe", ""), rir, values.getOrDefault("rest", ""), type, note, List.copyOf(sourceRefs));
        }

        private boolean repetitions(String value) {
            if (!REPS.matcher(value).matches()) return false;
            if (value.equals("AMRAP")) return true;
            var range = value.split("-");
            int low = Integer.parseInt(range[0]);
            int high = Integer.parseInt(range[range.length - 1]);
            return low <= high && high <= REPETITIONS;
        }

        private boolean references(List<String> values, String where) {
            if (!check(!values.isEmpty() && values.size() <= 100, where, "refs пуст или больше 100 ссылок")) return false;
            var unknown = values.stream().filter(value -> !refs.containsKey(value)).findFirst();
            return check(unknown.isEmpty(), where, "ref «" + unknown.orElse("") + "» нет в ИСТОЧНИКЕ; используй только точные ref или answer:N");
        }

        private String node(String key) {
            check(++nodes <= MAX_NODES, "weeks", "слишком много узлов программы");
            return key;
        }

        private boolean check(boolean condition, String where, String message) {
            if (!condition && errors.size() < MAX_ERRORS) errors.add(where + ": " + message);
            return condition;
        }

        private void finish() { if (!errors.isEmpty()) throw new InvalidDraft(errors); }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String abbreviate(String value) {
        var single = value.replaceAll("\\s+", " ").trim();
        return single.length() <= 60 ? single : single.substring(0, 57) + "...";
    }

    /** Ответ нарушил контракт. errors — конкретные места для подсказки модели при техническом повторе. */
    public static class InvalidDraft extends RuntimeException {
        private static final int MAX_HINT_CHARS = 2000;
        private final List<String> errors;

        public InvalidDraft(List<String> errors) {
            super("Ответ не соответствует контракту импорта");
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() { return errors; }

        /** Текст для следующей попытки. Не содержит исходник целиком, только короткие цитаты мест ошибки. */
        public String repairHint() {
            var text = new StringBuilder("Предыдущий ответ отклонён сервером. Верни полный ответ заново, исправив:");
            for (var error : errors) {
                var line = error.replaceAll("\\s+", " ");
                if (text.length() + line.length() + 3 > MAX_HINT_CHARS) break;
                text.append("\n- ").append(line);
            }
            return text.toString();
        }
    }
}
