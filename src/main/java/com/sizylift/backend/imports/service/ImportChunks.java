package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.model.ModelFailure;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.model.ProgramParserModel.Task;
import com.sizylift.backend.imports.repository.ImportRepository;
import com.sizylift.backend.imports.template.ImportAssembly;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Второй этап разбора большой программы: недели каркаса делятся на части, части разбираются моделью параллельно
 * и потоком пишут свои недели в общую сборку — пользователь видит, как заполняются сразу несколько недель.
 * Время ожидания — примерно время самой долгой части. Сбой части не отменяет остальные: её недели повторяются,
 * а если не вышло — помечаются неразобранными, остальная программа сохраняется.
 */
@Component
public class ImportChunks {
    /** Ожидание перед повтором при 429/5xx: части выполняются в виртуальных потоках внутри одной аренды. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(20);
    private static final long MIN_BACKOFF_MILLIS = 500;
    private static final long JITTER_MILLIS = 1500;
    private static final int HALVES = 2;
    private final ProgramParserModel model;
    private final ImportRepository sessions;
    private final ImportProperties limits;
    private final ImportCost cost;
    private final ImportMetrics metrics;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ImportChunks(ProgramParserModel model, ImportRepository sessions, ImportProperties limits,
                        ImportCost cost, ImportMetrics metrics, Clock clock) {
        this.model = model; this.sessions = sessions; this.limits = limits;
        this.cost = cost; this.metrics = metrics; this.clock = clock;
    }

    /** Части подряд идущих недель примерно по workoutsPerChunk тренировок; частей не больше maxChunks. */
    public static List<List<Integer>> split(List<Integer> weeks, ToIntFunction<Integer> weight, int workoutsPerChunk, int maxChunks) {
        int total = weeks.stream().mapToInt(weight).sum();
        int target = Math.max(workoutsPerChunk, Math.ceilDiv(total, maxChunks));
        while (true) {
            var result = new ArrayList<List<Integer>>();
            var current = new ArrayList<Integer>();
            int size = 0;
            for (var week : weeks) {
                if (!current.isEmpty() && size + weight.applyAsInt(week) > target) {
                    result.add(List.copyOf(current));
                    current.clear();
                    size = 0;
                }
                current.add(week);
                size += weight.applyAsInt(week);
            }
            if (!current.isEmpty()) result.add(List.copyOf(current));
            if (result.size() <= maxChunks) return result;
            target++;
        }
    }

    /** Разбирает недели weeks; неразобранные помечаются в сборке. Бросает только {@link Lost}. */
    public void run(ImportSession session, String system, ProgramParserModel.Input base, ImportAssembly assembly,
                    ImportProgress.Publisher publisher, List<Integer> weeks) {
        var run = new Run(session, system, base, assembly, publisher);
        var futures = split(weeks, assembly::workouts, limits.workoutsPerChunk(), limits.maxChunks()).stream()
                .map(part -> executor.submit(() -> run.chunk(part))).toList();
        run.await(futures);
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }

    /** Аренда потеряна: сессию отменили или забрали, писать нечего. */
    public static class Lost extends RuntimeException {
        Lost() { super("Сессия отменена или передана другому worker"); }
    }

    private final class Run {
        private final ImportSession session;
        private final String system;
        private final ProgramParserModel.Input base;
        private final ImportAssembly assembly;
        private final ImportProgress.Publisher publisher;
        private final Semaphore permits = new Semaphore(limits.chunkParallelism());
        /** Повторы и продолжения ограничены, чтобы сбой модели не превратился в лавину платных вызовов. */
        private final AtomicInteger calls = new AtomicInteger();
        private final int maxCalls = limits.maxChunks() * limits.attemptsPerRound() * HALVES * HALVES;

        Run(ImportSession session, String system, ProgramParserModel.Input base, ImportAssembly assembly,
            ImportProgress.Publisher publisher) {
            this.session = session; this.system = system; this.base = base; this.assembly = assembly; this.publisher = publisher;
        }

        /**
         * Одна часть. Обрезанный ответ продолжается с первой неразобранной недели и попыткой не считается; если не
         * готово ни одной недели, часть делится пополам. Ответ без части недель повторяется с подсказкой.
         */
        Void chunk(List<Integer> weeks) {
            var remaining = new ArrayList<>(weeks);
            var hint = "";
            int attempts = 0;
            while (!remaining.isEmpty() && attempts < limits.attemptsPerRound()) {
                var input = new ProgramParserModel.Input(base.source(), base.startDate(), base.history(),
                        Task.weeks(assembly.outline(), remaining, assembly.exerciseLines()), hint);
                var cursor = assembly.cursor(Set.copyOf(remaining));
                var lines = publisher.lines(cursor);
                ProgramParserModel.Result result;
                try {
                    result = call(new ProgramParserModel.Request(system, input), lines);
                } catch (ProgramParserModel.Stop e) {
                    result = new ProgramParserModel.Result("", 0, 0, 0, true);
                } catch (ModelFailure e) {
                    lines.end(true);
                    if (!e.retryable()) break;
                    attempts++;
                    pause(e.retryAfter());
                    hint = "";
                    continue;
                } catch (Budget e) {
                    lines.end(true);
                    break;
                } catch (RuntimeException e) {
                    lines.end(true);
                    throw e;
                }
                var done = lines.finish(result);
                remaining.removeAll(done);
                if (remaining.isEmpty()) return null;
                if (result.truncated()) {
                    if (done.isEmpty()) return halves(remaining);
                    continue;
                }
                attempts++;
                hint = "В ответе нет недель " + String.join(", ", remaining.stream().map(String::valueOf).toList())
                        + ". Распиши их подробно, начиная со строки «Тренировки:».";
            }
            assembly.fail(remaining);
            return null;
        }

        /** Обрезанная часть без единой готовой недели делится пополам: тот же запрос снова упёрся бы в лимит вывода. */
        private Void halves(List<Integer> weeks) {
            if (weeks.size() < HALVES) {
                assembly.fail(weeks);
                return null;
            }
            int middle = weeks.size() / HALVES;
            var first = List.copyOf(weeks.subList(0, middle));
            var second = List.copyOf(weeks.subList(middle, weeks.size()));
            await(List.of(executor.submit(() -> chunk(first)), executor.submit(() -> chunk(second))));
            return null;
        }

        private ProgramParserModel.Result call(ProgramParserModel.Request request, ProgramParserModel.Listener listener) {
            if (calls.incrementAndGet() > maxCalls || cost.tooLarge(request)) throw new Budget();
            permits.acquireUninterruptibly();
            ProgramParserModel.Result result;
            try {
                // Аренда продлевается уже после ожидания слота: вызов всегда укладывается в одну аренду.
                if (!sessions.extend(session, clock.instant(), limits)) throw new Lost();
                if (!sessions.reserveChunk(session, cost.micros(request), clock.instant(), limits)) throw new Budget();
                result = model.parse(request, listener);
            } finally {
                permits.release();
            }
            if (result.inputTokens() < 0 || result.outputTokens() < 0 || result.cachedTokens() < 0
                    || result.cachedTokens() > result.inputTokens()) throw new ModelFailure(true, Duration.ZERO);
            sessions.usage(session, result.inputTokens(), result.outputTokens(), result.cachedTokens(), clock.instant());
            metrics.usage(result.inputTokens(), result.outputTokens(), result.cachedTokens());
            return result;
        }

        /** Потеря аренды останавливает все части; прочие сбои части уже учтены в сборке. */
        void await(List<Future<Void>> futures) {
            try {
                for (var future : futures) future.get();
            } catch (ExecutionException e) {
                futures.forEach(future -> future.cancel(true));
                if (e.getCause() instanceof RuntimeException failure) throw failure;
                throw new Lost();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(future -> future.cancel(true));
                throw new Lost();
            }
        }

        private void pause(Duration requested) {
            long millis = Math.min(MAX_BACKOFF.toMillis(), Math.max(MIN_BACKOFF_MILLIS, requested.toMillis()))
                    + ThreadLocalRandom.current().nextLong(JITTER_MILLIS);
            try { Thread.sleep(millis); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Lost(); }
        }
    }

    /** Лимит вызовов или бюджета исчерпан: оставшиеся недели части не разбираются. */
    private static class Budget extends RuntimeException {
        Budget() { super("Достигнут лимит запросов или стоимости обработки"); }
    }
}
