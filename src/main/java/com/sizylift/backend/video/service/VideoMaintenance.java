package com.sizylift.backend.video.service;

import java.time.Clock;
import java.util.Comparator;

import com.sizylift.backend.video.config.VideoProperties;
import com.sizylift.backend.video.entity.Video;
import com.sizylift.backend.video.repository.VideoRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Фоновые задачи видео (ADR-0012): незавершённые загрузки старше {@code pending-ttl} удаляются, комментарии
 * отпускают ролики, которых нет дольше {@code orphan-ttl}, файлы удалённых роликов убираются из хранилища.
 * Сбой хранилища оставляет строку в очереди до следующего прохода. Заполнение общего предела выше порогов
 * {@code fill-warnings} пишется в журнал, пока в проекте нет оповещений. Ключи объектов в журнал не попадают.
 */
@Service
public class VideoMaintenance {

    private static final Logger log = LoggerFactory.getLogger(VideoMaintenance.class);
    private static final int REMOVAL_BATCH = 50;
    private static final int ORPHAN_BATCH = 200;
    private static final double PERCENT = 100;
    private static final double NO_WARNING = 0;

    private final VideoRepository videos;
    private final VideoStorage storage;
    private final VideoProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final VideoService service;
    private final Counter failedRemovals;
    /** Последний порог, о котором уже предупредили; при спаде ниже него предупреждение повторится. */
    private volatile double warnedLevel = NO_WARNING;

    VideoMaintenance(VideoRepository videos, VideoStorage storage, VideoProperties properties,
                     TransactionTemplate transactions, Clock clock, MeterRegistry meters, VideoService service) {
        this.videos = videos;
        this.service = service;
        this.storage = storage;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        this.failedRemovals = Counter.builder("sizylift.video.removal.failures").register(meters);
        Gauge.builder("sizylift.video.stored.bytes", videos, VideoRepository::totalBytes).register(meters);
        Gauge.builder("sizylift.video.bucket.limit.bytes", properties, VideoProperties::bucketLimitBytes)
                .register(meters);
        Gauge.builder("sizylift.video.bucket.used.ratio", this, VideoMaintenance::usedRatio).register(meters);
    }

    @Scheduled(cron = "${sizylift.video.cleanup-cron}", zone = "${sizylift.coach-workspace.zone}")
    public void dropStalePending() {
        var now = clock.instant();
        transactions.executeWithoutResult(status -> videos.stalePending(now.minus(properties.pendingTtl()))
                .forEach(id -> videos.lock(id).filter(video -> !video.ready()).ifPresent(video -> {
                    videos.delete(id);
                    videos.queueRemoval(id, video.ownerId(), now);
                })));
        var detached = service.detachOrphans(now.minus(properties.orphanTtl()), ORPHAN_BATCH);
        if (detached > 0) {
            log.info("Комментарии отпустили ролики, которых нет на сервере: {}", detached);
        }
    }

    @Scheduled(fixedDelayString = "${sizylift.video.fill-check-interval}")
    public void checkFill() {
        var ratio = usedRatio();
        var level = properties.fillWarnings().stream().filter(threshold -> ratio >= threshold)
                .max(Comparator.naturalOrder()).orElse(NO_WARNING);
        if (level > warnedLevel) {
            log.warn("Хранилище видео заполнено на {} % общего предела ({} байт); порог {} %",
                    Math.round(ratio * PERCENT), properties.bucketLimitBytes(), Math.round(level * PERCENT));
        }
        warnedLevel = level;
    }

    /** Доля общего предела, занятая роликами и превью, включая незавершённые загрузки. */
    public double usedRatio() {
        return (double) videos.totalBytes() / properties.bucketLimitBytes();
    }

    @Scheduled(fixedDelayString = "${sizylift.video.removal-interval}")
    public void removeObjects() {
        try {
            transactions.executeWithoutResult(status -> videos.nextRemovals(REMOVAL_BATCH).forEach(removal -> {
                storage.delete(Video.videoKey(removal.ownerId(), removal.videoId()));
                storage.delete(Video.previewKey(removal.ownerId(), removal.videoId()));
                storage.delete(Video.sourceKey(removal.ownerId(), removal.videoId()));
                videos.removalDone(removal.videoId());
            }));
        } catch (RuntimeException e) {
            failedRemovals.increment();
        }
    }
}
