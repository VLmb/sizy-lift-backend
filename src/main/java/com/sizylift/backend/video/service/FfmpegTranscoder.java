package com.sizylift.backend.video.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.sizylift.backend.video.config.VideoProperties;
import com.sizylift.backend.video.config.VideoProperties.Limits;
import com.sizylift.backend.video.service.VideoFailure.Reason;

/**
 * Перекодирование ffmpeg (ADR-0012, дополнение 18.11.2026). Ролик — как у приложения: длинная сторона до 1280,
 * H.264 high, AAC, индекс в начале файла; битрейт снижается, если ролик не уложился в предел размера. Поворот
 * с телефона ffmpeg применяет сам. Запуск с пониженным приоритетом и ограничением потоков.
 */
public class FfmpegTranscoder implements VideoTranscoder {

    private static final long[] VIDEO_BITRATES = {2_500_000, 1_400_000};
    private static final String AUDIO_BITRATE = "128k";
    private static final int MAX_SIDE = 1280;
    private static final int PREVIEW_WIDTH = 480;
    /** Качество JPEG у ffmpeg: меньше — лучше; следующее — если превью не уложилось в предел. */
    private static final int[] PREVIEW_QUALITIES = {4, 8, 14};
    private static final double PREVIEW_AT_SECONDS = 0.5;
    private static final double HALF = 0.5;
    private static final long MILLIS_PER_SECOND = 1000;
    private static final String VIDEO_STREAM = "codec_type=video";
    private static final String DURATION = "duration=";
    private static final String LOG = "ffmpeg.log";

    private final VideoProperties properties;
    private final VideoProperties.Processing processing;

    public FfmpegTranscoder(VideoProperties properties) {
        this.properties = properties;
        this.processing = properties.browser().processing();
    }

    @Override
    public Transcoded transcode(Path source, Path video, Path preview, Limits limits) throws IOException {
        var limit = limits.maxDuration().plus(properties.durationTolerance());
        // Длины в заголовке может не быть (WebM из браузера): тогда её покажет готовый ролик.
        var sourceDuration = probe(source).orElseThrow(() -> new Rejected(Reason.UNSUPPORTED));
        if (sourceDuration.isPresent() && sourceDuration.get() > limit.toMillis()) {
            throw new Rejected(Reason.TOO_LONG);
        }
        encode(source, video, limit.plusSeconds(1), limits.maxSizeBytes());
        var duration = probe(video).flatMap(found -> found)
                .orElseThrow(() -> new Rejected(Reason.UNSUPPORTED));
        if (duration > limit.toMillis()) {
            throw new Rejected(Reason.TOO_LONG);
        }
        frame(video, preview, duration);
        return new Transcoded(duration.intValue());
    }

    /**
     * Длина в миллисекундах, если она известна; внешнее пусто — в файле нет видео или ffprobe его не читает.
     */
    private Optional<Optional<Long>> probe(Path file) throws IOException {
        var output = run(List.of(processing.ffprobe(), "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=codec_type:format=duration", "-of", "default=noprint_wrappers=1", file
                        .toString()), file.resolveSibling(LOG));
        if (output.exitCode() != 0 || !output.text().contains(VIDEO_STREAM)) {
            return Optional.empty();
        }
        return Optional.of(output.text().lines().filter(line -> line.startsWith(DURATION))
                .map(line -> line.substring(DURATION.length()).strip())
                .flatMap(value -> parseSeconds(value).stream())
                .map(seconds -> Math.round(seconds * MILLIS_PER_SECOND)).findFirst());
    }

    private void encode(Path source, Path video, Duration limit, long maxSizeBytes) throws IOException {
        for (long bitrate : VIDEO_BITRATES) {
            var result = run(ffmpeg("-i", source.toString(), "-map", "0:v:0", "-map", "0:a:0?",
                    "-t", seconds(limit.toMillis()),
                    "-vf", "scale=w='min(%d,iw)':h='min(%d,ih)':force_original_aspect_ratio=decrease"
                            .formatted(MAX_SIDE, MAX_SIDE) + ":force_divisible_by=2",
                    "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "high", "-pix_fmt", "yuv420p",
                    "-b:v", String.valueOf(bitrate), "-maxrate", String.valueOf(bitrate),
                    "-bufsize", String.valueOf(2 * bitrate),
                    "-c:a", "aac", "-b:a", AUDIO_BITRATE, "-ac", "2",
                    "-movflags", "+faststart", video.toString()), video.resolveSibling(LOG));
            if (result.exitCode() != 0) {
                throw new Rejected(Reason.UNSUPPORTED);
            }
            if (Files.size(video) <= maxSizeBytes) {
                return;
            }
        }
        throw new Rejected(Reason.TOO_LARGE);
    }

    private void frame(Path video, Path preview, long durationMs) throws IOException {
        var at = Math.min(PREVIEW_AT_SECONDS, durationMs * HALF / MILLIS_PER_SECOND);
        for (int quality : PREVIEW_QUALITIES) {
            var result = run(ffmpeg("-ss", String.format(Locale.ROOT, "%.3f", at), "-i", video.toString(),
                    "-frames:v", "1", "-vf", "scale=" + PREVIEW_WIDTH + ":-2", "-q:v", String.valueOf(quality),
                    preview.toString()), preview.resolveSibling(LOG));
            if (result.exitCode() != 0 || !Files.exists(preview)) {
                throw new Rejected(Reason.UNSUPPORTED);
            }
            if (Files.size(preview) <= properties.maxPreviewSizeBytes()) {
                return;
            }
        }
        throw new Rejected(Reason.TOO_LARGE);
    }

    private List<String> ffmpeg(String... arguments) {
        var command = new ArrayList<>(List.of(processing.ffmpeg(), "-nostdin", "-v", "error", "-y",
                "-threads", String.valueOf(processing.threads())));
        command.addAll(List.of(arguments));
        return command;
    }

    /** Запуск с пониженным приоритетом; вывод — во временный файл, чтобы процесс не встал на полном буфере. */
    private Output run(List<String> command, Path log) throws IOException {
        var full = new ArrayList<String>();
        if (processing.niceness() > 0) {
            full.addAll(List.of("nice", "-n", String.valueOf(processing.niceness())));
        }
        full.addAll(command);
        var process = new ProcessBuilder(full).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(processing.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg не уложился в " + processing.timeout());
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Перекодирование прервано", e);
        }
        return new Output(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
    }

    private static Optional<Double> parseSeconds(String value) {
        try {
            return Optional.of(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / (double) MILLIS_PER_SECOND);
    }

    private record Output(int exitCode, String text) {
    }
}
