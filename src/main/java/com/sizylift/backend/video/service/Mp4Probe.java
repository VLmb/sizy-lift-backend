package com.sizylift.backend.video.service;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Проверка содержимого ролика без перекодирования (ADR-0012, раздел 2): коробки MP4 верхнего уровня обходятся
 * запросами диапазонов, длительность берётся из {@code mvhd} внутри {@code moov}. Индекс может лежать и в начале,
 * и в конце файла. Всё, что не похоже на MP4, — пустой результат.
 */
final class Mp4Probe {

    /** Чтение части объекта: {@code length} байт с {@code offset}. */
    @FunctionalInterface
    interface Reader {
        byte[] read(long offset, int length);
    }

    static final int JPEG_SIGNATURE_LENGTH = 3;

    private static final int BOX_HEADER = 8;
    private static final int LARGE_BOX_HEADER = 16;
    private static final int SIZE_TO_END = 0;
    private static final int LARGE_SIZE = 1;
    private static final int MAX_TOP_LEVEL_BOXES = 64;
    private static final String MOOV = "moov";
    private static final String MVHD = "mvhd";
    /** Коробки, которые встречаются на верхнем уровне MP4 и QuickTime; любая другая — не ролик. */
    private static final Set<String> TOP_LEVEL = Set.of("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid",
            "udta", "meta", "pdin", "moof", "mfra", "sidx", "styp", "prft");
    private static final int MVHD_V0_TIMESCALE = 12;
    private static final int MVHD_V0_DURATION = 16;
    private static final int MVHD_V1_TIMESCALE = 20;
    private static final int MVHD_V1_DURATION = 24;
    private static final long UNKNOWN_DURATION_V0 = 0xFFFF_FFFFL;
    private static final long MILLIS_PER_SECOND = 1000;
    private static final int[] JPEG_SIGNATURE = {0xFF, 0xD8, 0xFF};

    private Mp4Probe() {
    }

    /** Длительность ролика в миллисекундах; пусто — файл не MP4 или индекс больше {@code maxIndexBytes}. */
    static OptionalLong durationMs(Reader reader, long size, long maxIndexBytes) {
        long offset = 0;
        for (int boxes = 0; boxes < MAX_TOP_LEVEL_BOXES && offset + BOX_HEADER <= size; boxes++) {
            var header = reader.read(offset, (int) Math.min(LARGE_BOX_HEADER, size - offset));
            if (header.length < BOX_HEADER || !TOP_LEVEL.contains(type(header, 0))) {
                return OptionalLong.empty();
            }
            long boxSize = uint32(header, 0);
            int headerLength = BOX_HEADER;
            if (boxSize == LARGE_SIZE) {
                if (header.length < LARGE_BOX_HEADER) {
                    return OptionalLong.empty();
                }
                boxSize = uint64(header, BOX_HEADER);
                headerLength = LARGE_BOX_HEADER;
            } else if (boxSize == SIZE_TO_END) {
                boxSize = size - offset;
            }
            if (boxSize < headerLength || boxSize > size - offset) {
                return OptionalLong.empty();
            }
            if (MOOV.equals(type(header, 0))) {
                long body = boxSize - headerLength;
                return body > maxIndexBytes ? OptionalLong.empty()
                        : movieDuration(reader.read(offset + headerLength, (int) body));
            }
            offset += boxSize;
        }
        return OptionalLong.empty();
    }

    /** Кадр-превью начинается с маркера JPEG. */
    static boolean jpeg(byte[] head) {
        if (head.length < JPEG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < JPEG_SIGNATURE.length; i++) {
            if (Byte.toUnsignedInt(head[i]) != JPEG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static OptionalLong movieDuration(byte[] moov) {
        int position = 0;
        while (position + BOX_HEADER <= moov.length) {
            long boxSize = uint32(moov, position);
            if (boxSize < BOX_HEADER || boxSize > moov.length - position) {
                return OptionalLong.empty();
            }
            if (MVHD.equals(type(moov, position))) {
                return mvhd(moov, position + BOX_HEADER, (int) boxSize - BOX_HEADER);
            }
            position += (int) boxSize;
        }
        return OptionalLong.empty();
    }

    private static OptionalLong mvhd(byte[] data, int start, int length) {
        boolean wide = length > 0 && data[start] != 0;
        int needed = wide ? MVHD_V1_DURATION + Long.BYTES : MVHD_V0_DURATION + Integer.BYTES;
        if (length < needed) {
            return OptionalLong.empty();
        }
        long timescale = uint32(data, start + (wide ? MVHD_V1_TIMESCALE : MVHD_V0_TIMESCALE));
        long duration = wide ? uint64(data, start + MVHD_V1_DURATION) : uint32(data, start + MVHD_V0_DURATION);
        if (timescale == 0 || duration <= 0 || (!wide && duration == UNKNOWN_DURATION_V0)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(duration / timescale * MILLIS_PER_SECOND
                + duration % timescale * MILLIS_PER_SECOND / timescale);
    }

    private static String type(byte[] data, int boxStart) {
        return new String(data, boxStart + Integer.BYTES, Integer.BYTES, StandardCharsets.ISO_8859_1);
    }

    private static long uint32(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < Integer.BYTES; i++) {
            value = value << Byte.SIZE | Byte.toUnsignedInt(data[offset + i]);
        }
        return value;
    }

    /** 64-битный размер или длительность; больше {@code Long.MAX_VALUE} не бывает в настоящих файлах. */
    private static long uint64(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value = value << Byte.SIZE | Byte.toUnsignedInt(data[offset + i]);
        }
        return value;
    }
}
