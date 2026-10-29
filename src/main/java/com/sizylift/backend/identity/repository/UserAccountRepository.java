package com.sizylift.backend.identity.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.identity.entity.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {

    private static final String COLUMNS = "id, issuer, subject, email, email_verified, created_at, updated_at";
    private static final RowMapper<UserAccount> MAPPER = UserAccountRepository::map;

    private static final String TOUCH = """
            UPDATE sizylift.user_account SET last_seen_at = now()
            WHERE id = ? AND (last_seen_at IS NULL OR last_seen_at < now() - interval '1 minute')
            """;
    private final JdbcTemplate jdbc;

    public UserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByIssuerAndSubject(String issuer, String subject) {
        return jdbc.query("SELECT %s FROM sizylift.user_account WHERE issuer = ? AND subject = ?".formatted(COLUMNS),
                MAPPER, issuer, subject).stream().findFirst();
    }

    /** Вставка без гонки: параллельные первые запросы пользователя создают ровно одну строку. */
    public boolean insertIfAbsent(String issuer, String subject, String email, boolean emailVerified) {
        return jdbc.update("""
                INSERT INTO sizylift.user_account (issuer, subject, email, email_verified)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (issuer, subject) DO NOTHING
                """, issuer, subject, email, emailVerified) == 1;
    }

    /** Обновляет контакты, только если они изменились; иначе возвращает пустой результат. */
    public Optional<UserAccount> updateContact(UserAccount account, String email, boolean emailVerified) {
        return jdbc.query("""
                UPDATE sizylift.user_account
                SET email = ?, email_verified = ?, updated_at = now()
                WHERE id = ? AND (email IS DISTINCT FROM ? OR email_verified <> ?)
                RETURNING %s
                """.formatted(COLUMNS),
                MAPPER, email, emailVerified, account.id(), email, emailVerified).stream().findFirst();
    }

    public void touch(java.util.UUID accountId) {
        jdbc.update(TOUCH, accountId);
    }

    private static UserAccount map(ResultSet row, int rowNumber) throws SQLException {
        return new UserAccount(
                row.getObject("id", UUID.class),
                row.getString("issuer"),
                row.getString("subject"),
                row.getString("email"),
                row.getBoolean("email_verified"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
