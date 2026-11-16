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
import com.sizylift.backend.imports.dto.ImportDtos.Failure;
import com.sizylift.backend.imports.dto.ImportDtos.Summary;
import com.sizylift.backend.imports.dto.ImportDtos.Progress;
import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.entity.ImportLanguage;
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
    private static final String LANGUAGE = "SELECT ui_language FROM sizylift.import_session WHERE id = ?";
    private static final String LOCK = BY_ID + " FOR UPDATE";
    private static final String BY_REQUEST = "SELECT * FROM sizylift.import_session WHERE user_id = ? AND request_id = ?";
    private static final String ADMISSION_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String INSERT = """
            INSERT INTO sizylift.import_session(id,user_id,owner_id,request_id,request_hash,status,source_kind,
                source_text,source_bytes,source_name,catalog,name,start_date,program_id,created_at,expires_at,ui_language)
            VALUES (?,?,?,?,?,'queued',?,?,?,?,?::jsonb,?,?,?,?,?,?)
            """;
    private static final String ACTIVE = """
            SELECT count(*) FROM sizylift.import_session WHERE user_id = ? AND expires_at > ?
              AND status IN ('queued','processing','sheet_selection','clarification_required','review')
            """;
    /** Незавершённые сессии пользователя для списка «вернуться к разбору», новые сверху. */
    private static final String SUMMARIES = """
            SELECT id, owner_id, status, version, name, ui_language, start_date, expires_at, progress, error_code, error_message,
                   model_output -> 'programDraft' ->> 'name' AS draft_name,
                   CASE WHEN status = 'review' THEN (
                       SELECT count(*) FROM jsonb_array_elements(coalesce(model_output -> 'exerciseResolutions', '[]'::jsonb)) r
                       WHERE decisions -> (r ->> 'key') IS NULL) ELSE 0 END AS open_choices
            FROM sizylift.import_session
            WHERE user_id = ? AND expires_at > ?
              AND status IN ('queued','processing','sheet_selection','clarification_required','review','failed')
            ORDER BY created_at DESC, id LIMIT ?
            """;
    /** Название из разбора, если пользователь не задал своё: у сессии осталось название по умолчанию. */
    private static final String ADOPT_NAME = "UPDATE sizylift.import_session SET name=? WHERE id=? AND name IN (?,?)";
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
                lease_token=NULL,lease_until=NULL,error_code=?,error_message=?,repair_hint=NULL,outline=NULL,progress=NULL,
                input_tokens=input_tokens+?,output_tokens=output_tokens+?,cached_tokens=cached_tokens+?
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String RETRY = """
            UPDATE sizylift.import_session SET status='queued',version=version+1,lease_token=NULL,lease_until=NULL,
                next_attempt_at=?,error_code='retrying',error_message='Повторяем обработку.',repair_hint=?,progress=NULL
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String OUTLINE = """
            UPDATE sizylift.import_session SET outline=?::jsonb
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String PARTIAL = """
            UPDATE sizylift.import_session SET model_output=?::jsonb,progress=?::jsonb
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String RETRY_WEEKS = """
            UPDATE sizylift.import_session SET status='queued',version=version+1,attempts=0,outline=?::jsonb,
                error_code=NULL,error_message=NULL,repair_hint=NULL,progress=NULL,next_attempt_at=? WHERE id=?
            """;
    private static final String AWAIT_SHEETS = """
            UPDATE sizylift.import_session SET status='sheet_selection',version=version+1,normalized=?::jsonb,
                source_bytes=NULL,source_text=NULL,lease_token=NULL,lease_until=NULL,progress=NULL
            WHERE id=? AND lease_token=? AND status='processing' AND expires_at > ?
            """;
    private static final String SELECT_SHEETS = """
            UPDATE sizylift.import_session SET status='queued',version=version+1,normalized=?::jsonb,sheets_selected=true,
                next_attempt_at=? WHERE id=?
            """;
    /** Отпечаток видимого состояния: меняется при любой записи статуса, версии, прогресса или черновика. */
    private static final String STAMPS = """
            SELECT id, status || ':' || version || ':' || md5(coalesce(progress::text, '') || coalesce(model_output::text, ''))
                AS stamp FROM sizylift.import_session WHERE id = ANY(?)
            """;
    private static final String PROGRESS = """
            UPDATE sizylift.import_session SET progress=?::jsonb
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
                history=?::jsonb,error_code=NULL,error_message=NULL,repair_hint=NULL,outline=NULL,progress=NULL,next_attempt_at=? WHERE id=?
            """;
    private static final String PATCH = """
            UPDATE sizylift.import_session SET version=version+1,name=?,start_date=?,decisions=?::jsonb WHERE id=?
            """;
    private static final String COMMIT = """
            UPDATE sizylift.import_session SET status='committed',version=version+1,program_revision=?,
                source_text=NULL,source_bytes=NULL,source_name='',name='',normalized=NULL,catalog='[]',
                model_output=NULL,decisions='{}',history='[]',lease_token=NULL,lease_until=NULL,
                error_code=NULL,error_message=NULL,repair_hint=NULL,outline=NULL,progress=NULL WHERE id=?
            """;
    private static final String CANCEL = """
            UPDATE sizylift.import_session SET status='cancelled',version=version+1,source_text=NULL,source_bytes=NULL,name='',source_name='',
                normalized=NULL,catalog='[]',model_output=NULL,decisions='{}',history='[]',lease_token=NULL,
                lease_until=NULL,error_code=NULL,error_message=NULL,repair_hint=NULL,outline=NULL,progress=NULL WHERE id=?
            """;
    private static final String EXPIRE = """
            UPDATE sizylift.import_session SET status='expired',version=version+1,source_text=NULL,source_bytes=NULL,
                source_name='',name='',normalized=NULL,catalog='[]',model_output=NULL,decisions='{}',history='[]',
                lease_token=NULL,lease_until=NULL,error_code=NULL,error_message=NULL,repair_hint=NULL,outline=NULL,progress=NULL
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
    public String language(UUID id) { return jdbc.queryForObject(LANGUAGE, String.class, id); }
    public Optional<ImportSession> find(UUID id) { return jdbc.query(BY_ID, this::row, id).stream().findFirst(); }
    public Optional<ImportSession> lock(UUID id) { return jdbc.query(LOCK, this::row, id).stream().findFirst(); }
    public Optional<ImportSession> byRequest(UUID user, UUID request) {
        return jdbc.query(BY_REQUEST, this::row, user, request).stream().findFirst();
    }
    public int active(UUID user, Instant now) { return jdbc.queryForObject(ACTIVE, Integer.class, user, stamp(now)); }
    public List<Summary> summaries(UUID user, Instant now, int limit) {
        return jdbc.query(SUMMARIES, this::summary, user, stamp(now), limit);
    }
    public void adoptName(UUID id, String name) {
        jdbc.update(ADOPT_NAME, name, id, ImportLanguage.RUSSIAN.defaultName(), ImportLanguage.ENGLISH.defaultName());
    }
    public int createdSince(UUID user, Instant since) { return jdbc.queryForObject(DAILY, Integer.class, user, stamp(since)); }

    public ImportSession create(UUID user, UUID owner, Create input, byte[] bytes, String sourceName,
                                String hash, List<CatalogExercise> catalog, Instant now, ImportProperties limits) {
        var id = UUID.randomUUID();
        jdbc.update(INSERT, id, user, owner, input.requestId(), hash, input.source(),
                input.source().equals("sheet") ? input.url() : input.text(), bytes, sourceName,
                json.writeValueAsString(catalog), input.name() == null ? ImportLanguage.of(input.language()).defaultName() : input.name().trim(),
                input.startDate(), UUID.randomUUID(), stamp(now), stamp(now.plus(limits.retention())), input.uiLanguage());
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
    public boolean progress(ImportSession session, Progress progress, Instant now) {
        return jdbc.update(PROGRESS, json.writeValueAsString(progress), session.id(), session.leaseToken(), stamp(now)) == 1;
    }
    /** Книга с несколькими листами: модель не вызывается, пока пользователь не выберет листы. */
    public boolean awaitSheets(ImportSession session, Source source, Instant now) {
        return jdbc.update(AWAIT_SHEETS, json.writeValueAsString(source), session.id(), session.leaseToken(), stamp(now)) == 1;
    }
    /** Остаются только выбранные листы; остальные стираются и в модель не уходят. */
    public void selectSheets(UUID id, Source source, Instant now) {
        jdbc.update(SELECT_SHEETS, json.writeValueAsString(source), stamp(now), id);
    }
    /** Отпечатки состояния сессий для потока событий; удалённых сессий в ответе нет. */
    public Map<UUID, String> stamps(java.util.Collection<UUID> ids) {
        var result = new java.util.HashMap<UUID, String>();
        if (ids.isEmpty()) return result;
        jdbc.query(STAMPS, row -> { result.put(row.getObject("id", UUID.class), row.getString("stamp")); },
                (Object) ids.toArray(UUID[]::new));
        return result;
    }
    /** Частичный черновик и прогресс, пока модель пишет ответ. */
    public boolean partial(ImportSession session, ImportDraftV1 draft, Progress progress, Instant now) {
        return jdbc.update(PARTIAL, json.writeValueAsString(draft), json.writeValueAsString(progress), session.id(),
                session.leaseToken(), stamp(now)) == 1;
    }
    /** Неразобранные недели — снова в очередь; готовые недели и решения по упражнениям сохраняются. */
    public void retryWeeks(UUID id, ImportPlan outline, Instant now) {
        jdbc.update(RETRY_WEEKS, json.writeValueAsString(outline), stamp(now), id);
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
                read(row, "outline", ImportPlan.class), read(row, "progress", Progress.class), row.getBoolean("sheets_selected"));
    }
    private Summary summary(ResultSet row, int index) throws SQLException {
        var name = row.getString("name");
        var draftName = row.getString("draft_name");
        var language = ImportLanguage.of(row.getString("ui_language"));
        if (draftName != null && !draftName.isBlank() && name.equals(language.defaultName())) name = draftName;
        var error = row.getString("error_code");
        return new Summary(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class), row.getString("status"),
                row.getLong("version"), name, row.getObject("start_date", java.time.LocalDate.class),
                row.getTimestamp("expires_at").toInstant(),
                row.getString("status").equals("processing") ? read(row, "progress", Progress.class) : null,
                row.getInt("open_choices"),
                error == null || row.getString("status").equals("queued") ? null : new Failure(error, row.getString("error_message")));
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
