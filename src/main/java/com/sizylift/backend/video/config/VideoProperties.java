package com.sizylift.backend.video.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Видео (ADR-0012). Пределы — настройки, а не константы: {@code maxSizeBytes} и {@code maxPreviewSizeBytes} —
 * ролик и кадр-превью, {@code maxDuration} — длина ролика, {@code quota} — на пользователя по видам,
 * {@code bucketLimitBytes} — всё хранилище, {@code pendingTtl} — сколько живёт незавершённая загрузка.
 */
@ConfigurationProperties("sizylift.video")
public record VideoProperties(
        Storage storage,
        Duration uploadUrlTtl,
        Duration viewUrlTtl,
        long maxSizeBytes,
        long maxPreviewSizeBytes,
        Duration maxDuration,
        Quota quota,
        long bucketLimitBytes,
        Duration pendingTtl) {

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
