package com.sizylift.backend.imports.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.imports.dto.ImportDtos.Draft;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.entity.ImportSource;
import com.sizylift.backend.imports.entity.ImportStatus;
import com.sizylift.backend.imports.entity.Round;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Сессии ИИ-импорта. Каждая операция — отдельный короткий запрос: вызов модели идёт вне транзакции. */
@Repository
public class ImportSessionRepository {

    private static final String INSERT = """
            INSERT INTO sizylift.import_session (user_id, target_owner_id, source, input, start_date, status,
                expires_at)
            VALUES (?, ?, ?, ?, ?, 'pending', ?)
            RETURNING id
            """;
    private static final String SELECT = """
            SELECT id, user_id, target_owner_id, input, start_date, status, rounds, expires_at
            FROM sizylift.import_session WHERE id = ?
            """;
    private static final String CLAIM_FOR_ANSWERS = """
            UPDATE sizylift.import_session SET status = 'pending'
            WHERE id = ? AND user_id = ? AND status = 'questions' AND expires_at > now()
            """;
    private static final String FINISH = """
            UPDATE sizylift.import_session
            SET status = ?, rounds = ?::jsonb, draft = ?::jsonb, tokens_in = tokens_in + ?, tokens_out = tokens_out + ?
            WHERE id = ?
            """;
    private static final String CREATED_SINCE =
            "SELECT count(*) FROM sizylift.import_session WHERE user_id = ? AND created_at > ?";
    private static final String DELETE_EXPIRED = "DELETE FROM sizylift.import_session WHERE expires_at < now()";
    private static final TypeReference<List<Round>> ROUNDS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ImportSessionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID create(UUID userId, UUID targetOwnerId, ImportSource source, String input, LocalDate startDate,
                       Instant expiresAt) {
        return jdbc.queryForObject(INSERT, UUID.class, userId, targetOwnerId, source.code(), input, startDate,
                expiresAt.atOffset(ZoneOffset.UTC));
    }

    public Optional<ImportSession> find(UUID id) {
        return jdbc.query(SELECT, this::session, id).stream().findFirst();
    }

    /** Переводит сессию в {@code pending}, если она ждёт ответов; так два параллельных ответа не пройдут оба. */
    public boolean claimForAnswers(UUID id, UUID userId) {
        return jdbc.update(CLAIM_FOR_ANSWERS, id, userId) == 1;
    }

    public void finish(UUID id, ImportStatus status, List<Round> rounds, Draft draft, int tokensIn, int tokensOut) {
        jdbc.update(FINISH, status.code(), json.writeValueAsString(rounds),
                draft == null ? null : json.writeValueAsString(draft), tokensIn, tokensOut, id);
    }

    public long createdSince(UUID userId, Instant since) {
        return jdbc.queryForObject(CREATED_SINCE, Long.class, userId, since.atOffset(ZoneOffset.UTC));
    }

    public int deleteExpired() {
        return jdbc.update(DELETE_EXPIRED);
    }

    private ImportSession session(ResultSet row, int rowNumber) throws SQLException {
        return new ImportSession(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getObject("target_owner_id", UUID.class), row.getString("input"),
                row.getObject("start_date", LocalDate.class), ImportStatus.of(row.getString("status")),
                json.readValue(row.getString("rounds"), ROUNDS),
                row.getObject("expires_at", OffsetDateTime.class).toInstant());
    }
}
