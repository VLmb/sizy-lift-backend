package com.sizylift.backend.video.service;

import java.time.Clock;

import com.sizylift.backend.video.config.VideoProperties;
import com.sizylift.backend.video.entity.Video;
import com.sizylift.backend.video.repository.VideoRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Фоновые задачи видео (ADR-0012): незавершённые загрузки старше {@code pending-ttl} удаляются, файлы
 * удалённых роликов убираются из хранилища. Сбой хранилища оставляет строку в очереди до следующего прохода.
 * Ключи объектов в журнал не попадают.
 */
@Service
public class VideoMaintenance {

    private static final int REMOVAL_BATCH = 50;

    private final VideoRepository videos;
    private final VideoStorage storage;
    private final VideoProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Counter failedRemovals;

    VideoMaintenance(VideoRepository videos, VideoStorage storage, VideoProperties properties,
                     TransactionTemplate transactions, Clock clock, MeterRegistry meters) {
        this.videos = videos;
        this.storage = storage;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
        this.failedRemovals = Counter.builder("sizylift.video.removal.failures").register(meters);
        Gauge.builder("sizylift.video.stored.bytes", videos, VideoRepository::totalBytes).register(meters);
        Gauge.builder("sizylift.video.bucket.limit.bytes", properties, VideoProperties::bucketLimitBytes)
                .register(meters);
    }

    @Scheduled(cron = "${sizylift.video.cleanup-cron}", zone = "${sizylift.coach-workspace.zone}")
    public void dropStalePending() {
        var now = clock.instant();
        transactions.executeWithoutResult(status -> videos.stalePending(now.minus(properties.pendingTtl()))
                .forEach(id -> videos.lock(id).filter(video -> !video.ready()).ifPresent(video -> {
                    videos.delete(id);
                    videos.queueRemoval(id, video.ownerId(), now);
                })));
    }

    @Scheduled(fixedDelayString = "${sizylift.video.removal-interval}")
    public void removeObjects() {
        try {
            transactions.executeWithoutResult(status -> videos.nextRemovals(REMOVAL_BATCH).forEach(removal -> {
                storage.delete(Video.videoKey(removal.ownerId(), removal.videoId()));
                storage.delete(Video.previewKey(removal.ownerId(), removal.videoId()));
                videos.removalDone(removal.videoId());
            }));
        } catch (RuntimeException e) {
            failedRemovals.increment();
        }
    }
}
