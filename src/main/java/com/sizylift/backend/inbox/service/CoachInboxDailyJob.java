package com.sizylift.backend.inbox.service;

import java.time.LocalDate;

import com.sizylift.backend.inbox.config.CoachCalendar;
import com.sizylift.backend.inbox.entity.CoachEventKind;
import com.sizylift.backend.inbox.repository.CoachEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ежедневно: пропущенные плановые тренировки ({@code workout_missed}) и программы, которые кончаются через
 * 0–7 дней ({@code program_ending}). Идемпотентна по {@code dedup_key}; при нескольких экземплярах одновременно
 * работает один, остальные пропускают запуск.
 */
@Component
public class CoachInboxDailyJob {

    /** Глубже в прошлое не смотрим: первый запуск не заваливает тренера старыми пропусками. */
    static final int MISSED_LOOKBACK_DAYS = 14;
    /** Пропущена — дата раньше вчерашнего дня: вчерашнюю тренировку ещё могут опубликовать. */
    static final int MISSED_GRACE_DAYS = 1;
    static final int PROGRAM_ENDING_DAYS = 7;
    private static final String LOCK_NAME = "coach-inbox-daily";
    private static final Logger log = LoggerFactory.getLogger(CoachInboxDailyJob.class);

    private final CoachEventRepository events;
    private final CoachCalendar calendar;
    private final TransactionTemplate transactions;
    private final CoachInboxMetrics metrics;

    CoachInboxDailyJob(CoachEventRepository events, CoachCalendar calendar, TransactionTemplate transactions,
                       CoachInboxMetrics metrics) {
        this.events = events;
        this.calendar = calendar;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${sizylift.coach-workspace.daily-cron}", zone = "${sizylift.coach-workspace.zone}")
    public void runToday() {
        run(calendar.today());
    }

    /** Результат — сколько событий создано; {@code null} — запуск пропущен, его выполняет другой экземпляр. */
    public Result run(LocalDate today) {
        var result = metrics.timeRun(() -> transactions.execute(status -> create(today)));
        if (result != null) {
            metrics.countCreated(CoachEventKind.WORKOUT_MISSED, result.missed());
            metrics.countCreated(CoachEventKind.PROGRAM_ENDING, result.programEnding());
        }
        return result;
    }

    private Result create(LocalDate today) {
        if (!events.tryLockDaily(LOCK_NAME)) {
            log.info("Coach inbox daily job is running elsewhere, skipped");
            return null;
        }
        var now = calendar.now();
        var yesterday = today.minusDays(MISSED_GRACE_DAYS);
        var missed = events.insertMissed(today.minusDays(MISSED_LOOKBACK_DAYS), yesterday, calendar.zoneId(),
                now);
        var ending = events.insertProgramEnding(today, today.plusDays(PROGRAM_ENDING_DAYS), now);
        log.info("Coach inbox daily job created {} missed and {} program ending events", missed, ending);
        return new Result(missed, ending);
    }

    public record Result(int missed, int programEnding) {
    }
}
