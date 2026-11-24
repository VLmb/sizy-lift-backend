package com.sizylift.backend.photo.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.photo.dto.WorkoutPhotoDtos.Metadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkoutPhotoRepository {

    private static final String WORKOUT_EXISTS = """
            SELECT EXISTS (SELECT 1 FROM sizylift.workout WHERE user_id = ? AND id = ?)
            """;
    private static final String LOCK_WORKOUT = """
            SELECT id FROM sizylift.workout WHERE user_id = ? AND id = ? FOR UPDATE
            """;
    private static final String FIND = """
            SELECT * FROM sizylift.workout_photo WHERE user_id = ? AND workout_id = ?
            """;
    private static final String FIRST_PAGE = """
            SELECT * FROM sizylift.workout_photo WHERE user_id = ? ORDER BY workout_id LIMIT ?
            """;
    private static final String NEXT_PAGE = """
            SELECT * FROM sizylift.workout_photo WHERE user_id = ? AND workout_id > ? ORDER BY workout_id LIMIT ?
            """;
    private static final String SAVE = """
            INSERT INTO sizylift.workout_photo (user_id, workout_id, version, object_key, width, height, byte_size)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, workout_id) DO UPDATE SET version = EXCLUDED.version, object_key = EXCLUDED.object_key,
                width = EXCLUDED.width, height = EXCLUDED.height, byte_size = EXCLUDED.byte_size, updated_at = now()
            """;
    private static final String RECORD_OPERATION = """
            INSERT INTO sizylift.workout_photo_operation (user_id, workout_id, version) VALUES (?, ?, ?)
            """;
    private static final String SEEN_OPERATION = """
            SELECT EXISTS (SELECT 1 FROM sizylift.workout_photo_operation
                           WHERE user_id = ? AND workout_id = ? AND version = ?)
            """;
    private static final String REMOVE_WITH_WORKOUT = """
            DELETE FROM sizylift.workout_photo WHERE user_id = ? AND workout_id = ?
            """;
    private static final String QUEUE_REMOVAL = """
            INSERT INTO sizylift.workout_photo_removal (object_key, due_at) VALUES (?, ?)
            """;
    private static final String LOCK_RESERVATION = """
            SELECT object_key FROM sizylift.workout_photo_removal WHERE object_key = ? FOR UPDATE
            """;
    private static final String NEXT_REMOVALS = """
            SELECT object_key FROM sizylift.workout_photo_removal WHERE due_at <= now()
            ORDER BY due_at, object_key LIMIT ? FOR UPDATE SKIP LOCKED
            """;
    private static final String FINISH_REMOVAL = """
            DELETE FROM sizylift.workout_photo_removal WHERE object_key = ?
            """;

    private final JdbcTemplate jdbc;

    public WorkoutPhotoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean workoutExists(UUID userId, UUID workoutId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(WORKOUT_EXISTS, Boolean.class, userId, workoutId));
    }

    public boolean lockWorkout(UUID userId, UUID workoutId) {
        return !jdbc.query(LOCK_WORKOUT, (row, index) -> row.getObject(1, UUID.class), userId, workoutId).isEmpty();
    }

    public Optional<Photo> find(UUID userId, UUID workoutId) {
        return jdbc.query(FIND, WorkoutPhotoRepository::photo, userId, workoutId).stream().findFirst();
    }

    public List<Metadata> page(UUID userId, UUID after, int limit) {
        return after == null
                ? jdbc.query(FIRST_PAGE, (row, index) -> photo(row, index).metadata(), userId, limit)
                : jdbc.query(NEXT_PAGE, (row, index) -> photo(row, index).metadata(), userId, after, limit);
    }

    public void save(UUID userId, UUID workoutId, UUID version, String key, Integer width, Integer height,
                     Integer byteSize) {
        jdbc.update(SAVE, userId, workoutId, version, key, width, height, byteSize);
    }

    public void recordOperation(UUID userId, UUID workoutId, UUID version) {
        jdbc.update(RECORD_OPERATION, userId, workoutId, version);
    }

    public boolean seenOperation(UUID userId, UUID workoutId, UUID version) {
        return Boolean.TRUE.equals(jdbc.queryForObject(SEEN_OPERATION, Boolean.class, userId, workoutId, version));
    }

    public void removeWithWorkout(UUID userId, UUID workoutId) {
        jdbc.update(REMOVE_WITH_WORKOUT, userId, workoutId);
    }

    public void queueRemoval(String key, Instant dueAt) {
        jdbc.update(QUEUE_REMOVAL, key, Timestamp.from(dueAt));
    }

    public boolean lockReservation(String key) {
        return !jdbc.queryForList(LOCK_RESERVATION, String.class, key).isEmpty();
    }

    public List<String> nextRemovals(int limit) {
        return jdbc.queryForList(NEXT_REMOVALS, String.class, limit);
    }

    public void finishRemoval(String key) {
        jdbc.update(FINISH_REMOVAL, key);
    }

    private static Photo photo(ResultSet row, int index) throws SQLException {
        var key = row.getString("object_key");
        return new Photo(key, new Metadata(row.getObject("workout_id", UUID.class),
                row.getObject("version", UUID.class), key == null, row.getObject("width", Integer.class),
                row.getObject("height", Integer.class), row.getObject("byte_size", Integer.class),
                row.getTimestamp("updated_at").toInstant()));
    }

    public record Photo(String objectKey, Metadata metadata) {
    }
}
