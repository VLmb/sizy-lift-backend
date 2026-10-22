package com.sizylift.backend.access.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.access.entity.CoachingRelation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Чтение активных связей и блокировка владельца данных для политики доступа. */
@Repository
public class AccessRepository {

    private static final String LOCK_OWNER_SHARED =
            "SELECT id FROM sizylift.user_account WHERE id = ? FOR SHARE";
    private static final String COACH_OF = """
            SELECT coach_user_id, athlete_user_id, health_access FROM sizylift.coaching_link
            WHERE athlete_user_id = ? AND ended_at IS NULL
            """;
    private static final String ATHLETES_OF = """
            SELECT coach_user_id, athlete_user_id, health_access FROM sizylift.coaching_link
            WHERE coach_user_id = ? AND ended_at IS NULL
            """;

    private final JdbcTemplate jdbc;

    public AccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Разделяемая блокировка аккаунта владельца до конца транзакции. Изменения связей берут исключительную,
     * поэтому запись данных и создание или разрыв связи не идут одновременно.
     */
    public void lockOwnerShared(UUID ownerId) {
        jdbc.query(LOCK_OWNER_SHARED, (row, n) -> null, ownerId);
    }

    public Optional<CoachingRelation> activeCoachOf(UUID athleteId) {
        return jdbc.query(COACH_OF, AccessRepository::relation, athleteId).stream().findFirst();
    }

    public List<CoachingRelation> activeAthletesOf(UUID coachId) {
        return jdbc.query(ATHLETES_OF, AccessRepository::relation, coachId);
    }

    private static CoachingRelation relation(ResultSet row, int rowNumber) throws SQLException {
        return new CoachingRelation(row.getObject("coach_user_id", UUID.class),
                row.getObject("athlete_user_id", UUID.class), row.getBoolean("health_access"));
    }
}
