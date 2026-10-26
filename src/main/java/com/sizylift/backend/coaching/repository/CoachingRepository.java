package com.sizylift.backend.coaching.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.coaching.entity.CoachingInvite;
import com.sizylift.backend.coaching.entity.CoachingLink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Режим тренера, приглашения и связи. */
@Repository
public class CoachingRepository {

    private static final String LINK_COLUMNS =
            "id, coach_user_id, athlete_user_id, health_access, created_at, ended_at";
    private static final String SET_COACH_MODE = "UPDATE sizylift.user_account SET coach_enabled = ? WHERE id = ?";
    private static final String COACH_MODE = "SELECT coach_enabled FROM sizylift.user_account WHERE id = ?";
    private static final String LOCK_ACCOUNT = "SELECT id FROM sizylift.user_account WHERE id = ? FOR UPDATE";
    private static final String INSERT_INVITE = """
            INSERT INTO sizylift.coaching_invite (coach_user_id, token_hash, expires_at) VALUES (?, ?, ?)
            """;
    private static final String SELECT_INVITE = """
            SELECT id, coach_user_id, expires_at, accepted_at, revoked_at FROM sizylift.coaching_invite WHERE token_hash = ?
            """;
    private static final String ACCEPT_INVITE = """
            UPDATE sizylift.coaching_invite SET accepted_at = now(), accepted_by = ? WHERE id = ?
            """;
    private static final String INSERT_LINK = """
            INSERT INTO sizylift.coaching_link (coach_user_id, athlete_user_id) VALUES (?, ?)
            RETURNING %s
            """.formatted(LINK_COLUMNS);
    private static final String SELECT_LINK = "SELECT %s FROM sizylift.coaching_link WHERE id = ?"
            .formatted(LINK_COLUMNS);
    private static final String END_LINK = "UPDATE sizylift.coaching_link SET ended_at = now() WHERE id = ?";
    private static final String SET_HEALTH_ACCESS =
            "UPDATE sizylift.coaching_link SET health_access = ? WHERE id = ?";
    private static final String ACTIVE_AS_COACH = """
            SELECT %s FROM sizylift.coaching_link WHERE coach_user_id = ? AND ended_at IS NULL ORDER BY created_at
            """.formatted(LINK_COLUMNS);
    private static final String ACTIVE_AS_ATHLETE = """
            SELECT %s FROM sizylift.coaching_link WHERE athlete_user_id = ? AND ended_at IS NULL
            """.formatted(LINK_COLUMNS);
    private static final String DISPLAY_NAME =
            "SELECT display_name FROM sizylift.athlete_profile WHERE user_id = ?";

    private static final String PENDING_INVITES = """
            SELECT id, created_at, expires_at FROM sizylift.coaching_invite
            WHERE coach_user_id = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()
            ORDER BY created_at DESC
            """;
    private static final String REVOKE_INVITE = """
            UPDATE sizylift.coaching_invite SET revoked_at = coalesce(revoked_at, now())
            WHERE coach_user_id = ? AND id = ? AND accepted_at IS NULL
            """;

    public record PendingInvite(UUID id, Instant createdAt, Instant expiresAt) {
    }

    public List<PendingInvite> pendingInvites(UUID coachId) {
        return jdbc.query(PENDING_INVITES, (row, n) -> new PendingInvite(row.getObject("id", UUID.class),
                instant(row, "created_at"), instant(row, "expires_at")), coachId);
    }

    public boolean revokeInvite(UUID coachId, UUID id) {
        return jdbc.update(REVOKE_INVITE, coachId, id) > 0;
    }

    private final JdbcTemplate jdbc;

    public CoachingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setCoachMode(UUID userId, boolean enabled) {
        jdbc.update(SET_COACH_MODE, enabled, userId);
    }

    public boolean coachEnabled(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(COACH_MODE, Boolean.class, userId));
    }

    /**
     * Исключительная блокировка аккаунта подопечного до конца транзакции. Запись его данных берёт разделяемую,
     * поэтому создание и разрыв связи не пересекаются с фан-аутом.
     */
    public void lockAccount(UUID userId) {
        jdbc.query(LOCK_ACCOUNT, (row, n) -> null, userId);
    }

    public void insertInvite(UUID coachId, byte[] tokenHash, Instant expiresAt) {
        jdbc.update(INSERT_INVITE, coachId, tokenHash, expiresAt.atOffset(ZoneOffset.UTC));
    }

    public Optional<CoachingInvite> findInvite(byte[] tokenHash) {
        return jdbc.query(SELECT_INVITE, CoachingRepository::invite, tokenHash).stream().findFirst();
    }

    public Optional<CoachingInvite> lockInvite(byte[] tokenHash) {
        return jdbc.query(SELECT_INVITE + " FOR UPDATE", CoachingRepository::invite, tokenHash).stream().findFirst();
    }

    public void acceptInvite(UUID inviteId, UUID athleteId) {
        jdbc.update(ACCEPT_INVITE, athleteId, inviteId);
    }

    public CoachingLink insertLink(UUID coachId, UUID athleteId) {
        return jdbc.queryForObject(INSERT_LINK, CoachingRepository::link, coachId, athleteId);
    }

    public Optional<CoachingLink> findLink(UUID linkId) {
        return jdbc.query(SELECT_LINK, CoachingRepository::link, linkId).stream().findFirst();
    }

    public CoachingLink lockLink(UUID linkId) {
        return jdbc.queryForObject(SELECT_LINK + " FOR UPDATE", CoachingRepository::link, linkId);
    }

    public void endLink(UUID linkId) {
        jdbc.update(END_LINK, linkId);
    }

    public void setHealthAccess(UUID linkId, boolean enabled) {
        jdbc.update(SET_HEALTH_ACCESS, enabled, linkId);
    }

    public List<CoachingLink> activeAsCoach(UUID coachId) {
        return jdbc.query(ACTIVE_AS_COACH, CoachingRepository::link, coachId);
    }

    public Optional<CoachingLink> activeAsAthlete(UUID athleteId) {
        return jdbc.query(ACTIVE_AS_ATHLETE, CoachingRepository::link, athleteId).stream().findFirst();
    }

    /** Имя из профиля спортсмена; {@code null}, если профиль не опубликован. */
    public String displayName(UUID userId) {
        return jdbc.query(DISPLAY_NAME, (row, n) -> row.getString(1), userId).stream().findFirst().orElse(null);
    }

    private static CoachingInvite invite(ResultSet row, int rowNumber) throws SQLException {
        return new CoachingInvite(row.getObject("id", UUID.class), row.getObject("coach_user_id", UUID.class),
                instant(row, "expires_at"), instant(row, "accepted_at"), instant(row, "revoked_at"));
    }

    private static CoachingLink link(ResultSet row, int rowNumber) throws SQLException {
        return new CoachingLink(row.getObject("id", UUID.class), row.getObject("coach_user_id", UUID.class),
                row.getObject("athlete_user_id", UUID.class), row.getBoolean("health_access"),
                instant(row, "created_at"), instant(row, "ended_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
