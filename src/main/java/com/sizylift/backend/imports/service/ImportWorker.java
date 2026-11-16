package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportDtos.Decision;
import com.sizylift.backend.imports.entity.ImportLanguage;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.imports.model.ImportPrompt;
import com.sizylift.backend.imports.model.ModelFailure;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.model.ProgramParserModel.Task;
import com.sizylift.backend.imports.repository.ImportRepository;
import com.sizylift.backend.imports.source.ImportSourceReader;
import com.sizylift.backend.imports.source.SourceSheets;
import com.sizylift.backend.imports.template.ExerciseResolver;
import com.sizylift.backend.imports.template.ImportAssembly;
import com.sizylift.backend.imports.template.ProgramLine;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.web.service.SheetImportService;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Разбор сессии (docs/imports.md, «Шаблон»): первый вызов модели пишет каркас программы — упражнения и недели —
 * и, если программа небольшая, сразу её тренировки; недели, которых нет в ответе, разбираются параллельными частями.
 * Всё время разбора частичный черновик пишется в сессию, и пользователь видит, как программа появляется.
 */
@Component
public class ImportWorker {
    private static final String NO_PLAN = """
            Предыдущий ответ не по шаблону: нет раздела «Упражнения» или «План». Ответь строками: «Программа», «Правило», \
            «Упражнения», «План» со всеми неделями — или одной строкой «Вопрос:».""";
    private final ImportRepository sessions;
    private final ImportProperties limits;
    private final ImportSourceReader sourceReader;
    private final ProgramParserModel model;
    private final ProgramDraftCompiler compiler;
    private final AccessPolicy access;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ImportMetrics metrics;
    private final ImportCost cost;
    private final ImportChunks chunks;
    private final ImportProgress progress;
    private final ImportCatalogs catalogs;
    private final ImportEvents events;
    private final Semaphore capacity;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ImportWorker(ImportRepository sessions, ImportProperties limits, ImportSourceReader sourceReader,
            ProgramParserModel model, ProgramDraftCompiler compiler, AccessPolicy access, TransactionTemplate transactions,
            Clock clock, ImportMetrics metrics, ImportCost cost, ImportChunks chunks, ImportProgress progress,
            ImportCatalogs catalogs, ImportEvents events) {
        this.sessions = sessions; this.limits = limits; this.sourceReader = sourceReader; this.model = model;
        this.compiler = compiler; this.access = access; this.transactions = transactions; this.clock = clock;
        this.metrics = metrics; this.cost = cost; this.chunks = chunks; this.progress = progress; this.catalogs = catalogs;
        this.events = events;
        this.capacity = new Semaphore(limits.concurrentRequests());
    }

    @Scheduled(fixedDelayString = "${sizylift.imports.poll-interval:1s}")
    public void tick() {
        if (!limits.enabled() || !limits.workerEnabled()) return;
        while (capacity.tryAcquire()) {
            boolean submitted = false;
            try {
                var next = transactions.execute(status -> sessions.claim(clock.instant(), limits));
                if (next == null || next.isEmpty()) return;
                executor.submit(() -> {
                    try { process(next.get()); }
                    finally { capacity.release(); }
                });
                submitted = true;
            } finally {
                if (!submitted) capacity.release();
            }
        }
    }

    /** Тот же путь исполнения для интеграционных тестов и ручного обслуживания очереди. */
    public boolean processOne() {
        if (!limits.enabled()) return false;
        var next = transactions.execute(status -> sessions.claim(clock.instant(), limits));
        if (next == null || next.isEmpty()) return false;
        process(next.get());
        return true;
    }

    private void process(ImportSession session) {
        long started = System.nanoTime();
        boolean modelRequested = false;
        try {
            if (!access.canWriteFor(session.userId(), session.ownerId(), AggregateType.PROGRAM)) {
                fail(session, "access_revoked", "Доступ к программе подопечного отозван.");
                return;
            }
            var source = session.normalized();
            if (source == null) {
                var parsed = sourceReader.read(session);
                source = new Source(parsed.items(), sessions.language(session.id()));
            }
            if (!session.sheetsSelected() && SourceSheets.needsSelection(source)) {
                // Несколько листов: модель не вызывается, пока пользователь не выберет нужные.
                if (sessions.awaitSheets(session, source, clock.instant())) {
                    events.changed(session.id());
                    metrics.outcome(session.sourceKind(), "sheet_selection", System.nanoTime() - started);
                }
                return;
            }
            sourceReader.checkSize(source);
            if (session.normalized() == null && !sessions.normalized(session, source, clock.instant())) return;
            var catalog = catalogs.of(session);
            var base = new ProgramParserModel.Input(source, session.startDate(), session.history(), Task.understand(),
                    session.attempts() == 0 || session.repairHint() == null ? "" : session.repairHint());
            if (cost.tooLarge(request(ImportPrompt.understand(), base))) {
                fail(session, "input_too_large", "Источник слишком большой для разбора. Сократите его или выберите нужные листы.");
                return;
            }
            modelRequested = true;
            var assembly = new ImportAssembly(new ExerciseResolver(catalog, source.items()), session.startDate(),
                    ImportLanguage.of(source.language()));
            // Каркас уже есть — перезапуск worker или повторный разбор недель: готовые недели не разбираются заново.
            if (session.outline() != null) assembly.restore(session.outline(), session.output());
            var publisher = progress.start(session, assembly);
            if (session.outline() == null) {
                understand(session, base, assembly, publisher);
                if (assembly.question() == null) {
                    if (!assembly.hasPlan()) {
                        retryOrFail(session, true, Duration.ZERO, NO_PLAN);
                        return;
                    }
                    match(session, ImportPrompt.match(catalog.prompt()), base, assembly, publisher);
                    if (!sessions.outline(session, assembly.outline(), clock.instant())) return;
                }
            }
            if (assembly.question() == null) {
                var pending = assembly.pendingWeeks();
                if (!pending.isEmpty()) chunks.run(session, ImportPrompt.weeks(), base, assembly, publisher, pending);
            }
            finish(session, assembly.snapshot(true), started);
        } catch (Budget e) {
            fail(session, "import_budget", "Достигнут лимит запросов или стоимости обработки.");
        } catch (TooLarge e) {
            fail(session, "input_too_large", "Программа слишком большая для разбора. Оставьте нужные недели или разделите источник на части.");
        } catch (ImportChunks.Lost e) {
            // Сессию отменили или забрали: писать нечего.
        } catch (ModelFailure e) {
            retryOrFail(session, e.retryable(), e.retryAfter(), null);
        } catch (ImportFailure e) {
            if (modelRequested) fail(session, "model_unavailable", "Не удалось получить корректный разбор. Повторите импорт позже.");
            else fail(session, "invalid_source", e.getMessage());
        } catch (SheetImportService.InvalidSheet | SheetImportService.UnavailableSheet e) {
            fail(session, "sheet_unavailable", "Таблица недоступна. Проверьте ссылку или загрузите Excel.");
        } catch (RuntimeException e) {
            fail(session, "processing_failed", "Не удалось обработать источник. Проверьте файл и попробуйте снова.");
        }
    }

    /**
     * Этап 1 — понимание источника (с рассуждениями, если они включены): правила чтения, упражнения как в источнике
     * и план недель — или вопрос. Тренировки на этом этапе не пишутся; если модель начала их писать, ответ
     * останавливается — недели распишут части по правилам.
     */
    private void understand(ImportSession session, ProgramParserModel.Input input, ImportAssembly assembly,
                            ImportProgress.Publisher publisher) {
        var lines = publisher.lines(assembly.cursor(null), assembly::planComplete);
        var result = call(session, request(ImportPrompt.understand(), input), lines, true);
        lines.finish(result);
        if (result.truncated() && !assembly.planComplete() && assembly.question() == null) throw new TooLarge();
    }

    /**
     * Этап 2 — сопоставление с каталогом. Сначала словарь сервера (точные названия, варианты, личные синонимы); что
     * не нашлось однозначно — одним коротким вызовом без источника и рассуждений. Сбой этого вызова не останавливает
     * разбор: несопоставленное пользователь выберет сам.
     */
    private void match(ImportSession session, String system, ProgramParserModel.Input base, ImportAssembly assembly,
                       ImportProgress.Publisher publisher) {
        var unresolved = assembly.unresolved();
        if (unresolved.isEmpty()) return;
        var input = new ProgramParserModel.Input(base.source(), base.startDate(), base.history(), Task.match(unresolved), "");
        ProgramParserModel.Result result;
        try {
            result = call(session, request(system, input), ProgramParserModel.Listener.NONE, false);
        } catch (ModelFailure | Budget e) {
            return;
        }
        for (var line : result.text().split("\\R")) {
            if (ProgramLine.parse(line) instanceof ProgramLine.ExerciseEntry entry) assembly.remap(entry);
        }
        publisher.publish(true);
    }

    /** Даже каркас программы не поместился в лимит вывода: повтор с тем же входом обрежется снова. */
    private static class TooLarge extends RuntimeException {
        TooLarge() { super("Каркас программы не поместился в ответ модели"); }
    }

    private static ProgramParserModel.Request request(String system, ProgramParserModel.Input input) {
        return new ProgramParserModel.Request(system, input);
    }

    /**
     * Вызов с резервом бюджета. Первый вызов раунда расходует попытку раунда, повтор каркаса после обрезки — нет.
     * Аренда продлевается перед каждым вызовом.
     */
    private ProgramParserModel.Result call(ImportSession session, ProgramParserModel.Request request,
                                           ProgramParserModel.Listener listener, boolean attempt) {
        if (!sessions.extend(session, clock.instant(), limits)) throw new ImportChunks.Lost();
        var reserved = attempt ? sessions.reserve(session, cost.micros(request), clock.instant(), limits)
                : sessions.reserveChunk(session, cost.micros(request), clock.instant(), limits);
        if (!reserved) throw new Budget();
        ProgramParserModel.Result result;
        try {
            result = model.parse(request, listener);
        } catch (ProgramParserModel.Stop e) {
            // Слушатель остановил ответ, а модель не обработала остановку сама: строки уже прочитаны.
            result = new ProgramParserModel.Result("", 0, 0, 0, true);
        }
        if (result.inputTokens() < 0 || result.outputTokens() < 0 || result.cachedTokens() < 0
                || result.cachedTokens() > result.inputTokens()) throw new ModelFailure(true, Duration.ZERO);
        sessions.usage(session, result.inputTokens(), result.outputTokens(), result.cachedTokens(), clock.instant());
        metrics.usage(result.inputTokens(), result.outputTokens(), result.cachedTokens());
        return result;
    }

    private void finish(ImportSession session, ImportDraftV1 draft, long started) {
        if (draft.needsClarification() && session.round() >= limits.clarificationRounds()) {
            sessions.finish(session, "failed", draft, Map.of(), "clarification_limit",
                    "Достигнут лимит уточнений. Исправьте исходный текст и начните новый импорт.", 0, 0, 0, clock.instant());
            metrics.outcome(session.sourceKind(), "clarification_limit", System.nanoTime() - started);
            return;
        }
        if (!draft.needsClarification() && draft.programDraft().cycles().isEmpty()) {
            sessions.finish(session, "failed", draft, Map.of(), "model_unavailable",
                    "Не удалось разобрать ни одной недели. Повторите импорт позже.", 0, 0, 0, clock.instant());
            metrics.outcome(session.sourceKind(), "model_unavailable", System.nanoTime() - started);
            return;
        }
        var decisions = draft.needsClarification() ? Map.<String, Decision>of() : decisions(session, draft);
        if (!draft.needsClarification() && decisions.size() == draft.exerciseResolutions().size())
            compiler.compile(session.withResult(draft, decisions), clock.instant());
        var outcome = draft.needsClarification() ? "clarification_required" : "review";
        sessions.finish(session, outcome, draft, decisions, null, null, 0, 0, 0, clock.instant());
        events.changed(session.id());
        metrics.outcome(session.sourceKind(), outcome, System.nanoTime() - started);
    }

    /** Выбор пользователя при повторном разборе недель сохраняется; новые упражнения получают решение по умолчанию. */
    private Map<String, Decision> decisions(ImportSession session, ImportDraftV1 draft) {
        var result = new LinkedHashMap<>(compiler.initialDecisions(session.id(), draft));
        var keys = draft.exerciseResolutions().stream().map(ImportDraftV1.Resolution::key).toList();
        session.decisions().forEach((key, decision) -> { if (keys.contains(key)) result.put(key, decision); });
        return result;
    }

    /** Резерв бюджета или попыток раунда исчерпан. */
    private static class Budget extends RuntimeException {
        Budget() { super("Достигнут лимит запросов или стоимости обработки"); }
    }

    /** repairHint == null — сбой транспорта: следующая попытка повторяет тот же запрос без подсказки. */
    private void retryOrFail(ImportSession session, boolean retryable, Duration requested, String repairHint) {
        if (retryable && session.attempts() + 1 < limits.attemptsPerRound()) {
            // Ответ не по шаблону не связан с перегрузкой провайдера: повторяем сразу, без паузы.
            long delay = repairHint != null ? 0 : Math.max(1 + ThreadLocalRandom.current().nextLong(3), requested.toSeconds());
            sessions.retry(session, clock.instant().plusSeconds(delay), clock.instant(), repairHint);
        } else fail(session, "model_unavailable", "Не удалось получить корректный разбор. Повторите импорт позже.");
    }

    private void fail(ImportSession session, String code, String message) {
        sessions.finish(session, "failed", session.output(), session.decisions(), code, message, 0, 0, 0, clock.instant());
        events.changed(session.id());
        metrics.outcome(session.sourceKind(), code, 0);
    }

    /** Новое задание начинает обрабатываться сразу после commit транзакции, не дожидаясь следующего tick. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onQueued(ImportQueued event) {
        if (!limits.enabled() || !limits.workerEnabled()) return;
        try { executor.submit(this::tick); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* приложение останавливается */ }
    }

    @Scheduled(fixedDelayString = "${sizylift.imports.cleanup-interval:1m}", initialDelayString = "1m")
    public void cleanup() { sessions.cleanup(clock.instant()); }
    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
