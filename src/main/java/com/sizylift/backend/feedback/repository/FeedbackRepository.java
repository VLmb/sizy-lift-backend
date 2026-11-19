package com.sizylift.backend.feedback.repository;

import java.util.UUID;

import com.sizylift.backend.feedback.dto.FeedbackRequest.Source;
import com.sizylift.backend.feedback.entity.FeedbackAuthor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {
    private static final String INSERT = """
            INSERT INTO sizylift.product_feedback (source, message, account_id, email, username, name)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """;
    private static final String UPDATE_MAIL_STATUS = """
            UPDATE sizylift.product_feedback SET mail_status = ? WHERE id = ?
            """;

    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID save(Source source, String message, FeedbackAuthor author) {
        return jdbc.queryForObject(INSERT, UUID.class,
                source.name(), message, author.accountId(), author.email(), author.username(), author.name());
    }

    public void updateMailStatus(UUID id, MailStatus status) {
        jdbc.update(UPDATE_MAIL_STATUS, status.name(), id);
    }

    public enum MailStatus {
        SENT, FAILED
    }
}
