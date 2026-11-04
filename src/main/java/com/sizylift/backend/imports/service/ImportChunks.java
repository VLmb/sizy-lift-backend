package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.model.ModelFailure;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.model.ProgramParserModel.Task;
import com.sizylift.backend.imports.repository.ImportRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Второй этап разбора большой программы: недели плана делятся на части, части разбираются моделью
 * параллельно и сводятся в один черновик. Время ожидания — примерно время самой долгой части,
 * а объём программы не ограничен выводом одного ответа.
 */
@Component
public class ImportChunks {
    /** Ожидание перед повтором при 429/5xx: части выполняются в виртуальных потоках внутри одной аренды. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(20);
    private static final long MIN_BACKOFF_MILLIS = 500;
    private static final long JITTER_MILLIS = 1500;
    private static final int HALVES = 2;
    private final ProgramParserModel model;
    private final ImportDraftValidator validator;
    private final ImportRepository sessions;
    private final ImportProperties limits;
    private final ImportCost cost;
    private final ImportMetrics metrics;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ImportChunks(ProgramParserModel model, ImportDraftValidator validator,
                        ImportRepository sessions, ImportProperties limits,
                        ImportCost cost, ImportMetrics metrics, Clock clock) {
        this.model = model; this.validator = validator; this.sessions = sessions; this.limits = limits;
        this.cost = cost; this.metrics = metrics; this.clock = clock;
    }

    /** Части подряд идущих недель примерно по workoutsPerChunk тренировок; частей не больше maxChunks. */
    public static List<List<Integer>> split(ImportPlan plan, int workoutsPerChunk, int maxChunks) {
        int total = plan.weeks().stream().mapToInt(ImportChunks::weight).sum();
        int target = Math.max(workoutsPerChunk, Math.ceilDiv(total, maxChunks));
        while (true) {
            var result = new ArrayList<List<Integer>>();
            var current = new ArrayList<Integer>();
            int size = 0;
            for (var week : plan.weeks()) {
                if (!current.isEmpty() && size + weight(week) > target) {
                    result.add(List.copyOf(current));
                    current.clear();
                    size = 0;
                }
                current.add(week.week());
                size += weight(week);
            }
            if (!current.isEmpty()) result.add(List.copyOf(current));
            if (result.size() <= maxChunks) return result;
            target++;
        }
    }

    /** Неделя без тренировок всё равно разбирается: в ней могут быть заметки и отдых. */
    private static int weight(ImportPlan.Week week) { return Math.max(1, week.workouts()); }

    public ImportDraftV1 run(ImportSession session, ImportPlan plan, ProgramParserModel.Input base) {
        var run = new Run(session, plan, base);
        var futures = split(plan, limits.workoutsPerChunk(), limits.maxChunks()).stream()
                .map(weeks -> executor.submit(() -> run.chunk(weeks))).toList();
        return validator.assemble(plan, run.collect(futures));
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }

    /** Сбой разбора частей: code и message — для пользователя, cancelled — аренда потеряна, писать нечего. */
    public static class ChunkFailure extends RuntimeException {
        private final String code;
        private final boolean cancelled;
        ChunkFailure(String code, String message, boolean cancelled) {
            super(message);
            this.code = code;
            this.cancelled = cancelled;
        }
        public String code() { return code; }
        public boolean cancelled() { return cancelled; }
        static ChunkFailure tooLarge() {
            return new ChunkFailure("input_too_large", "Неделя программы слишком большая для разбора. Разделите источник на части.", false);
        }
        static ChunkFailure unavailable() {
            return new ChunkFailure("model_unavailable", "Не удалось получить корректный разбор. Повторите импорт позже.", false);
        }
        static ChunkFailure budget() {
            return new ChunkFailure("import_budget", "Достигнут лимит запросов или стоимости обработки.", false);
        }
        static ChunkFailure lost() { return new ChunkFailure("lease_lost", "Сессия отменена или передана другому worker.", true); }
    }

    private final class Run {
        private final ImportSession session;
        private final ImportPlan plan;
        private final ProgramParserModel.Input base;
        private final Semaphore permits = new Semaphore(limits.chunkParallelism());
        /** Дробление обрезанных частей ограничено, чтобы сбой модели не превратился в лавину платных вызовов. */
        private final AtomicInteger calls = new AtomicInteger();
        private final int maxCalls = limits.maxChunks() * limits.attemptsPerRound() * HALVES;

        Run(ImportSession session, ImportPlan plan, ProgramParserModel.Input base) {
            this.session = session; this.plan = plan; this.base = base;
        }

        List<ImportDraftValidator.Chunk> chunk(List<Integer> weeks) {
            var hint = "";
            for (int attempt = 0; attempt < limits.attemptsPerRound(); attempt++) {
                var input = new ProgramParserModel.Input(base.source(), base.catalog(), base.startDate(), base.history(),
                        Task.weeks(plan, weeks), hint);
                try {
                    return List.of(validator.weeks(call(input).json(), plan, weeks, base.source(), base.catalog(), base.history()));
                } catch (ImportDraftValidator.InvalidDraft e) {
                    hint = e.repairHint();
                } catch (ModelFailure e) {
                    if (e.truncated()) return halves(weeks);
                    if (!e.retryable()) throw ChunkFailure.unavailable();
                    pause(e.retryAfter());
                    hint = "";
                }
            }
            throw ChunkFailure.unavailable();
        }

        /** Обрезанная часть делится пополам: тот же запрос снова упёрся бы в лимит вывода. */
        private List<ImportDraftValidator.Chunk> halves(List<Integer> weeks) {
            if (weeks.size() < HALVES) throw ChunkFailure.tooLarge();
            int middle = weeks.size() / HALVES;
            var first = executor.submit(() -> chunk(weeks.subList(0, middle)));
            var second = executor.submit(() -> chunk(weeks.subList(middle, weeks.size())));
            return collect(List.of(first, second));
        }

        private ProgramParserModel.Result call(ProgramParserModel.Input input) {
            if (calls.incrementAndGet() > maxCalls) throw ChunkFailure.unavailable();
            if (cost.tooLarge(input)) throw ChunkFailure.tooLarge();
            permits.acquireUninterruptibly();
            ProgramParserModel.Result result;
            try {
                // Аренда продлевается уже после ожидания слота: вызов всегда укладывается в одну аренду.
                if (!sessions.extend(session, clock.instant(), limits)) throw ChunkFailure.lost();
                if (!sessions.reserveChunk(session, cost.micros(input), clock.instant(), limits)) throw ChunkFailure.budget();
                result = model.parse(input);
            } catch (ModelFailure e) {
                if (e.truncated()) record(e.inputTokens(), e.outputTokens(), 0);
                throw e;
            } finally {
                permits.release();
            }
            if (result.inputTokens() < 0 || result.outputTokens() < 0 || result.cachedTokens() < 0
                    || result.cachedTokens() > result.inputTokens()) throw new ModelFailure(true, Duration.ZERO);
            record(result.inputTokens(), result.outputTokens(), result.cachedTokens());
            return result;
        }

        private void record(int input, int output, int cached) {
            sessions.usage(session, input, output, cached, clock.instant());
            metrics.usage(input, output, cached);
        }

        /** Первая ошибка отменяет остальные части: результат без них всё равно не собрать. */
        List<ImportDraftValidator.Chunk> collect(List<Future<List<ImportDraftValidator.Chunk>>> futures) {
            var result = new ArrayList<ImportDraftValidator.Chunk>();
            try {
                for (var future : futures) result.addAll(future.get());
                return result;
            } catch (ExecutionException e) {
                futures.forEach(future -> future.cancel(true));
                if (e.getCause() instanceof RuntimeException failure) throw failure;
                throw ChunkFailure.unavailable();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(future -> future.cancel(true));
                throw ChunkFailure.lost();
            }
        }

        private void pause(Duration requested) {
            long millis = Math.min(MAX_BACKOFF.toMillis(), Math.max(MIN_BACKOFF_MILLIS, requested.toMillis()))
                    + ThreadLocalRandom.current().nextLong(JITTER_MILLIS);
            try { Thread.sleep(millis); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ChunkFailure.lost(); }
        }
    }
}
