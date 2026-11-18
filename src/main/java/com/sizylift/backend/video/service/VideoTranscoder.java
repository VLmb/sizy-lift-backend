package com.sizylift.backend.video.service;

import java.io.IOException;
import java.nio.file.Path;

import com.sizylift.backend.video.config.VideoProperties.Limits;

/** Перекодирование исходника из браузера в ролик приложения: MP4 H.264/AAC не выше 720p и кадр-превью JPEG. */
public interface VideoTranscoder {

    /**
     * Пишет ролик в {@code video} и превью в {@code preview}. Файл, который не читается как видео или длиннее
     * предела своего вида ({@code limits}), — {@link Rejected}; {@link IOException} — сбой, который стоит повторить.
     */
    Transcoded transcode(Path source, Path video, Path preview, Limits limits) throws IOException;

    /** Настоящая длина получившегося ролика. */
    record Transcoded(int durationMs) {
    }

    /** Исходник не перекодировать: повтор не поможет. */
    final class Rejected extends RuntimeException {

        private final VideoFailure.Reason reason;

        public Rejected(VideoFailure.Reason reason) {
            super(reason.code());
            this.reason = reason;
        }

        public VideoFailure.Reason reason() {
            return reason;
        }
    }
}
