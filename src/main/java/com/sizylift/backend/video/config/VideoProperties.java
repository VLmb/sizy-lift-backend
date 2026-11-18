package com.sizylift.backend.video.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.sizylift.backend.video.entity.VideoKind;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Видео (ADR-0012). Пределы — настройки, а не константы: {@code limits} — длина и размер ролика по видам
 * (видео техники длиннее видео подхода), {@code maxPreviewSizeBytes} — кадр-превью, {@code quota} — на
 * пользователя по видам,
 * {@code bucketLimitBytes} — всё хранилище, {@code pendingTtl} — сколько живёт незавершённая загрузка.
 * {@code durationTolerance} — насколько настоящая длина ролика может превысить предел (сжатие немного её меняет),
 * {@code maxIndexBytes} — предел индекса MP4 ({@code moov}), который сервер читает при проверке содержимого,
 * {@code orphanTtl} — через сколько комментарий отпускает ролик, который так и не появился на сервере,
 * {@code fillWarnings} — доли общего предела, на которых сервер пишет предупреждение в журнал.
 * {@code browser} — загрузка с сайта и из PWA: исходник как есть, перекодирует сервер.
 * {@code enabled} — флаг функции: выключенные видео клиенты прячут ({@code videoAvailable} в {@code /api/v1/me}),
 * а сервер не выдаёт и не принимает ролики; удаление работает.
 */
@ConfigurationProperties("sizylift.video")
public record VideoProperties(
        @DefaultValue("true") boolean enabled,
        Storage storage,
        Duration uploadUrlTtl,
        Duration viewUrlTtl,
        KindLimits limits,
        long maxPreviewSizeBytes,
        Quota quota,
        long bucketLimitBytes,
        Duration pendingTtl,
        Duration durationTolerance,
        long maxIndexBytes,
        Duration orphanTtl,
        List<Double> fillWarnings,
        Browser browser) {

    /**
     * S3-совместимое хранилище. {@code endpoint} — адрес для сервера, {@code publicEndpoint} — адрес в
     * подписанных ссылках, по которому хранилище видят клиенты.
     */
    public record Storage(URI endpoint, URI publicEndpoint, String region, String bucket, String accessKey,
                          String secretKey) {
    }

    public record Quota(long setBytes, long techniqueBytes) {
    }

    /** Пределы ролика по видам. */
    public record KindLimits(Limits set, Limits technique) {

        public Limits of(VideoKind kind) {
            return kind == VideoKind.TECHNIQUE ? technique : set;
        }
    }

    /**
     * {@code maxDuration} — длина ролика, {@code maxSizeBytes} — ролик после сжатия (приложением или сервером),
     * {@code maxSourceSizeBytes} — исходник из браузера до перекодирования.
     */
    public record Limits(Duration maxDuration, long maxSizeBytes, long maxSourceSizeBytes) {
    }

    /**
     * Загрузка из браузера. {@code contentTypes} — допустимые типы исходника, {@code processing} —
     * перекодирование; пределы исходника — в {@link Limits}.
     */
    public record Browser(List<String> contentTypes, Processing processing) {
    }

    /**
     * Перекодирование ffmpeg: один ролик за раз, {@code threads} потоков с пониженным приоритетом
     * ({@code niceness}), чтобы не занять оба vCPU. {@code timeout} — предел одного запуска ffmpeg,
     * {@code maxAttempts} — сколько раз ролик пробуют перекодировать после сбоев, {@code workDir} — временные
     * файлы.
     */
    public record Processing(String ffmpeg, String ffprobe, int niceness, int threads, Duration timeout,
                             int maxAttempts, Path workDir) {
    }
}
