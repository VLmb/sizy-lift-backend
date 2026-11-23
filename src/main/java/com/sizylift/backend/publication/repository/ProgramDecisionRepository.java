package com.sizylift.backend.publication.repository;

import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.ProgramDecisionPayloadV1;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.sizylift.backend.publication.repository.Sql.first;
import static com.sizylift.backend.publication.repository.Sql.instant;
import static com.sizylift.backend.publication.repository.Sql.timestamp;

@Repository
public class ProgramDecisionRepository {

    private static final String INSERT = """
            INSERT INTO sizylift.coach_program_decision (user_id, program_id, decision, updated_at)
            VALUES (?, ?, ?, ?)
            """;
    private static final String SELECT = """
            SELECT decision, updated_at FROM sizylift.coach_program_decision
            WHERE user_id = ? AND program_id = ?
            """;
    private static final String DELETE = """
            DELETE FROM sizylift.coach_program_decision WHERE user_id = ? AND program_id = ?
            """;
    private static final String KEEP_UNACCEPTED_AS_DRAFT = """
            UPDATE sizylift.program p SET status = 'draft'
            FROM sizylift.coach_program_decision d
            WHERE p.user_id = ? AND p.id = ? AND d.user_id = p.user_id AND d.program_id = p.id
              AND d.decision <> 'accepted'
            """;

    private final JdbcTemplate jdbc;

    ProgramDecisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID owner, UUID id, ProgramDecisionPayloadV1 payload) {
        jdbc.update(INSERT, owner, id, payload.decision(), timestamp(payload.updatedAt()));
    }

    public Optional<ProgramDecisionPayloadV1> find(UUID owner, UUID id) {
        return first(jdbc.query(SELECT, (row, n) ->
                new ProgramDecisionPayloadV1(row.getString("decision"), instant(row, "updated_at")), owner, id));
    }

    public boolean delete(UUID owner, UUID id) {
        return jdbc.update(DELETE, owner, id) != 0;
    }

    public void keepUnacceptedAsDraft(UUID owner, UUID id) {
        jdbc.update(KEEP_UNACCEPTED_AS_DRAFT, owner, id);
    }
}
