package com.sizylift.backend.video.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Видео (ADR-0012). Пределы — настройки, а не константы: {@code maxSizeBytes} и {@code maxPreviewSizeBytes} —
 * ролик и кадр-превью, {@code maxDuration} — длина ролика, {@code quota} — на пользователя по видам,
 * {@code bucketLimitBytes} — всё хранилище, {@code pendingTtl} — сколько живёт незавершённая загрузка.
 * {@code durationTolerance} — насколько настоящая длина ролика может превысить предел (сжатие немного её меняет),
 * {@code maxIndexBytes} — предел индекса MP4 ({@code moov}), который сервер читает при проверке содержимого,
 * {@code orphanTtl} — через сколько комментарий отпускает ролик, который так и не появился на сервере,
 * {@code fillWarnings} — доли общего предела, на которых сервер пишет предупреждение в журнал.
 * {@code enabled} — флаг функции: выключенные видео клиенты прячут ({@code videoAvailable} в {@code /api/v1/me}),
 * а сервер не выдаёт и не принимает ролики; удаление работает.
 */
@ConfigurationProperties("sizylift.video")
public record VideoProperties(
        @DefaultValue("true") boolean enabled,
        Storage storage,
        Duration uploadUrlTtl,
        Duration viewUrlTtl,
        long maxSizeBytes,
        long maxPreviewSizeBytes,
        Duration maxDuration,
        Quota quota,
        long bucketLimitBytes,
        Duration pendingTtl,
        Duration durationTolerance,
        long maxIndexBytes,
        Duration orphanTtl,
        List<Double> fillWarnings) {

    /**
     * S3-совместимое хранилище. {@code endpoint} — адрес для сервера, {@code publicEndpoint} — адрес в
     * подписанных ссылках, по которому хранилище видят клиенты.
     */
    public record Storage(URI endpoint, URI publicEndpoint, String region, String bucket, String accessKey,
                          String secretKey) {
    }

    public record Quota(long setBytes, long techniqueBytes) {
    }
}
