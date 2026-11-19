package com.sizylift.backend.imports.template;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportDraftV1.PlannedWeek;
import com.sizylift.backend.imports.dto.ImportDraftV1.Prescription;
import com.sizylift.backend.imports.dto.ImportDtos.Progress;
import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.imports.entity.ImportLanguage;
import com.sizylift.backend.template.SetLine;
import com.sizylift.backend.template.TemplateVocabulary;
import com.sizylift.backend.template.TemplateVocabulary.Scope;

import static com.sizylift.backend.imports.dto.ImportDraftV1.ACTIVE;
import static com.sizylift.backend.imports.dto.ImportDraftV1.DONE;
import static com.sizylift.backend.imports.dto.ImportDraftV1.FAILED;
import static com.sizylift.backend.imports.dto.ImportDraftV1.PENDING;
import static com.sizylift.backend.imports.dto.ImportLimits.NAME_CHARS;
import static com.sizylift.backend.imports.dto.ImportLimits.NOTE_CHARS;

/**
 * Собирает черновик программы из строк шаблона, которые приходят потоком из одного или нескольких параллельных
 * вызовов модели (docs/imports.md, «Шаблон»). Каждый вызов читает свои строки через {@link Cursor}; общее состояние —
 * каркас недель, упражнения и готовые недели — под блокировкой сборки. Строка не по шаблону пропускается и попадает
 * в предупреждения, остальное сохраняется: одна ошибка модели не отклоняет весь ответ.
 * {@link #snapshot(boolean)} в любой момент даёт черновик — частичный, пока недели разбираются, или итоговый.
 */
public final class ImportAssembly {
    private static final int MAX_WARNINGS = 50;
    private static final int MAX_EXERCISES = 2000;
    private static final int MAX_QUOTE = 80;
    private static final int MAX_COPY_DEPTH = 4;
    private static final String KEY_PREFIX = "e";
    private static final java.util.regex.Pattern PER_SET_REPS = java.util.regex.Pattern.compile(
            "\\|?\\s*повт\\s*=\\s*(\\d{1,4}(?:\\s*/\\s*\\d{1,4})+)\\s*", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern COUNT_KEY = java.util.regex.Pattern.compile(
            "\\|?\\s*кол\\s*=\\s*\\d+\\s*", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final String STAGE_PLAN = "plan";
    private static final String STAGE_WEEKS = "weeks";
    private static final String SINGLE = "single";
    private static final String GROUP = "group";
    private static final Map<String, String[]> BLOCKS = Map.of(
            "суперсет", new String[] {GROUP, "superset"}, "трисет", new String[] {GROUP, "triset"},
            "гигантсет", new String[] {GROUP, "giant_set"}, "круг", new String[] {"circuit", ""},
            "интервалы", new String[] {"interval", ""});
    private final ExerciseResolver resolver;
    private final SourcePrescriptions sourcePrescriptions;
    private final LocalDate startDate;
    private final ImportLanguage language;
    private final TreeMap<Integer, PlanWeek> plan = new TreeMap<>();
    private final LinkedHashMap<String, Exercise> exercises = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final TreeMap<Integer, WeekState> weeks = new TreeMap<>();
    private final LinkedHashSet<String> warnings = new LinkedHashSet<>();
    private final List<String> rules = new ArrayList<>();
    private static final int MAX_RULES = 20;
    private String name = "";
    private String description = "";
    private String question;
    private boolean planComplete;
    private long changes;

    public ImportAssembly(ExerciseResolver resolver, LocalDate startDate, ImportLanguage language) {
        this.resolver = resolver;
        this.sourcePrescriptions = new SourcePrescriptions(resolver.source());
        this.startDate = startDate;
        this.language = language;
    }

    private static final class PlanWeek {
        final int number;
        String cycle = "";
        String name = "";
        List<String> days = List.of();
        Integer copyOf;
        PlanWeek(int number) { this.number = number; }
    }

    private record Exercise(ExerciseResolver.Resolved resolved, String qualifier) { }

    private static final class WeekState {
        final int number;
        final List<DayState> days = new ArrayList<>();
        String status = ACTIVE;
        WeekState(int number) { this.number = number; }
    }

    /** {@code offset} — день, восстановленный из сохранённого черновика; иначе его считает {@link DayPlacement}. */
    private record DayState(String label, String name, Integer offset, List<BlockState> blocks) { }

    private record BlockState(String type, String label, String title, String note, List<ItemState> items) { }

    private record ItemState(String key, List<String> comments, List<Prescription> sets) { }

    // ---------------------------------------------------------------- состояние для worker

    public synchronized String question() { return question; }

    public synchronized boolean planComplete() { return planComplete; }

    public synchronized boolean hasPlan() { return !plan.isEmpty() && !exercises.isEmpty(); }

    public synchronized long changes() { return changes; }

    /** Недели, которые нужно разобрать моделью: без содержимого и не повторы других недель. */
    public synchronized List<Integer> pendingWeeks() {
        return plan.values().stream().filter(w -> w.copyOf == null && !weeks.containsKey(w.number)).map(w -> w.number).toList();
    }

    /** Тренировок в неделе по каркасу; неделя без дней всё равно считается за одну: в ней могут быть заметки. */
    public synchronized int workouts(int week) {
        var planned = plan.get(week);
        return planned == null ? 1 : Math.max(1, planned.days.size());
    }

    public synchronized int workouts() {
        return plan.values().stream().filter(w -> w.copyOf == null).mapToInt(w -> Math.max(1, w.days.size())).sum();
    }

    /**
     * Недели, которые разобрать не удалось: их содержимое не попадает в программу. Предупреждения нет — неделя видна
     * в каркасе со статусом failed.
     */
    public synchronized void fail(Collection<Integer> numbers) {
        for (var number : numbers) {
            var state = new WeekState(number);
            state.status = FAILED;
            weeks.put(number, state);
        }
        changes++;
    }

    /** Каркас для import_session.outline: после перезапуска и при повторном разборе недель план не запрашивается. */
    public synchronized ImportPlan outline() {
        var list = plan.values().stream().map(w -> new ImportPlan.Week(w.number, w.cycle, w.name, w.days.size(), w.days, w.copyOf)).toList();
        return new ImportPlan(name, description, exercises.values().stream().map(e -> e.resolved().resolution()).toList(), list,
                List.copyOf(rules));
    }

    /**
     * Продолжение сохранённого разбора: каркас и упражнения из outline, готовые недели и упражнения, добавленные
     * частями, — из черновика. Неудачные и незаконченные недели снова ждут разбора.
     */
    public synchronized void restore(ImportPlan outline, ImportDraftV1 draft) {
        name = outline.name();
        description = outline.description();
        rules.addAll(outline.rules());
        for (var week : outline.weeks()) {
            var planned = new PlanWeek(week.week());
            planned.cycle = week.cycle();
            planned.name = week.name();
            planned.days = week.days();
            planned.copyOf = week.copyOf();
            plan.put(week.week(), planned);
        }
        var resolutions = draft == null || draft.exerciseResolutions().isEmpty() ? outline.exercises() : draft.exerciseResolutions();
        for (var resolution : resolutions) {
            var resolved = resolver.restore(resolution);
            exercises.put(resolution.key(), new Exercise(resolved, ExerciseResolver.qualifier(resolution.originalName(), resolved.catalogName())));
            index(resolution.originalName(), resolution.key());
            index(resolved.catalogName(), resolution.key());
        }
        planComplete = true;
        if (draft == null) return;
        var done = new HashSet<Integer>();
        for (var week : draft.plan()) if (DONE.equals(week.status()) && week.copyOf() == null) done.add(week.number());
        for (var cycle : draft.programDraft().cycles()) {
            for (var week : cycle.weeks()) if (done.contains(week.number())) weeks.put(week.number(), restored(week));
        }
        warnings.addAll(draft.warnings().stream().filter(w -> !w.startsWith(language.text("Не удалось разобрать недели", "Could not parse weeks"))).toList());
    }

    private WeekState restored(ImportDraftV1.Week week) {
        var state = new WeekState(week.number());
        state.status = DONE;
        for (var day : week.workouts()) {
            var blocks = new ArrayList<BlockState>();
            for (var block : day.blocks()) {
                var items = new ArrayList<ItemState>();
                for (var item : block.exercises()) {
                    var comments = new ArrayList<String>();
                    if (!item.comment().isBlank()) comments.add(item.comment());
                    items.add(new ItemState(item.resolutionKey(), comments, new ArrayList<>(item.sets())));
                }
                blocks.add(new BlockState(block.blockType(), block.groupLabel(), block.title(), block.description(), items));
            }
            int offset = day.dayOffset() - (week.number() - 1) * DayPlacement.DAYS_PER_WEEK;
            state.days.add(new DayState("", day.title(), offset, blocks));
        }
        return state;
    }

    // ---------------------------------------------------------------- чтение строк одного вызова

    /** Курсор одного вызова модели. {@code allowed} — недели задачи части; null — любые (первый вызов). */
    public Cursor cursor(Set<Integer> allowed) { return new Cursor(allowed); }

    public final class Cursor {
        private final Set<Integer> allowed;
        private final List<Integer> finished = new ArrayList<>();
        private ProgramLine.Section.Kind section;
        private WeekState week;
        private DayState day;
        private BlockState group;
        private ItemState item;
        private boolean skipping;

        private Cursor(Set<Integer> allowed) { this.allowed = allowed == null ? null : Set.copyOf(allowed); }

        public void line(String raw) {
            if (raw == null || raw.isBlank()) return;
            synchronized (ImportAssembly.this) {
                accept(ProgramLine.parse(raw), raw.strip());
                changes++;
            }
        }

        /**
         * Конец вызова. Обрезанный ответ теряет незаконченную неделю: её содержимое удаляется, неделя снова ждёт
         * разбора. Возвращает недели, полностью разобранные этим вызовом.
         */
        public List<Integer> end(boolean truncated) {
            synchronized (ImportAssembly.this) {
                if (truncated && week != null) weeks.remove(week.number);
                else finishWeek();
                if (!truncated && section == ProgramLine.Section.Kind.PLAN) planComplete = true;
                if (!truncated && allowed == null && !plan.isEmpty()) planComplete = true;
                week = null;
                changes++;
                return List.copyOf(finished);
            }
        }

        private void accept(ProgramLine line, String raw) {
            switch (line) {
                case ProgramLine.Question q -> {
                    if (exercises.isEmpty() && plan.isEmpty()) question = q.text();
                }
                case ProgramLine.Program p -> {
                    if (allowed == null) {
                        name = limit(p.name(), NAME_CHARS);
                        description = limit(p.description(), NOTE_CHARS);
                    }
                }
                case ProgramLine.Section s -> section(s.kind());
                case ProgramLine.Rule r -> {
                    if (allowed == null && rules.size() < MAX_RULES) rules.add(limit(r.text(), NOTE_CHARS));
                }
                case ProgramLine.ExerciseEntry e -> exercise(e);
                case ProgramLine.Week w -> {
                    // Повтор уже объявленной недели без ключей — модель начала тренировки, забыв «Тренировки:».
                    if (section == ProgramLine.Section.Kind.PLAN && plan.containsKey(w.from()) && w.days().isEmpty()
                            && w.cycle().isEmpty() && w.copyOf() == null) section(ProgramLine.Section.Kind.WORKOUTS);
                    if (section == ProgramLine.Section.Kind.PLAN) planned(w);
                    else startWeek(w);
                }
                case ProgramLine.Day d -> startDay(d);
                case ProgramLine.Block b -> block(b);
                case ProgramLine.Item i -> item(i.name());
                case ProgramLine.SetRow r -> set(r.body());
                case ProgramLine.Comment c -> comment(c.text());
                case ProgramLine.Unknown u -> warn(language.text("Строка пропущена: «", "Line skipped: “") + quote(raw)
                        + language.text("»", "”"));
            }
        }

        private void section(ProgramLine.Section.Kind kind) {
            if (section == ProgramLine.Section.Kind.PLAN && kind != ProgramLine.Section.Kind.PLAN) planComplete = true;
            if (kind == ProgramLine.Section.Kind.WORKOUTS && !plan.isEmpty()) planComplete = true;
            section = kind;
        }

        private void planned(ProgramLine.Week line) {
            for (int number = line.from(); number <= line.to(); number++) {
                var week = plan.computeIfAbsent(number, PlanWeek::new);
                week.cycle = limit(line.cycle(), NAME_CHARS);
                week.name = limit(line.name(), NAME_CHARS);
                week.days = line.days();
                week.copyOf = line.copyOf() != null && line.copyOf() != number ? line.copyOf() : null;
            }
        }

        private void startWeek(ProgramLine.Week line) {
            finishWeek();
            skipping = false;
            if (line.copyOf() != null) {
                for (int number = line.from(); number <= line.to(); number++) {
                    if (number != line.copyOf()) plan.computeIfAbsent(number, PlanWeek::new).copyOf = line.copyOf();
                }
                skipping = true;
                return;
            }
            int number = line.from();
            var existing = weeks.get(number);
            if (allowed != null && !allowed.contains(number) || existing != null && !FAILED.equals(existing.status)) {
                skipping = true;
                return;
            }
            var planned = plan.computeIfAbsent(number, PlanWeek::new);
            if (planned.cycle.isEmpty()) planned.cycle = limit(line.cycle(), NAME_CHARS);
            for (int copy = number + 1; copy <= line.to(); copy++) plan.computeIfAbsent(copy, PlanWeek::new).copyOf = number;
            week = new WeekState(number);
            weeks.put(number, week);
        }

        private void finishWeek() {
            if (week == null) return;
            for (var workout : week.days) {
                for (var block : workout.blocks()) {
                    for (var exercise : block.items()) {
                        if (!exercise.sets().isEmpty()) continue;
                        var name = exercises.get(exercise.key()).resolved().resolution().originalName();
                        if (sourcePrescriptions.forExercise(name, week.number).hasValues()) {
                            warn(language.text("Пропущены подходы с назначениями из таблицы: ",
                                    "Sets with spreadsheet prescriptions are missing: ") + quote(name));
                        }
                    }
                }
            }
            week.status = DONE;
            finished.add(week.number);
            var planned = plan.get(week.number);
            if (planned != null && planned.days.size() > week.days.size()) {
                warn(language.text("Неделя ", "Week ") + week.number + language.text(": в плане тренировок ", ": planned workouts ")
                        + planned.days.size() + language.text(", разобрано ", ", parsed ") + week.days.size());
            }
            week = null;
            day = null;
            group = null;
            item = null;
        }

        private void startDay(ProgramLine.Day line) {
            if (week == null) {
                if (!skipping) warn(language.text("День вне недели пропущен: ", "Day outside a week skipped: ") + quote(line.label()));
                return;
            }
            day = new DayState(line.label(), limit(line.name(), NAME_CHARS), null, new ArrayList<>());
            week.days.add(day);
            group = null;
            item = null;
        }

        private void block(ProgramLine.Block line) {
            if (day == null) return;
            item = null;
            if (line.kind().isEmpty()) {
                group = null;
                return;
            }
            var type = BLOCKS.getOrDefault(line.kind(), new String[] {GROUP, "superset"});
            group = new BlockState(type[0], type[1], limit(line.name(), NAME_CHARS), limit(line.note(), NOTE_CHARS), new ArrayList<>());
            day.blocks().add(group);
        }

        private void item(String source) {
            if (day == null) {
                if (!skipping) warn(language.text("Упражнение вне дня пропущено: ", "Exercise outside a day skipped: ") + quote(source));
                return;
            }
            var key = keyFor(source);
            item = new ItemState(key, new ArrayList<>(), new ArrayList<>());
            var exercise = exercises.get(key);
            if (!exercise.qualifier().isEmpty()) item.comments().add(exercise.qualifier());
            if (group != null) group.items().add(item);
            else day.blocks().add(new BlockState(SINGLE, "", "", "", new ArrayList<>(List.of(item))));
        }

        /** «повт=10 / 10 / 9» — повторы каждого подхода: строка раскладывается на подходы по одному. */
        private void set(String body) {
            if (item == null) return;
            var perSet = PER_SET_REPS.matcher(body);
            if (perSet.find()) {
                var rest = COUNT_KEY.matcher(perSet.replaceFirst("")).replaceAll("").replaceAll("^\\s*\\|\\s*|\\s*\\|\\s*$", "");
                for (var reps : perSet.group(1).split("/")) {
                    single("кол=1 | повт=" + reps.strip() + (rest.isBlank() ? "" : " | " + rest));
                }
                return;
            }
            single(body);
        }

        private void single(String body) {
            var exercise = exercises.get(item.key());
            var type = exercise.resolved().resultType();
            var label = exercise.resolved().resolution().originalName();
            var skipped = new ArrayList<String>();
            var issues = new ArrayList<String>();
            var rejected = new ArrayList<String>();
            var values = SetLine.parse(body, type == null || type.isEmpty() ? null : type, Scope.IMPORT, label, skipped, issues, rejected);
            skipped.forEach(s -> warn(language.text("Значение не по формату сохранено в заметке: ", "Unformatted value kept in the note: ") + quote(s)));
            issues.forEach(s -> warn(label + ": " + s));
            if (values == null && rejected.isEmpty()) return;
            if (values == null) values = new SetLine.Values(null, null, null, false, null, null, null, null, null, null, null, null, null);
            // Значение не по формату и ключ не по типу упражнения не теряются: пользователь видит их в заметке подхода.
            var extra = new ArrayList<String>(rejected);
            issues.forEach(issue -> extra.add(issue.split(" — ")[0]));
            var note = values.note() == null ? "" : values.note();
            if (!extra.isEmpty()) note = (note.isEmpty() ? "" : note + "\n") + String.join("\n", extra);
            item.sets().add(prescription(values, limit(note, NOTE_CHARS)));
        }

        private void comment(String text) {
            if (item != null) item.comments().add(text);
        }
    }

    // ---------------------------------------------------------------- упражнения

    private void exercise(ProgramLine.ExerciseEntry line) {
        if (names.containsKey(key(line.source())) || exercises.size() >= MAX_EXERCISES) return;
        var key = KEY_PREFIX + (exercises.size() + 1);
        var resolved = resolver.resolve(key, line);
        exercises.put(key, new Exercise(resolved, ExerciseResolver.qualifier(line.source(), resolved.catalogName())));
        index(line.source(), key);
        index(line.name(), key);
    }

    /**
     * Упражнения, которые сервер не сопоставил с каталогом однозначно: строки «название | тип=…» для этапа
     * сопоставления. Точное совпадение со словарём каталога модели не отправляется.
     */
    public synchronized List<String> unresolved() {
        return exercises.values().stream().filter(e -> !ExerciseResolver.MATCHED.equals(e.resolved().resolution().status()))
                .map(ImportAssembly::line).toList();
    }

    /** Решение этапа сопоставления: строка «- источник → название | тип=…» заменяет решение по этому названию. */
    public synchronized void remap(ProgramLine.ExerciseEntry entry) {
        var key = names.get(key(entry.source()));
        if (key == null) return;
        var resolved = resolver.resolve(key, entry);
        exercises.put(key, new Exercise(resolved, ExerciseResolver.qualifier(entry.source(), resolved.catalogName())));
        index(entry.name(), key);
        changes++;
    }

    /** Упражнения программы для частей: название как в источнике и тип, по которому разбираются подходы. */
    public synchronized List<String> exerciseLines() {
        return exercises.values().stream().map(ImportAssembly::line).toList();
    }

    private static String line(Exercise exercise) {
        var name = exercise.resolved().resolution().originalName();
        var type = exercise.resolved().resultType();
        var word = type == null || type.isEmpty() ? "" : com.sizylift.backend.template.TemplateVocabulary.word(type).orElse("");
        return word.isEmpty() ? name : name + " | тип=" + word;
    }

    /** Ключ упражнения строки «Упражнение:»: по названию из источника или каталога; иначе новое из этой строки. */
    private String keyFor(String source) {
        var known = names.get(key(source));
        if (known != null) return known;
        var key = KEY_PREFIX + (exercises.size() + 1);
        var resolved = resolver.unlisted(key, source);
        exercises.put(key, new Exercise(resolved, ExerciseResolver.qualifier(source, resolved.catalogName())));
        index(source, key);
        warn(language.text("Упражнения «", "Exercise “") + quote(source)
                + language.text("» не было в списке упражнений: проверьте сопоставление.", "” was not in the exercise list: check the match."));
        return key;
    }

    private void index(String name, String key) {
        if (name != null && !name.isBlank()) names.putIfAbsent(key(name), key);
    }

    // ---------------------------------------------------------------- черновик

    public synchronized Progress progress() {
        int done = (int) planned().stream().filter(w -> DONE.equals(w.status())).count();
        return new Progress(planComplete ? STAGE_WEEKS : STAGE_PLAN, exercises.size(), done, plan.isEmpty() ? null : plan.size());
    }

    /**
     * Черновик. Частичный ({@code complete=false}) содержит все упражнения и недели, которые уже есть; итоговый —
     * только упражнения, которые используются в программе. Недели без содержимого есть только в каркасе {@code plan}.
     */
    public synchronized ImportDraftV1 snapshot(boolean complete) {
        if (question != null) {
            return new ImportDraftV1(1, true, limit(question, NOTE_CHARS), new ImportDraftV1.Draft("", "", List.of()), List.of());
        }
        var numbers = new TreeSet<Integer>(plan.keySet());
        numbers.addAll(weeks.keySet());
        var cycles = new ArrayList<ImportDraftV1.Cycle>();
        String cycleName = null;
        List<ImportDraftV1.Week> current = null;
        var used = new HashSet<String>();
        for (var number : numbers) {
            var content = content(number, number, 0);
            if (content == null || content.days.isEmpty() && !DONE.equals(content.status)) continue;
            var planned = plan.get(number);
            var cycle = planned == null ? "" : planned.cycle;
            if (current == null || !cycle.equals(cycleName)) {
                cycleName = cycle;
                current = new ArrayList<>();
                cycles.add(new ImportDraftV1.Cycle("c" + (cycles.size() + 1), cycle, current));
            }
            current.add(week(number, planned == null ? "" : planned.name, content, used));
        }
        var resolutions = exercises.values().stream().map(e -> e.resolved().resolution())
                .filter(r -> !complete || used.contains(r.key())).toList();
        return new ImportDraftV1(1, false, "", new ImportDraftV1.Draft(name, description, cycles), resolutions,
                planned(), List.copyOf(warnings), List.copyOf(rules));
    }

    private List<PlannedWeek> planned() {
        var result = new ArrayList<PlannedWeek>();
        for (var week : plan.values()) {
            var content = content(week.number, week.number, 0);
            var status = content == null ? PENDING : content.status;
            if (week.copyOf != null && content == null) {
                var source = weeks.get(week.copyOf);
                status = source != null && FAILED.equals(source.status) ? FAILED : PENDING;
            }
            result.add(new PlannedWeek(week.number, week.cycle, week.name, week.days, week.copyOf, status));
        }
        return result;
    }

    /** Содержимое недели: своё или недели, которую она повторяет. */
    private WeekState content(int number, int origin, int depth) {
        var own = weeks.get(number);
        if (own != null) return FAILED.equals(own.status) && number != origin ? null : own;
        var planned = plan.get(number);
        if (planned == null || planned.copyOf == null || depth >= MAX_COPY_DEPTH) return null;
        var source = content(planned.copyOf, origin, depth + 1);
        return source == null || !DONE.equals(source.status) ? null : source;
    }

    private ImportDraftV1.Week week(int number, String title, WeekState state, Set<String> used) {
        var weekKey = "w" + number;
        var labels = state.days.stream().map(DayState::label).toList();
        var offsets = DayPlacement.offsets(labels, startDate);
        var days = new ArrayList<ImportDraftV1.Day>();
        for (int d = 0; d < state.days.size(); d++) {
            var day = state.days.get(d);
            var dayKey = weekKey + "d" + (d + 1);
            int offset = day.offset() != null ? day.offset() : offsets.get(d);
            var blocks = new ArrayList<ImportDraftV1.Block>();
            var sourceBlocks = restoreSupersets(day.blocks());
            for (int b = 0; b < sourceBlocks.size(); b++) {
                var block = sourceBlocks.get(b);
                var blockKey = dayKey + "b" + (b + 1);
                var items = new ArrayList<ImportDraftV1.Exercise>();
                for (int i = 0; i < block.items().size(); i++) {
                    var item = block.items().get(i);
                    used.add(item.key());
                    items.add(exercise(blockKey + "x" + (i + 1), item, number));
                }
                blocks.add(new ImportDraftV1.Block(blockKey, block.type(), block.label(), block.title(), block.note(), items));
            }
            days.add(new ImportDraftV1.Day(dayKey, day.name(), (number - 1) * DayPlacement.DAYS_PER_WEEK + offset, blocks));
        }
        return new ImportDraftV1.Week(weekKey, number, title, days);
    }

    private List<BlockState> restoreSupersets(List<BlockState> source) {
        var blocks = new ArrayList<BlockState>();
        for (int i = 0; i < source.size(); i++) {
            var first = source.get(i);
            if (i + 1 < source.size() && formsSuperset(first, source.get(i + 1))) {
                var second = source.get(++i);
                blocks.add(new BlockState(GROUP, "superset", "", "", List.of(first.items().getFirst(), second.items().getFirst())));
            } else blocks.add(first);
        }
        return blocks;
    }

    private boolean formsSuperset(BlockState first, BlockState second) {
        if (!SINGLE.equals(first.type()) || !SINGLE.equals(second.type()) || first.items().size() != 1 || second.items().size() != 1)
            return false;
        var left = exercises.get(first.items().getFirst().key()).resolved().resolution().originalName();
        var right = exercises.get(second.items().getFirst().key()).resolved().resolution().originalName();
        return sourcePrescriptions.formsSuperset(left, right);
    }

    private ImportDraftV1.Exercise exercise(String key, ItemState item, int week) {
        var resolved = exercises.get(item.key()).resolved();
        var resolution = resolved.resolution();
        var targets = sourcePrescriptions.forExercise(resolution.originalName(), week);
        var allowed = TemplateVocabulary.keysFor(resolved.resultType(), Scope.IMPORT);
        boolean canSetEffort = allowed.contains("rpe");
        var sets = new ArrayList<Prescription>();
        for (int s = 0; s < item.sets().size(); s++) {
            var p = item.sets().get(s);
            var rest = p.restSeconds();
            if (targets.restSeconds() != null && (rest.isBlank() || item.sets().size() == 1))
                rest = targets.restSeconds().toString();

            var rpe = p.rpe();
            var rir = p.rir();
            if (canSetEffort && !targets.explicitRpe() && !"warmup".equals(p.setType())) {
                if (targets.rir() != null) {
                    rir = targets.rir();
                    rpe = plain(SetLine.rpeFromRir(rir));
                } else if (rpe.isBlank()) {
                    if (rir.isBlank() && "working".equals(p.setType()) && !p.repetitions().isBlank()
                            && targets.defaultRir() != null) rir = targets.defaultRir();
                    rpe = plain(SetLine.rpeFromRir(rir));
                }
            }
            var percent = p.percentOneRm();
            if (percent.isBlank() && allowed.contains("процент") && targets.percentOneRm() != null && "working".equals(p.setType()))
                percent = targets.percentOneRm();
            sets.add(new Prescription(key + "s" + (s + 1), p.count(), p.repetitions(), p.weightKg(), percent,
                    p.durationSeconds(), p.distanceMeters(), rpe, rir, rest, p.setType(), p.note(), resolution.sourceRefs()));
        }
        return new ImportDraftV1.Exercise(key, item.key(), limit(String.join("; ", item.comments()), NOTE_CHARS),
                resolution.sourceRefs(), sets);
    }

    private static Prescription prescription(SetLine.Values v, String note) {
        String reps = v.maxReps() ? "AMRAP" : v.reps() == null ? "" : v.repsMax() == null ? v.reps().toString() : v.reps() + "-" + v.repsMax();
        return new Prescription("", v.count() == null ? "" : v.count().toString(), reps, plain(v.weightKg()), plain(v.percent()),
                v.seconds() == null ? "" : v.seconds().toString(), v.meters() == null ? "" : v.meters().toString(),
                plain(v.rpe()), v.rir() == null ? "" : v.rir(), v.restSeconds() == null ? "" : v.restSeconds().toString(),
                v.type() == null ? "working" : v.type(), note, List.of());
    }

    private static String plain(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private void warn(String text) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(text);
    }

    private static String quote(String text) {
        var single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= MAX_QUOTE ? single : single.substring(0, MAX_QUOTE - 1) + "…";
    }

    private static String limit(String text, int max) {
        var value = text == null ? "" : text.strip();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ");
    }
}
