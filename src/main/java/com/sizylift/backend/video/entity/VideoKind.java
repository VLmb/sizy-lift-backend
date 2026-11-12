package com.sizylift.backend.video.entity;

import java.util.Arrays;
import java.util.Optional;

/** Вид ролика (ADR-0012): видео подхода подопечного или видео техники тренера. */
public enum VideoKind {
    SET("set"),
    TECHNIQUE("technique");

    private final String code;

    VideoKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<VideoKind> parse(String code) {
        return Arrays.stream(values()).filter(kind -> kind.code.equals(code)).findFirst();
    }
}
