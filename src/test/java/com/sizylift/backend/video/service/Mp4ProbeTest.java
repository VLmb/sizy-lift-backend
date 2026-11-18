package com.sizylift.backend.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Mp4ProbeTest {

    private static final long MAX_INDEX = 4 * 1024 * 1024;

    @Test
    void readsDurationWithTheIndexAtTheEnd() {
        var file = concat(ftyp(), box("mdat", noise(5000)), moov(mvhdV0(600, 600 * 24)));

        assertThat(probe(file)).hasValue(24_000);
    }

    @Test
    void readsDurationWithTheIndexAtTheStartAndAWideHeader() {
        var file = concat(ftyp(), moov(mvhdV1(90_000, 90_000L * 59 + 45_000)), box("free", new byte[16]),
                box("mdat", noise(2000)));

        assertThat(probe(file)).hasValue(59_500);
    }

    @Test
    void followsSixtyFourBitBoxSizes() {
        var payload = noise(3000);
        var largeMdat = ByteBuffer.allocate(16 + payload.length).putInt(1).put(ascii("mdat"))
                .putLong(16 + payload.length).put(payload).array();
        var file = concat(ftyp(), largeMdat, moov(mvhdV0(1000, 12_345)));

        assertThat(probe(file)).hasValue(12_345);
    }

    /** Настоящие файлы ffmpeg: MP4 с индексом в конце (как пишет Android) и QuickTime .mov (как iPhone). */
    @Test
    void readsRealFilesFromFfmpeg(@TempDir Path directory) throws Exception {
        for (var name : new String[] {"end.mp4", "phone.mov"}) {
            var file = directory.resolve(name);
            var process = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                    "testsrc=size=320x240:rate=30", "-f", "lavfi", "-i", "sine", "-t", "2.5", "-c:v", "libx264",
                    "-preset", "ultrafast", "-c:a", "aac", file.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor()).as(new String(process.getInputStream().readAllBytes())).isZero();
            var bytes = Files.readAllBytes(file);

            assertThat(probe(bytes).orElseThrow()).as(name).isBetween(2_450L, 2_600L);
        }
    }

    @Test
    void rejectsWhatIsNotAMovie() {
        assertThat(probe(noise(10_000))).as("случайные байты").isEmpty();
        assertThat(probe(concat(ftyp(), box("mdat", noise(100))))).as("без индекса").isEmpty();
        assertThat(probe(concat(ftyp(), moov(box("trak", new byte[8]))))).as("без mvhd").isEmpty();
        assertThat(probe(concat(ftyp(), moov(mvhdV0(0, 1000))))).as("нулевая шкала времени").isEmpty();
        var truncated = concat(ftyp(), box("mdat", noise(100)));
        assertThat(probe(Arrays.copyOf(truncated, truncated.length - 10))).as("обрезанный файл").isEmpty();
        var big = concat(ftyp(), moov(mvhdV0(1000, 1000)));
        assertThat(Mp4Probe.durationMs(reader(big), big.length, 16)).as("индекс больше предела").isEmpty();
    }

    @Test
    void recognisesJpeg() {
        assertThat(Mp4Probe.jpeg(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})).isTrue();
        assertThat(Mp4Probe.jpeg(new byte[] {(byte) 0x89, 'P', 'N'})).isFalse();
        assertThat(Mp4Probe.jpeg(new byte[] {(byte) 0xFF})).isFalse();
    }

    private static OptionalLong probe(byte[] file) {
        return Mp4Probe.durationMs(reader(file), file.length, MAX_INDEX);
    }

    private static Mp4Probe.Reader reader(byte[] file) {
        return (offset, length) -> Arrays.copyOfRange(file, (int) offset, (int) offset + length);
    }

    private static byte[] ftyp() {
        return box("ftyp", concat(ascii("isom"), new byte[4], ascii("isom")));
    }

    private static byte[] moov(byte[] children) {
        return box("moov", children);
    }

    private static byte[] mvhdV0(int timescale, int duration) {
        return box("mvhd", ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0).putInt(timescale)
                .putInt(duration).array());
    }

    private static byte[] mvhdV1(int timescale, long duration) {
        return box("mvhd", ByteBuffer.allocate(112).putInt(1 << 24).putLong(0).putLong(0).putInt(timescale)
                .putLong(duration).array());
    }

    private static byte[] box(String type, byte[] body) {
        return ByteBuffer.allocate(8 + body.length).putInt(8 + body.length).put(ascii(type)).put(body).array();
    }

    private static byte[] concat(byte[]... parts) {
        var out = ByteBuffer.allocate(Arrays.stream(parts).mapToInt(part -> part.length).sum());
        Arrays.stream(parts).forEach(out::put);
        return out.array();
    }

    private static byte[] noise(int size) {
        var content = new byte[size];
        new Random(size).nextBytes(content);
        return content;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
