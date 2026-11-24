package com.sizylift.backend.photo.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.sizylift.backend.photo.dto.WorkoutPhotoDtos.Page;
import com.sizylift.backend.photo.dto.WorkoutPhotoDtos.View;
import com.sizylift.backend.photo.dto.WorkoutPhotoDtos.WriteResult;
import com.sizylift.backend.photo.repository.WorkoutPhotoRepository;
import com.sizylift.backend.video.service.VideoStorage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;

@Service
public class WorkoutPhotoService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PIXELS = 24_000_000;
    private static final int PAGE_SIZE = 100;
    private static final int REMOVAL_BATCH_SIZE = 50;
    private static final long REMOVAL_INTERVAL_MS = 60_000;
    private static final Duration VIEW_TTL = Duration.ofMinutes(5);
    private static final Duration UPLOAD_CLEANUP_DELAY = Duration.ofHours(1);

    private final WorkoutPhotoRepository photos;
    private final VideoStorage storage;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Counter removalFailures;
    private final Counter uploadFailures;

    public WorkoutPhotoService(WorkoutPhotoRepository photos, VideoStorage storage, TransactionTemplate transactions,
                               Clock clock, MeterRegistry meters) {
        this.photos = photos;
        this.storage = storage;
        this.transactions = transactions;
        this.clock = clock;
        this.uploadFailures = Counter.builder("sizylift.workout.photo.upload.failures").register(meters);
        this.removalFailures = Counter.builder("sizylift.workout.photo.removal.failures").register(meters);
    }

    public Page page(UUID userId, UUID after) {
        var rows = photos.page(userId, after, PAGE_SIZE + 1);
        return rows.size() > PAGE_SIZE
                ? new Page(rows.subList(0, PAGE_SIZE), rows.get(PAGE_SIZE - 1).workoutId())
                : new Page(rows, null);
    }

    public View view(UUID userId, UUID workoutId) {
        var photo = photos.find(userId, workoutId).filter(item -> !item.metadata().deleted())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new View(photo.metadata(), storage.viewUrl(photo.objectKey(), VIEW_TTL));
    }

    public WriteResult upload(UUID userId, UUID workoutId, UUID version, UUID base, InputStream input,
                              long contentLength) throws IOException {
        if (!photos.workoutExists(userId, workoutId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (contentLength > MAX_BYTES) throw tooLarge();
        var bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw tooLarge();
        var dimensions = jpegDimensions(bytes);

        var key = "workout-photo/%s/%s/%s.jpg".formatted(userId, workoutId, UUID.randomUUID());
        // Если хранилище успеет записать файл, а транзакция откатится, ключ останется в очереди удаления.
        photos.queueRemoval(key, clock.instant().plus(UPLOAD_CLEANUP_DELAY));
        var result = write(userId, workoutId, version, base, () -> {
            if (!photos.lockReservation(key)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo upload reservation expired");
            }
            try {
                storage.uploadPhoto(key, MediaType.IMAGE_JPEG_VALUE, bytes);
            } catch (SdkException e) {
                uploadFailures.increment();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage unavailable");
            }
            photos.save(userId, workoutId, version, key, dimensions.width(), dimensions.height(), bytes.length);
            photos.finishRemoval(key);
        });
        // У повторного запроса или конфликта зарезервированный ключ не был использован.
        photos.finishRemoval(key);
        return result;
    }

    public WriteResult delete(UUID userId, UUID workoutId, UUID version, UUID base) {
        return write(userId, workoutId, version, base,
                () -> photos.save(userId, workoutId, version, null, null, null, null));
    }

    private WriteResult write(UUID userId, UUID workoutId, UUID version, UUID base, Runnable apply) {
        return Objects.requireNonNull(transactions.execute(status -> {
            if (!photos.lockWorkout(userId, workoutId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            var current = photos.find(userId, workoutId).map(photo -> photo.metadata().version()).orElse(null);
            if (current != null && photos.seenOperation(userId, workoutId, version)) {
                return new WriteResult(current, true);
            }
            if (current != null && !current.equals(base)) return new WriteResult(current, false);
            apply.run();
            photos.recordOperation(userId, workoutId, version);
            return new WriteResult(version, true);
        }));
    }

    @Scheduled(fixedDelay = REMOVAL_INTERVAL_MS)
    public void removeObjects() {
        try {
            transactions.executeWithoutResult(status -> photos.nextRemovals(REMOVAL_BATCH_SIZE).forEach(key -> {
                storage.delete(key);
                photos.finishRemoval(key);
            }));
        } catch (RuntimeException e) {
            removalFailures.increment();
        }
    }

    private static Dimensions jpegDimensions(byte[] bytes) {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalidPhoto();
            var reader = readers.next();
            try {
                if (!reader.getFormatName().toLowerCase(Locale.ROOT).equals("jpeg")) throw invalidPhoto();
                reader.setInput(stream, true, true);
                var width = reader.getWidth(0);
                var height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) throw tooLarge();
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw invalidPhoto();
        }
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Photo exceeds the size limit");
    }

    private static ResponseStatusException invalidPhoto() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Invalid JPEG photo");
    }

    private record Dimensions(int width, int height) {
    }
}
