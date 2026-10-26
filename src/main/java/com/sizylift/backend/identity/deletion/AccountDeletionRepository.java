package com.sizylift.backend.identity.deletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.identity.entity.UserAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountDeletionRepository {
    private static final String LOCK_ACCOUNT = "SELECT id FROM sizylift.user_account WHERE id = ? FOR UPDATE";
    public void lockAccount(UUID id) { jdbc.query(LOCK_ACCOUNT, (row, n) -> null, id); }
    private static final String LOCK_IDENTITY = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
    public void lockIdentity(String issuer, String subject) {
        jdbc.query(LOCK_IDENTITY, (row, n) -> null, issuer + "\n" + subject);
    }
    private static final String RETIRED = "SELECT EXISTS (SELECT 1 FROM sizylift.retired_identity WHERE fingerprint = ?)";
    private static final String RETIRE = "INSERT INTO sizylift.retired_identity (fingerprint) VALUES (?) ON CONFLICT DO NOTHING";
    private static final String QUEUE = "INSERT INTO sizylift.account_deletion (id, issuer, subject) VALUES (?, ?, ?)";
    private static final String DELETE_ACCOUNT = "DELETE FROM sizylift.user_account WHERE id = ?";
    private static final String NEXT = "SELECT id, issuer, subject FROM sizylift.account_deletion ORDER BY requested_at LIMIT 1 FOR UPDATE SKIP LOCKED";
    private static final String PENDING_COUNT = "SELECT count(*) FROM sizylift.account_deletion";
    public long pendingCount() { return jdbc.queryForObject(PENDING_COUNT, Long.class); }

    private static final String COMPLETE = "DELETE FROM sizylift.account_deletion WHERE id = ?";
    private final JdbcTemplate jdbc;

    public AccountDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean retired(String issuer, String subject) {
        return Boolean.TRUE.equals(jdbc.queryForObject(RETIRED, Boolean.class, fingerprint(issuer, subject)));
    }

    public void delete(UserAccount account) {
        lockIdentity(account.issuer(), account.subject());
        jdbc.update(RETIRE, fingerprint(account.issuer(), account.subject()));
        jdbc.update(QUEUE, account.id(), account.issuer(), account.subject());
        jdbc.update(DELETE_ACCOUNT, account.id());
    }

    public record Pending(UUID id, String issuer, String subject) {
    }

    public Optional<Pending> next() {
        return jdbc.query(NEXT, (row, n) -> new Pending(row.getObject(1, UUID.class), row.getString(2), row.getString(3)))
                .stream().findFirst();
    }

    public void complete(UUID id) {
        jdbc.update(COMPLETE, id);
    }

    private static String fingerprint(String issuer, String subject) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((issuer + "\n" + subject).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
