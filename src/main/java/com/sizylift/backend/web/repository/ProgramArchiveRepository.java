package com.sizylift.backend.web.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Снимок программы при архивации из веба; активная копия удаляется через sync. */
@Repository
public class ProgramArchiveRepository {
    private static final String SAVE = """
            INSERT INTO sizylift.web_program_archive (user_id, program_id, author_user_id, payload, deleted_revision)
            VALUES (?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (user_id, program_id) DO UPDATE
            SET author_user_id = excluded.author_user_id, payload = excluded.payload, deleted_revision = excluded.deleted_revision, archived_at = now()
            """;
    private static final String LIST = """
            SELECT ar.program_id, a.author_user_id, ar.payload::text, ar.deleted_revision FROM sizylift.web_program_archive ar
            JOIN sizylift.published_aggregate a ON a.user_id = ar.user_id AND a.type = 'program'
                AND a.aggregate_id = ar.program_id AND a.deleted AND a.revision = ar.deleted_revision
            WHERE ar.user_id = ? ORDER BY ar.archived_at DESC
            """;
    private static final String FIND = """
            SELECT program_id, author_user_id, payload::text, deleted_revision FROM sizylift.web_program_archive
            WHERE user_id = ? AND program_id = ? FOR UPDATE
            """;
    private static final String HEADS = """
            SELECT ar.program_id, ar.deleted_revision FROM sizylift.web_program_archive ar
            JOIN sizylift.published_aggregate a ON a.user_id = ar.user_id AND a.type = 'program'
                AND a.aggregate_id = ar.program_id AND a.deleted AND a.revision = ar.deleted_revision
            WHERE ar.user_id = ?
            """;
    public Map<UUID, Long> heads(UUID owner) {
        return jdbc.query(HEADS, (row, n) -> Map.entry(row.getObject(1, UUID.class), row.getLong(2)), owner)
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    private static final String TRANSFER = """
            UPDATE sizylift.web_program_archive SET author_user_id = NULL, deleted_revision = ?
            WHERE user_id = ? AND program_id = ? AND deleted_revision = ?
            """;
    public void transfer(UUID owner, UUID id, long oldRevision, long revision) {
        jdbc.update(TRANSFER, revision, owner, id, oldRevision);
    }
    private static final String DELETE = "DELETE FROM sizylift.web_program_archive WHERE user_id = ? AND program_id = ?";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProgramArchiveRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record ArchivedProgram(UUID id, UUID authorId, ProgramPayloadV1 program, long deletedRevision) {
    }

    public void save(UUID owner, UUID id, UUID author, ProgramPayloadV1 program, long deletedRevision) {
        jdbc.update(SAVE, owner, id, author, json.writeValueAsString(program), deletedRevision);
    }

    public List<ArchivedProgram> list(UUID owner) {
        return jdbc.query(LIST, (row, n) -> new ArchivedProgram(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), json.readValue(row.getString(3), ProgramPayloadV1.class), row.getLong(4)), owner);
    }

    public Optional<ArchivedProgram> lock(UUID owner, UUID id) {
        return jdbc.query(FIND, (row, n) -> new ArchivedProgram(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), json.readValue(row.getString(3), ProgramPayloadV1.class), row.getLong(4)), owner, id)
                .stream().findFirst();
    }

    public void remove(UUID owner, UUID id) {
        jdbc.update(DELETE, owner, id);
    }
}
