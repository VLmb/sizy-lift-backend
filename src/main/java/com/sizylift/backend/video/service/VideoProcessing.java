package com.sizylift.backend.video.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import com.sizylift.backend.video.config.VideoProperties;
import com.sizylift.backend.video.entity.Video;
import com.sizylift.backend.video.repository.VideoRepository;
import com.sizylift.backend.video.service.VideoFailure.Reason;
import com.sizylift.backend.web.config.WebProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Перекодирование роликов из браузера (ADR-0012, дополнение 18.11.2026): очередь — строки {@code processing}
 * в таблице {@code video}, обработчик один, ролик за роликом. Сбой хранилища или ffmpeg отпускает ролик
 * обратно в очередь; после {@code max-attempts} попыток или если файл не читается как видео, ролик удаляется
 * и снимается с комментариев. Брошенный упавшим сервером ролик подхватывается через {@code timeout}.
 * Ключи объектов и id пользователей в журнал не попадают.
 */
@Service
public class VideoProcessing {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessing.class);
    private static final String OUTCOME = "outcome";
    private static final String SOURCE = "source";
    private static final String VIDEO_FILE = "video.mp4";
    private static final String PREVIEW_FILE = "preview.jpg";

    private final VideoRepository videos;
    private final VideoStorage storage;
    private final VideoService service;
    private final VideoTranscoder transcoder;
    private final VideoProperties properties;
    private final WebProperties web;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry meters;
    private volatile boolean browsersAllowed;

    VideoProcessing(VideoRepository videos, VideoStorage storage, VideoService service, VideoTranscoder transcoder,
                    VideoProperties properties, WebProperties web, TransactionTemplate transactions, Clock clock,
                    MeterRegistry meters) {
        this.videos = videos;
        this.storage = storage;
        this.service = service;
        this.transcoder = transcoder;
        this.properties = properties;
        this.web = web;
        this.transactions = transactions;
        this.clock = clock;
        this.meters = meters;
    }

    /** Перекодирует всё, что ждёт, по одному ролику. */
    @Scheduled(fixedDelayString = "${sizylift.video.browser.processing.poll-interval}")
    public void processWaiting() {
        if (!properties.enabled()) {
            return;
        }
        for (var job = claim(); job.isPresent(); job = claim()) {
            process(job.get());
        }
    }

    /**
     * CORS бакета: браузеры сайта и PWA кладут исходник прямо в хранилище. Хранилище при запуске сервера может
     * быть ещё недоступно — тогда повтор при следующем проходе.
     */
    @Scheduled(fixedDelayString = "${sizylift.video.browser.processing.poll-interval}")
    public void allowBrowsers() {
        if (browsersAllowed || web.allowedOrigins().isEmpty()) {
            return;
        }
        try {
            storage.allowBrowsers(web.allowedOrigins());
            browsersAllowed = true;
        } catch (RuntimeException e) {
            log.warn("Хранилище видео не приняло CORS для браузеров: {}", e.getClass().getSimpleName());
        }
    }

    private Optional<Job> claim() {
        var now = clock.instant();
        var abandoned = now.minus(properties.browser().processing().timeout().multipliedBy(2));
        return transactions.execute(status -> videos.nextProcessing(abandoned)
                .map(video -> new Job(video, videos.startProcessing(video.id(), now))));
    }

    private void process(Job job) {
        var video = job.video();
        if (job.attempt() > properties.browser().processing().maxAttempts()) {
            reject(video, Reason.UNSUPPORTED, "attempts");
            return;
        }
        var sample = Timer.start(meters);
        Path directory = null;
        try {
            directory = workDirectory();
            var source = directory.resolve(SOURCE);
            var output = directory.resolve(VIDEO_FILE);
            var preview = directory.resolve(PREVIEW_FILE);
            storage.download(video.sourceKey(), source);
            var result = transcoder.transcode(source, output, preview, properties.limits().of(video.kind()));
            storage.upload(video.videoKey(), VideoService.VIDEO_CONTENT_TYPE, output);
            storage.upload(video.previewKey(), VideoService.PREVIEW_CONTENT_TYPE, preview);
            if (service.finishProcessing(video.id(), Files.size(output), Files.size(preview), result.durationMs())) {
                removeQuietly(video.sourceKey());
                count("ready");
            } else {
                // Владелец удалил ролик во время перекодирования: результат никому не нужен.
                removeQuietly(video.videoKey());
                removeQuietly(video.previewKey());
                count("deleted");
            }
        } catch (VideoTranscoder.Rejected e) {
            reject(video, e.reason(), e.reason().code());
        } catch (IOException | RuntimeException e) {
            log.warn("Перекодирование видео не удалось, попытка {}: {}", job.attempt(),
                    e.getClass().getSimpleName());
            videos.releaseProcessing(video.id());
            count("retry");
        } finally {
            sample.stop(meters.timer("sizylift.video.processing.duration"));
            deleteQuietly(directory);
        }
    }

    private void reject(Video video, Reason reason, String outcome) {
        service.failProcessing(video.id(), reason);
        count("rejected_" + outcome);
    }

    private Path workDirectory() throws IOException {
        var root = properties.browser().processing().workDir();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "job-");
    }

    private void removeQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            meters.counter("sizylift.video.removal.failures").increment();
        }
    }

    private void count(String outcome) {
        meters.counter("sizylift.video.processing", OUTCOME, outcome).increment();
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | UncheckedIOException e) {
            log.warn("Временные файлы перекодирования не удалены: {}", e.getClass().getSimpleName());
        }
    }

    private record Job(Video video, int attempt) {
    }
}
