package com.sizylift.backend.video.entity;

import java.util.Arrays;

/**
 * {@code pending} — место выдано, файлы ещё не подтверждены; {@code processing} — исходник из браузера загружен
 * и ждёт перекодирования; {@code ready} — ролик можно смотреть.
 */
public enum VideoStatus {
    PENDING("pending"),
    PROCESSING("processing"),
    READY("ready");

    private final String code;

    VideoStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static VideoStatus of(String code) {
        return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst().orElseThrow();
    }
}
