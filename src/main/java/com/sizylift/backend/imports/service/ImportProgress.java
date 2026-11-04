package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.sizylift.backend.imports.dto.ImportDtos.Progress;
import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.repository.ImportRepository;
import org.springframework.stereotype.Component;

/**
 * Прогресс разбора по потоку ответа модели: сколько упражнений и недель модель уже полностью выписала.
 * Числа реальные, процент не выдумывается. Запись в сессию не чаще раза в секунду и с проверкой аренды.
 */
@Component
public class ImportProgress {
    public static final String PLAN = "plan";
    public static final String WEEKS = "weeks";
    private static final String EXERCISES_ARRAY = "exercises";
    private static final String PLAN_ARRAY = "plan";
    private static final String WEEKS_ARRAY = "weeks";
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private final ImportRepository sessions;
    private final Clock clock;

    public ImportProgress(ImportRepository sessions, Clock clock) { this.sessions = sessions; this.clock = clock; }

    /** Этап плана: найденные упражнения, недели плана и, для небольшой программы, разобранные недели. */
    public Tracker plan(ImportSession session) { return new Tracker(session, PLAN, 0, null); }

    /** Этап недель: всего недель из плана, готовые — завершённые части плюс недели, уже выписанные в потоке. */
    public Tracker weeks(ImportSession session, ImportPlan plan) {
        return new Tracker(session, WEEKS, plan.exercises().size(), plan.weeks().size());
    }

    public final class Tracker {
        private final ImportSession session;
        private final String stage;
        private final int exercises;
        private final Integer total;
        private final Set<StreamCounter> active = new HashSet<>();
        private int completed;
        private Progress last;
        private Instant written = Instant.MIN;

        private Tracker(ImportSession session, String stage, int exercises, Integer total) {
            this.session = session; this.stage = stage; this.exercises = exercises; this.total = total;
            publish(true);
        }

        /** Слушатель одного вызова модели. */
        public synchronized StreamCounter call() {
            var counter = new StreamCounter(() -> publish(false));
            active.add(counter);
            return counter;
        }

        /** Вызов принят: его недели засчитываются окончательно. */
        public synchronized void completed(StreamCounter counter, int weeks) {
            active.remove(counter);
            completed += weeks;
            publish(false);
        }

        /** Вызов отклонён или оборван: его частичный счёт больше не учитывается. */
        public synchronized void abandoned(StreamCounter counter) { active.remove(counter); }

        private synchronized void publish(boolean force) {
            var next = current();
            if (next.equals(last)) return;
            var now = clock.instant();
            if (!force && now.isBefore(written.plus(INTERVAL))) return;
            if (sessions.progress(session, next, now)) {
                last = next;
                written = now;
            }
        }

        /** Готовые недели не убывают: повтор части не откатывает уже показанное число. */
        private Progress current() {
            int found = exercises;
            int done = completed;
            Integer weeks = total;
            for (var counter : active) {
                done += counter.count(WEEKS_ARRAY);
                if (stage.equals(PLAN)) {
                    found = Math.max(found, counter.count(EXERCISES_ARRAY));
                    int planned = counter.count(PLAN_ARRAY);
                    if (planned > 0) weeks = planned;
                }
            }
            if (last != null) done = Math.max(done, last.weeksDone());
            if (weeks != null) done = Math.min(done, weeks);
            return new Progress(stage, found, done, weeks);
        }
    }
}
