package com.sizylift.backend.imports.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportDtos.Create;
import com.sizylift.backend.imports.dto.ImportDtos.Decision;
import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.entity.ImportSession.Source;
import com.sizylift.backend.web.dto.WebDtos.CatalogExercise;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Все изменения worker проверяют аренду, чтобы поздний ответ не воскресил отменённую сессию. */
@Repository
public class ImportRepository {
    private static final String BY_ID = "SELECT * FROM sizylift.import_session WHERE id = ?";
    private static final String LOCK = BY_ID + " FOR UPDATE";
    private static final String BY_REQUEST = "SELECT * FROM sizylift.import_session WHERE user_id = ? AND request_id = ?";
    private static final String ADMISSION_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String INSERT = """
            INSERT INTO sizylift.import_session(id,user_id,owner_id,request_id,request_hash,status,source_kind,
                source_text,source_bytes,source_name,catalog,name,start_date,program_id,created_at,expires_at)
            VALUES (?,?,?,?,?,'queued',?,?,?,?,?::jsonb,?,?,?,?,?)
            """;
    private static final String ACTIVE = """
            SELECT count(*) FROM sizylift.import_session WHERE user_id = ? AND expires_at > ?
              AND status IN ('queued','processing','clarification_required','review')
            """;
    private static final String DAILY = "SELECT count(*) FROM sizylift.import_session WHERE user_id = ? AND created_at > ?";
    private static final String CLAIM_COUNT = """
            SELECT count(*) FROM sizylift.import_session WHERE status = 'processing' AND lease_until > ? AND expires_at > ?
            """;
    private static final String CLAIM_SELECT = """
            SELECT * FROM sizylift.import_session WHERE expires_at > ? AND next_attempt_at <= ?
              AND (status = 'queued' OR (status = 'processing' AND lease_until <= ?))
            ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """;
    private static final String CLAIM = """
            UPDATE sizylift.import_session SET status='processing', version=version+1, lease_token=?, lease_until=? WHERE id=?
            """;
    private static final String NORMALIZE = """
            UPDATE sizylift.import_session SET normalized=?::jsonb,source_bytes=NULL,source_text=NULL
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String RESERVE = """
            UPDATE sizylift.import_session SET attempts=attempts+1,reserved_cost_micros=reserved_cost_micros+?
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
              AND attempts < ? AND reserved_cost_micros + ? <= ?
            """;
    private static final String FINISH = """
            UPDATE sizylift.import_session SET status=?,version=version+1,model_output=?::jsonb,decisions=?::jsonb,
                lease_token=NULL,lease_until=NULL,error_code=?,error_message=?,repair_hint=NULL,outline=NULL,
                input_tokens=input_tokens+?,output_tokens=output_tokens+?,cached_tokens=cached_tokens+?
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String RETRY = """
            UPDATE sizylift.import_session SET status='queued',version=version+1,lease_token=NULL,lease_until=NULL,
                next_attempt_at=?,error_code='retrying',error_message='Повторяем обработку.',repair_hint=?
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String OUTLINE = """
            UPDATE sizylift.import_session SET outline=?::jsonb
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String EXTEND = """
            UPDATE sizylift.import_session SET lease_until=?
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String RESERVE_CHUNK = """
            UPDATE sizylift.import_session SET reserved_cost_micros=reserved_cost_micros+?
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ? AND reserved_cost_micros + ? <= ?
            """;
    private static final String USAGE = """
            UPDATE sizylift.import_session SET input_tokens=input_tokens+?,output_tokens=output_tokens+?,cached_tokens=cached_tokens+?
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String ANSWER = """
            UPDATE sizylift.import_session SET status='queued',version=version+1,round=round+1,attempts=0,
                history=?::jsonb,error_code=NULL,error_message=NULL,repair_hint=NULL,outline=NULL,next_attempt_at=? WHERE id=?
            """;
    private static final String PATCH = """
            UPDATE sizylift.import_session SET version=version+1,name=?,start_date=?,decisions=?::jsonb WHERE id=?
            """;
    private static final String COMMIT = """
            UPDATE sizylift.import_session SET status='committed',version=version+1,program_revision=?,
                source_text=NULL,source_bytes=NULL,source_name='',name='',normalized=NULL,catalog='[]',
                model_output=NULL,decisions='{}',history='[]',lease_token=NULL,lease_until=NULL,
                error_code=NULL,error_message=NULL,repair_hint=NULL,outline=NULL WHERE id=?
            """;
    private static final String CANCEL = """
            UPDATE sizylift.import_session SET status='cancelled',version=version+1,source_text=NULL,source_bytes=NULL,name='',source_name='',
                normalized=NULL,catalog='[]',model_output=NULL,decisions='{}',history='[]',lease_token=NULL,
                lease_until=NULL,error_code=NULL,error_message=NULL,repair_hint=NULL,outline=NULL WHERE id=?
            """;
    private static final String EXPIRE = """
            UPDATE sizylift.import_session SET status='expired',version=version+1,source_text=NULL,source_bytes=NULL,
                source_name='',name='',normalized=NULL,catalog='[]',model_output=NULL,decisions='{}',history='[]',
                lease_token=NULL,lease_until=NULL,error_code=NULL,error_message=NULL,repair_hint=NULL,outline=NULL
            WHERE expires_at <= ? AND status NOT IN ('committed','expired','cancelled')
            """;
    private static final String PURGE = """
            DELETE FROM sizylift.import_session WHERE expires_at < ? AND status IN ('committed','expired','cancelled')
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ImportRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void lockAdmission(UUID user) { jdbc.queryForObject(ADMISSION_LOCK, Object.class, "import:user:" + user); }
    public void lockQueue() { jdbc.queryForObject(ADMISSION_LOCK, Object.class, "import:queue"); }
    public Optional<ImportSession> find(UUID id) { return jdbc.query(BY_ID, this::row, id).stream().findFirst(); }
    public Optional<ImportSession> lock(UUID id) { return jdbc.query(LOCK, this::row, id).stream().findFirst(); }
    public Optional<ImportSession> byRequest(UUID user, UUID request) {
        return jdbc.query(BY_REQUEST, this::row, user, request).stream().findFirst();
    }
    public int active(UUID user, Instant now) { return jdbc.queryForObject(ACTIVE, Integer.class, user, stamp(now)); }
    public int createdSince(UUID user, Instant since) { return jdbc.queryForObject(DAILY, Integer.class, user, stamp(since)); }

    public ImportSession create(UUID user, UUID owner, Create input, byte[] bytes, String sourceName,
                                String hash, List<CatalogExercise> catalog, Instant now, ImportProperties limits) {
        var id = UUID.randomUUID();
        jdbc.update(INSERT, id, user, owner, input.requestId(), hash, input.source(),
                input.source().equals("sheet") ? input.url() : input.text(), bytes, sourceName,
                json.writeValueAsString(catalog), input.name() == null ? "Импортированная программа" : input.name().trim(),
                input.startDate(), UUID.randomUUID(), stamp(now), stamp(now.plus(limits.retention())));
        return find(id).orElseThrow();
    }

    /** Вызывается в короткой транзакции под общей блокировкой очереди. */
    public Optional<ImportSession> claim(Instant now, ImportProperties limits) {
        lockQueue();
        if (jdbc.queryForObject(CLAIM_COUNT, Integer.class, stamp(now), stamp(now)) >= limits.concurrentRequests())
            return Optional.empty();
        var next = jdbc.query(CLAIM_SELECT, this::row, stamp(now), stamp(now), stamp(now)).stream().findFirst();
        if (next.isEmpty()) return next;
        var id = next.get().id();
        jdbc.update(CLAIM, UUID.randomUUID(), stamp(now.plus(limits.lease())), id);
        return find(id);
    }

    public boolean normalized(ImportSession session, Source source, Instant now) {
        return jdbc.update(NORMALIZE, json.writeValueAsString(source), session.id(), session.leaseToken(), stamp(now)) == 1;
    }
    public boolean reserve(ImportSession session, long micros, Instant now, ImportProperties limits) {
        return jdbc.update(RESERVE, micros, session.id(), session.leaseToken(), stamp(now),
                limits.attemptsPerRound(), micros, limits.maxCostMicros()) == 1;
    }
    public boolean finish(ImportSession session, String status, ImportDraftV1 output, Map<String, Decision> decisions,
                           String code, String message, int input, int completion, int cached, Instant now) {
        return jdbc.update(FINISH, status, json.writeValueAsString(output), json.writeValueAsString(decisions),
                code, message, input, completion, cached, session.id(), session.leaseToken(), stamp(now)) == 1;
    }
    public void retry(ImportSession session, Instant next, Instant now, String repairHint) {
        jdbc.update(RETRY, stamp(next), repairHint, session.id(), session.leaseToken(), stamp(now));
    }
    public boolean outline(ImportSession session, ImportPlan plan, Instant now) {
        return jdbc.update(OUTLINE, json.writeValueAsString(plan), session.id(), session.leaseToken(), stamp(now)) == 1;
    }
    /** Продлевает аренду перед каждым вызовом модели по частям; false — сессию отменили или забрали. */
    public boolean extend(ImportSession session, Instant now, ImportProperties limits) {
        return jdbc.update(EXTEND, stamp(now.plus(limits.lease())), session.id(), session.leaseToken(), stamp(now)) == 1;
    }
    /** Резерв бюджета вызова по части: попытки раунда не расходует, их ограничивает разбор частей. */
    public boolean reserveChunk(ImportSession session, long micros, Instant now, ImportProperties limits) {
        return jdbc.update(RESERVE_CHUNK, micros, session.id(), session.leaseToken(), stamp(now), micros, limits.maxCostMicros()) == 1;
    }
    public void usage(ImportSession session, int input, int output, int cached, Instant now) {
        jdbc.update(USAGE, input, output, cached, session.id(), session.leaseToken(), stamp(now));
    }
    public void answer(UUID id, List<Clarification> history, Instant now) {
        jdbc.update(ANSWER, json.writeValueAsString(history), stamp(now), id);
    }
    public void patch(UUID id, String name, java.time.LocalDate startDate, Map<String, Decision> decisions) {
        jdbc.update(PATCH, name, startDate, json.writeValueAsString(decisions), id);
    }
    public void committed(UUID id, long revision) { jdbc.update(COMMIT, revision, id); }
    public void cancel(UUID id) { jdbc.update(CANCEL, id); }
    public void cleanup(Instant now) {
        jdbc.update(EXPIRE, stamp(now));
        jdbc.update(PURGE, stamp(now.minus(java.time.Duration.ofDays(30))));
    }

    private ImportSession row(ResultSet row, int index) throws SQLException {
        return new ImportSession(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getObject("owner_id", UUID.class), row.getObject("request_id", UUID.class), row.getString("request_hash"),
                row.getString("status"), row.getLong("version"), row.getString("source_kind"), row.getString("source_text"),
                row.getBytes("source_bytes"), row.getString("source_name"), read(row, "normalized", Source.class),
                read(row, "catalog", new TypeReference<>() { }), read(row, "model_output", ImportDraftV1.class),
                read(row, "decisions", new TypeReference<>() { }), read(row, "history", new TypeReference<>() { }),
                row.getString("name"), row.getObject("start_date", java.time.LocalDate.class),
                row.getObject("program_id", UUID.class), row.getObject("program_revision", Long.class),
                row.getInt("round"), row.getInt("attempts"), row.getLong("reserved_cost_micros"),
                row.getLong("input_tokens"), row.getLong("output_tokens"), row.getLong("cached_tokens"),
                row.getString("error_code"), row.getString("error_message"), row.getObject("lease_token", UUID.class),
                row.getTimestamp("expires_at").toInstant(), row.getString("repair_hint"),
                read(row, "outline", ImportPlan.class));
    }
    private <T> T read(ResultSet row, String field, Class<T> type) throws SQLException {
        var value = row.getString(field);
        return value == null || value.equals("null") ? null : json.readValue(value, type);
    }
    private <T> T read(ResultSet row, String field, TypeReference<T> type) throws SQLException {
        var value = row.getString(field);
        return value == null || value.equals("null") ? null : json.readValue(value, type);
    }
    private static java.sql.Timestamp stamp(Instant value) { return java.sql.Timestamp.from(value); }
}
