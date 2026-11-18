package com.sizylift.backend.video;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Файлы для тестов видео: MP4 нужного размера и длины (индекс в конце, как у сжатого приложением), JPEG. */
final class VideoFiles {

    static final int TIMESCALE = 1000;
    private static final int FTYP_SIZE = 24;
    private static final int MVHD_BODY = 100;
    private static final int MOOV_SIZE = 8 + 8 + MVHD_BODY;
    private static final int MDAT_HEADER = 8;

    private VideoFiles() {
    }

    /** {@code ftyp}, {@code mdat} со случайным содержимым и {@code moov} с {@code mvhd} в конце. */
    static byte[] mp4(int size, long durationMs) {
        var file = ByteBuffer.allocate(size);
        file.putInt(FTYP_SIZE).put(ascii("ftyp")).put(ascii("isom")).putInt(0x200).put(ascii("isom"))
                .put(ascii("mp41"));
        var mdat = size - FTYP_SIZE - MOOV_SIZE;
        file.putInt(mdat).put(ascii("mdat"));
        file.put(random(mdat - MDAT_HEADER, size));
        file.putInt(MOOV_SIZE).put(ascii("moov"));
        file.putInt(8 + MVHD_BODY).put(ascii("mvhd"));
        file.putInt(0).putInt(0).putInt(0).putInt(TIMESCALE).putInt((int) durationMs);
        file.put(new byte[MVHD_BODY - 5 * Integer.BYTES]);
        return file.array();
    }

    /** Маркер начала JPEG и случайное содержимое. */
    static byte[] jpeg(int size) {
        var content = random(size, size);
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xD8;
        content[2] = (byte) 0xFF;
        return content;
    }

    static byte[] random(int size, long seed) {
        var content = new byte[size];
        new Random(seed).nextBytes(content);
        return content;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
