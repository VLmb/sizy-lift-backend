package com.sizylift.backend.imports.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.imports.model.ProgramParserModel.CatalogEntry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static com.sizylift.backend.imports.dto.ImportLimits.*;

/**
 * Проверяет компактные ответы модели и переводит их в {@link ImportDraftV1}. Ключи узлов назначает сервер,
 * alias каталога заменяются UUID снимка. Ошибки сформулированы в терминах ответа модели: при техническом
 * повторе они передаются ей, чтобы повтор исправлял конкретные места, а не был случайной перегенерацией.
 */
@Component
public class ImportDraftValidator {
    private static final int MAX_JSON_CHARS = 1_000_000;
    private static final int MAX_NODES = 20_000;
    private static final int MAX_ERRORS = 10;
    private static final int MAX_FIELD = 100;
    /** Новые упражнения части получают ключ k{первая неделя части}n{i}: уникален между параллельными частями. */
    private static final String CHUNK_PREFIX = "k";
    private static final String CYCLE_PREFIX = "c";
    private static final Pattern COUNT = Pattern.compile("[1-9]\\d{0,2}");
    private static final Pattern REPS = Pattern.compile("AMRAP|[1-9]\\d{0,3}(?:-[1-9]\\d{0,3})?");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern NUMBER_RANGE = Pattern.compile("\\d+(?:[.,]\\d+)?(?:-\\d+(?:[.,]\\d+)?)?");
    private static final Set<String> SET_TYPES = Set.of("warmup", "working", "top", "backoff", "drop");
    private static final Set<String> FIELDS = Set.of("n", "r", "kg", "pct", "sec", "m", "rpe", "rir", "rest", "type");
    private final Schema planSchema = schema("program-import.plan.v1.schema.json");
    private final Schema weeksSchema = schema("program-import.weeks.v1.schema.json");
    private final ObjectMapper json;

    public ImportDraftValidator(ObjectMapper json) { this.json = json; }

    /** Итог первого вызова: вопрос, вся программа целиком или план для разбора по частям. */
    public sealed interface Outcome permits Question, Complete, Planned { }
    public record Question(ImportDraftV1 draft) implements Outcome { }
    public record Complete(ImportDraftV1 draft) implements Outcome { }
    public record Planned(ImportPlan plan) implements Outcome { }
    /** Подробно разобранные недели одной части и упражнения, которых не было в плане. */
    public record Chunk(List<ImportDraftV1.Resolution> extras, List<ImportDraftV1.Week> weeks) { }

    public Outcome plan(String text, Source source, List<CatalogEntry> catalog, List<Clarification> history) {
        var answer = read(text, planSchema, ImportModelDraft.Plan.class);
        var translation = new Translation(source, catalog, history);
        if (!answer.question().isBlank()) {
            // Частичный разбор при вопросе не нужен: после ответа модель всё равно разбирает источник заново.
            translation.check(answer.question().length() <= NOTE_CHARS, "question", "слишком длинный вопрос");
            translation.finish();
            return new Question(new ImportDraftV1(1, true, answer.question().trim(),
                    new ImportDraftV1.Draft("", "", List.of()), List.of()));
        }
        translation.check(answer.name().length() <= NAME_CHARS, "name", "длиннее " + NAME_CHARS + " символов");
        translation.check(answer.description().length() <= NOTE_CHARS, "description", "длиннее " + NOTE_CHARS + " символов");
        var exercises = new LinkedHashMap<String, ImportDraftV1.Resolution>();
        translation.check(answer.exercises().size() <= RESOLUTIONS, "exercises", "больше " + RESOLUTIONS + " упражнений");
        for (var exercise : answer.exercises()) {
            var where = "exercises[" + exercise.id() + "]";
            if (translation.check(!exercises.containsKey(exercise.id()), where, "повторный id"))
                exercises.put(exercise.id(), translation.resolution(exercise, exercise.id(), where));
        }
        translation.check(!exercises.isEmpty(), "exercises", "нет ни одного упражнения; разбери программу или задай question");
        var weeks = new ArrayList<ImportPlan.Week>();
        var numbers = new HashSet<Integer>();
        for (var week : answer.plan()) {
            var where = "plan[week=" + week.week() + "]";
            translation.check(numbers.add(week.week()), where, "номер недели повторяется; номера недель общие для всей программы");
            translation.check(week.name().length() <= NAME_CHARS && week.cycle().length() <= NAME_CHARS, where,
                    "name или cycle длиннее " + NAME_CHARS + " символов");
            weeks.add(new ImportPlan.Week(week.week(), week.cycle().trim(), week.name().trim(), week.workouts()));
        }
        translation.check(!weeks.isEmpty(), "plan", "пустой план; перечисли все недели программы");
        weeks.sort(Comparator.comparingInt(ImportPlan.Week::week));
        var plan = new ImportPlan(answer.name().trim(), answer.description().trim(), List.copyOf(exercises.values()), List.copyOf(weeks));
        // Недели проверяются в том же проходе: повтор получает все ошибки ответа сразу.
        var chunk = answer.weeks().isEmpty() ? List.<ImportDraftV1.Week>of()
                : translation.weeks(answer.weeks(), plan, numbers, exercises.keySet(), "");
        translation.finish();
        if (answer.weeks().isEmpty()) return new Planned(plan);
        return new Complete(assemble(plan, List.of(new Chunk(List.of(), chunk))));
    }

    /** Ответ emit_program_weeks для недель requested. */
    public Chunk weeks(String text, ImportPlan plan, List<Integer> requested, Source source, List<CatalogEntry> catalog,
                       List<Clarification> history) {
        var answer = read(text, weeksSchema, ImportModelDraft.Weeks.class);
        var translation = new Translation(source, catalog, history);
        var tag = CHUNK_PREFIX + requested.stream().mapToInt(Integer::intValue).min().orElse(0);
        var extras = new LinkedHashMap<String, ImportDraftV1.Resolution>();
        var known = new HashMap<String, String>();
        for (var exercise : plan.exercises()) known.put(exercise.key(), exercise.key());
        for (var exercise : answer.exercises()) {
            var where = "exercises[" + exercise.id() + "]";
            if (!translation.check(!known.containsKey(exercise.id()), where, "повторный id")) continue;
            var key = tag + exercise.id();
            known.put(exercise.id(), key);
            extras.put(key, translation.resolution(exercise, key, where));
        }
        var weeks = translation.weeks(answer.weeks(), plan, new TreeSet<>(requested), known.keySet(), tag);
        translation.finish();
        var renamed = weeks.stream().map(week -> rename(week, known)).toList();
        return new Chunk(List.copyOf(extras.values()), renamed);
    }

    /**
     * Сводит части в черновик. Новое упражнение части, совпавшее по названию и решению с уже известным,
     * объединяется с ним, чтобы пользователь не выбирал его повторно. Неиспользованные упражнения
     * не попадают в выбор и не создаются при commit.
     */
    public ImportDraftV1 assemble(ImportPlan plan, List<Chunk> chunks) {
        var resolutions = new LinkedHashMap<String, ImportDraftV1.Resolution>();
        var identity = new HashMap<String, String>();
        var canonical = new HashMap<String, String>();
        for (var exercise : plan.exercises()) {
            resolutions.put(exercise.key(), exercise);
            identity.putIfAbsent(identity(exercise), exercise.key());
            canonical.put(exercise.key(), exercise.key());
        }
        for (var chunk : chunks) {
            for (var extra : chunk.extras()) {
                var same = identity.get(identity(extra));
                if (same != null) canonical.put(extra.key(), same);
                else {
                    resolutions.put(extra.key(), extra);
                    identity.put(identity(extra), extra.key());
                    canonical.put(extra.key(), extra.key());
                }
            }
        }
        var byNumber = new HashMap<Integer, ImportPlan.Week>();
        for (var week : plan.weeks()) byNumber.put(week.week(), week);
        var weeks = chunks.stream().flatMap(chunk -> chunk.weeks().stream()).map(week -> rename(week, canonical))
                .sorted(Comparator.comparingInt(ImportDraftV1.Week::number)).toList();
        var cycles = new ArrayList<ImportDraftV1.Cycle>();
        String cycleName = null;
        List<ImportDraftV1.Week> current = null;
        var used = new HashSet<String>();
        int nodes = 0;
        for (var week : weeks) {
            var meta = byNumber.get(week.number());
            var cycle = meta == null ? "" : meta.cycle();
            if (current == null || !cycle.equals(cycleName)) {
                cycleName = cycle;
                current = new ArrayList<>();
                cycles.add(new ImportDraftV1.Cycle(CYCLE_PREFIX + (cycles.size() + 1), cycle, current));
                nodes++;
            }
            current.add(new ImportDraftV1.Week(week.key(), week.number(), meta == null ? "" : meta.name(), week.workouts()));
            nodes++;
            for (var day : week.workouts()) {
                nodes++;
                for (var block : day.blocks()) {
                    nodes++;
                    for (var exercise : block.exercises()) {
                        used.add(exercise.resolutionKey());
                        nodes += 1 + exercise.sets().size();
                    }
                }
            }
        }
        var errors = new ArrayList<String>();
        if (nodes > MAX_NODES) errors.add("weeks: слишком много узлов программы");
        if (cycles.size() > CYCLES) errors.add("plan: больше " + CYCLES + " циклов");
        if (used.isEmpty()) errors.add("weeks: нет ни одного упражнения");
        if (!errors.isEmpty()) throw new InvalidDraft(errors);
        var selected = resolutions.values().stream().filter(r -> used.contains(r.key())).toList();
        return new ImportDraftV1(1, false, "", new ImportDraftV1.Draft(plan.name(), plan.description(), cycles), selected);
    }

    private <T> T read(String text, Schema schema, Class<T> type) {
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
        try {
            return json.readValue(text, type);
        } catch (RuntimeException e) {
            throw new InvalidDraft(List.of("Ответ не соответствует схеме инструмента."));
        }
    }

    private static ImportDraftV1.Week rename(ImportDraftV1.Week week, Map<String, String> keys) {
        return new ImportDraftV1.Week(week.key(), week.number(), week.name(), week.workouts().stream()
                .map(day -> new ImportDraftV1.Day(day.key(), day.title(), day.dayOffset(), day.blocks().stream()
                        .map(block -> new ImportDraftV1.Block(block.key(), block.blockType(), block.groupLabel(), block.title(),
                                block.description(), block.exercises().stream()
                                .map(ex -> new ImportDraftV1.Exercise(ex.key(), keys.getOrDefault(ex.resolutionKey(), ex.resolutionKey()),
                                        ex.comment(), ex.sourceRefs(), ex.sets())).toList())).toList())).toList());
    }

    private static String identity(ImportDraftV1.Resolution resolution) {
        return normalize(resolution.originalName()) + "|" + resolution.status() + "|" + resolution.exerciseId();
    }

    private static final class Translation {
        private final Map<String, String> refs = new HashMap<>();
        private final Map<String, UUID> aliases = new HashMap<>();
        private final List<String> errors = new ArrayList<>();

        Translation(Source source, List<CatalogEntry> catalog, List<Clarification> history) {
            for (var item : source.items()) refs.put(item.ref(), item.text());
            for (int i = 0; i < history.size(); i++) refs.put("answer:" + (i + 1), history.get(i).answer());
            for (var entry : catalog) aliases.put(entry.alias(), entry.id());
        }

        ImportDraftV1.Resolution resolution(ImportModelDraft.Exercise exercise, String key, String where) {
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
            var candidates = exercise.candidates().stream().map(aliases::get).filter(Objects::nonNull)
                    .distinct().limit(10).map(UUID::toString).toList();
            check(exercise.newName().length() <= NAME_CHARS, where, "newName длиннее " + NAME_CHARS + " символов");
            return new ImportDraftV1.Resolution(key, name, exercise.status(), exerciseId, candidates,
                    exercise.newName().trim(), exercise.resultType(), List.copyOf(exercise.refs()));
        }

        /** Недели ответа должны совпасть с expected; ключи узлов строятся от номера недели и уникальны во всей программе. */
        List<ImportDraftV1.Week> weeks(List<ImportModelDraft.Week> weeks, ImportPlan plan, Set<Integer> expected,
                                       Set<String> exercises, String tag) {
            var seen = new HashSet<Integer>();
            var result = new ArrayList<ImportDraftV1.Week>();
            for (var week : weeks) {
                var where = "weeks[week=" + week.week() + "]";
                if (!check(seen.add(week.week()), where, "неделя повторяется")) continue;
                if (!check(expected.contains(week.week()), where, "этой недели нет в " + (tag.isEmpty() ? "plan" : "ЗАДАЧЕ"))) continue;
                check(week.days().size() <= WORKOUTS_PER_WEEK, where, "больше " + WORKOUTS_PER_WEEK + " тренировок");
                var weekKey = "w" + week.week();
                var days = new ArrayList<ImportDraftV1.Day>();
                for (int d = 0; d < week.days().size(); d++)
                    days.add(day(week.days().get(d), weekKey + "d" + (d + 1), where + ".days[" + d + "]", week.week(), exercises));
                result.add(new ImportDraftV1.Week(weekKey, week.week(), "", days));
            }
            for (var missing : expected) check(seen.contains(missing), "weeks", "нет недели " + missing + "; разбери все недели "
                    + (tag.isEmpty() ? "plan" : "из ЗАДАЧИ"));
            return result;
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
                blocks.add(new ImportDraftV1.Block(blockKey, block.type(), label, block.title().trim(), block.note().trim(), items));
            }
            return new ImportDraftV1.Day(key, day.title().trim(), (week - 1) * DAYS_PER_WEEK + day.day(), blocks);
        }

        private ImportDraftV1.Exercise item(ImportModelDraft.Item item, String key, String where, Set<String> exercises) {
            check(exercises.contains(item.ex()), where, "ex «" + item.ex() + "» нет в exercises");
            check(item.note().length() <= NOTE_CHARS, where, "note длиннее " + NOTE_CHARS + " символов");
            references(item.refs(), where);
            check(item.sets().size() <= SETS_PER_EXERCISE, where, "больше " + SETS_PER_EXERCISE + " строк подходов");
            var sets = new ArrayList<ImportDraftV1.Prescription>();
            for (int s = 0; s < item.sets().size(); s++)
                sets.add(set(item.sets().get(s), key + "s" + (s + 1), where + ".sets[" + s + "]", item.refs()));
            return new ImportDraftV1.Exercise(key, item.ex(), item.note().trim(), List.copyOf(item.refs()), sets);
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
            return new ImportDraftV1.Prescription(key, count, repetitions, values.getOrDefault("kg", ""),
                    values.getOrDefault("pct", ""), values.getOrDefault("sec", ""), values.getOrDefault("m", ""),
                    values.getOrDefault("rpe", ""), rir, values.getOrDefault("rest", ""), type, note, List.copyOf(sourceRefs));
        }

        private static boolean repetitions(String value) {
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

        boolean check(boolean condition, String where, String message) {
            if (!condition && errors.size() < MAX_ERRORS) errors.add(where + ": " + message);
            return condition;
        }

        void finish() { if (!errors.isEmpty()) throw new InvalidDraft(errors); }
    }

    private static Schema schema(String name) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(SchemaLocation.of("classpath:imports/" + name));
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
