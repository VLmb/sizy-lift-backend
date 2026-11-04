package com.sizylift.backend.imports.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.model.ModelFailure;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.repository.ImportRepository;
import com.sizylift.backend.imports.source.ImportSourceReader;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.web.service.SheetImportService;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public class ImportWorker {
    private static final long PROMPT_OVERHEAD_BYTES = 32_768;
    private static final long MAX_REQUEST_BYTES = 900_000;
    private static final long MILLION = 1_000_000;
    private static final String TOO_LARGE = "Для одного разбора программа слишком большая. Оставьте нужные недели или разделите источник на части.";
    private final ImportRepository sessions;
    private final ImportProperties limits;
    private final ImportSourceReader sourceReader;
    private final ProgramParserModel model;
    private final ImportDraftValidator validator;
    private final ProgramDraftCompiler compiler;
    private final AccessPolicy access;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectMapper json;
    private final ImportMetrics metrics;
    private final Semaphore capacity;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ImportWorker(ImportRepository sessions, ImportProperties limits, ImportSourceReader sourceReader,
            ProgramParserModel model, ImportDraftValidator validator, ProgramDraftCompiler compiler,
            AccessPolicy access, TransactionTemplate transactions, Clock clock, ObjectMapper json, ImportMetrics metrics) {
        this.sessions = sessions; this.limits = limits; this.sourceReader = sourceReader; this.model = model;
        this.validator = validator; this.compiler = compiler; this.access = access; this.transactions = transactions;
        this.clock = clock; this.json = json; this.metrics = metrics; this.capacity = new Semaphore(limits.concurrentRequests());
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
            var source = session.normalized() == null ? sourceReader.read(session) : session.normalized();
            if (session.normalized() == null && !sessions.normalized(session, source, clock.instant())) return;
            var catalog = ProgramParserModel.CatalogEntry.of(session.catalog());
            var input = new ProgramParserModel.Input(source, catalog, session.startDate(), session.history(),
                    session.attempts() == 0 || session.repairHint() == null ? "" : session.repairHint());
            long bytes = json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8).length + PROMPT_OVERHEAD_BYTES;
            if (bytes > MAX_REQUEST_BYTES) {
                fail(session, "input_too_large", "Для одного запроса источник слишком большой. Сократите его или выберите нужные листы.");
                return;
            }
            // Верхняя оценка: один токен на байт и пиковый тариф, включая неопределённый исход сетевых сбоев.
            long micros = Math.addExact(Math.multiplyExact(bytes, limits.inputMicrosPerMillion()),
                    Math.multiplyExact((long) limits.maxOutputTokens(), limits.outputMicrosPerMillion()));
            micros = Math.addExact(micros, MILLION - 1) / MILLION;
            if (!sessions.reserve(session, micros, clock.instant(), limits)) {
                fail(session, "import_budget", "Достигнут лимит запросов или стоимости обработки.");
                return;
            }
            modelRequested = true;
            var result = model.parse(input);
            if (result.inputTokens() < 0 || result.outputTokens() < 0 || result.cachedTokens() < 0
                    || result.cachedTokens() > result.inputTokens()) throw new ModelFailure(true, Duration.ZERO);
            sessions.usage(session, result.inputTokens(), result.outputTokens(), result.cachedTokens(), clock.instant());
            metrics.usage(result.inputTokens(), result.outputTokens(), result.cachedTokens());
            var draft = validator.parse(result.json(), source, catalog, session.history());
            if (draft.needsClarification() && session.round() >= limits.clarificationRounds()) {
                sessions.finish(session, "failed", draft, Map.of(), "clarification_limit",
                        "Достигнут лимит уточнений. Исправьте исходный текст и начните новый импорт.", 0, 0, 0, clock.instant());
                metrics.outcome(session.sourceKind(), "clarification_limit", System.nanoTime() - started);
                return;
            }
            var decisions = draft.needsClarification() ? Map.<String, com.sizylift.backend.imports.dto.ImportDtos.Decision>of()
                    : compiler.initialDecisions(session.id(), draft);
            if (!draft.needsClarification() && decisions.size() == draft.exerciseResolutions().size())
                compiler.compile(session.withResult(draft, decisions), clock.instant());
            var outcome = draft.needsClarification() ? "clarification_required" : "review";
            sessions.finish(session, outcome, draft, decisions, null, null, 0, 0, 0, clock.instant());
            metrics.outcome(session.sourceKind(), outcome, System.nanoTime() - started);
        } catch (ImportDraftValidator.InvalidDraft e) {
            retryOrFail(session, true, Duration.ZERO, e.repairHint());
        } catch (ModelFailure e) {
            if (e.truncated()) {
                // Повтор того же запроса снова упрётся в лимит вывода: сразу сообщаем, как уменьшить источник.
                sessions.usage(session, e.inputTokens(), e.outputTokens(), 0, clock.instant());
                metrics.usage(e.inputTokens(), e.outputTokens(), 0);
                fail(session, "input_too_large", TOO_LARGE);
            } else retryOrFail(session, e.retryable(), e.retryAfter(), null);
        } catch (ImportFailure e) {
            if (modelRequested) retryOrFail(session, true, Duration.ZERO,
                    "Предыдущий ответ отклонён сервером: " + e.getMessage() + " Проверь типы упражнений и значения подходов.");
            else fail(session, "invalid_source", e.getMessage());
        } catch (SheetImportService.InvalidSheet | SheetImportService.UnavailableSheet e) {
            fail(session, "sheet_unavailable", "Таблица недоступна. Проверьте ссылку или загрузите Excel.");
        } catch (RuntimeException e) {
            fail(session, "processing_failed", "Не удалось обработать источник. Проверьте файл и попробуйте снова.");
        }
    }

    /** repairHint == null — сбой транспорта: следующая попытка повторяет тот же запрос без подсказки. */
    private void retryOrFail(ImportSession session, boolean retryable, Duration requested, String repairHint) {
        if (retryable && session.attempts() + 1 < limits.attemptsPerRound()) {
            // Невалидный ответ не связан с перегрузкой провайдера: повторяем сразу, без паузы.
            long delay = repairHint != null ? 0 : Math.max(1 + ThreadLocalRandom.current().nextLong(3), requested.toSeconds());
            sessions.retry(session, clock.instant().plusSeconds(delay), clock.instant(), repairHint);
        } else fail(session, "model_unavailable", "Не удалось получить корректный разбор. Повторите импорт позже.");
    }
    private void fail(ImportSession session, String code, String message) {
        sessions.finish(session, "failed", session.output(), session.decisions(), code, message, 0, 0, 0, clock.instant());
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
