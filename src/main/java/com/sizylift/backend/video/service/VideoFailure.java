package com.sizylift.backend.video.service;

import org.springframework.http.HttpStatus;

/** Отказ с причиной, которую приложение показывает пользователю (docs/video.md). */
public class VideoFailure extends RuntimeException {

    private final Reason reason;

    public VideoFailure(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_VIDEO("invalid_video", HttpStatus.BAD_REQUEST, "Некорректные сведения о видео"),
        NOT_FOUND("not_found", HttpStatus.NOT_FOUND, "Видео не найдено"),
        COACH_REQUIRED("coach_required", HttpStatus.FORBIDDEN, "Видео техники загружает тренер"),
        ID_CONFLICT("id_conflict", HttpStatus.CONFLICT, "Идентификатор видео занят"),
        TOO_LARGE("video_too_large", HttpStatus.UNPROCESSABLE_CONTENT, "Видео больше допустимого размера"),
        TOO_LONG("video_too_long", HttpStatus.UNPROCESSABLE_CONTENT, "Видео длиннее допустимого"),
        QUOTA_EXCEEDED("quota_exceeded", HttpStatus.CONFLICT, "Место для видео закончилось: удалите старые ролики"),
        STORAGE_FULL("storage_full", HttpStatus.SERVICE_UNAVAILABLE, "Хранилище видео заполнено"),
        UPLOAD_INCOMPLETE("upload_incomplete", HttpStatus.CONFLICT, "Файлы видео не загружены полностью");

        private final String code;
        private final HttpStatus status;
        private final String message;

        Reason(String code, HttpStatus status, String message) {
            this.code = code;
            this.status = status;
            this.message = message;
        }

        public String code() {
            return code;
        }

        public HttpStatus status() {
            return status;
        }
    }
}
