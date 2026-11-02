package com.sizylift.backend.imports.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.dto.ImportDtos.*;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.entity.ImportSession.Clarification;
import com.sizylift.backend.imports.repository.ImportRepository;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.web.service.WebReadService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ImportService {
    private static final Set<String> SOURCES = Set.of("text", "sheet", "file");
    private final ImportRepository sessions;
    private final ImportProperties limits;
    private final AccessPolicy access;
    private final WebReadService reads;
    private final ProgramDraftCompiler compiler;
    private final ObjectMapper json;
    private final Clock clock;

    public ImportService(ImportRepository sessions, ImportProperties limits, AccessPolicy access,
                         WebReadService reads, ProgramDraftCompiler compiler, ObjectMapper json, Clock clock) {
        this.sessions = sessions; this.limits = limits; this.access = access; this.reads = reads;
        this.compiler = compiler; this.json = json; this.clock = clock;
    }

    @Transactional
    public Created create(UUID user, Create input, byte[] bytes, String filename) {
        enabled();
        validateSource(input, bytes);
        var hash = hash(input, bytes, filename);
        sessions.lockAdmission(user);
        var previous = sessions.byRequest(user, input.requestId());
        if (previous.isPresent()) {
            var old = previous.get();
            if (!old.requestHash().equals(hash)) throw ImportFailure.conflict();
            authorize(user, old);
            return new Created(old.id(), old.status(), old.version());
        }
        var owner = input.ownerId() == null ? user : input.ownerId();
        if (!access.canWriteFor(user, owner, AggregateType.PROGRAM)) throw ImportFailure.missing();
        var now = clock.instant();
        if (sessions.active(user, now) >= limits.activePerUser()
                || sessions.createdSince(user, now.minus(Duration.ofDays(1))) >= limits.dailyLimit())
            throw new ImportFailure(HttpStatus.TOO_MANY_REQUESTS, "import_limit", "Достигнут лимит импортов. Попробуйте позже.");
        var catalog = reads.catalog(user, owner).exercises();
        var session = sessions.create(user, owner, input, bytes, filename == null ? "" : safeFilename(filename), hash, catalog, now, limits);
        return new Created(session.id(), session.status(), session.version());
    }

    @Transactional(readOnly = true)
    public View get(UUID user, UUID id) { return view(owned(user, id, false)); }

    @Transactional
    public View answer(UUID user, UUID id, Answer answer) {
        enabled();
        var session = owned(user, id, true);
        for (var old : session.history()) {
            if (old.requestId().equals(answer.requestId())) {
                if (old.round() != answer.round() || !old.answer().equals(answer.answer().trim())) throw ImportFailure.conflict();
                return view(session);
            }
        }
        if (!session.status().equals("clarification_required") || session.version() != answer.version()
                || session.round() != answer.round()) throw ImportFailure.conflict();
        if (session.round() >= limits.clarificationRounds()) throw ImportFailure.conflict();
        var history = new ArrayList<>(session.history());
        history.add(new Clarification(answer.requestId(), session.round(), session.output().clarificationMessage(), answer.answer().trim()));
        sessions.answer(id, history, clock.instant());
        return view(sessions.find(id).orElseThrow());
    }

    @Transactional
    public View patch(UUID user, UUID id, Patch patch) {
        var session = owned(user, id, true);
        if (!session.status().equals("review") || session.version() != patch.version()) throw ImportFailure.conflict();
        var available = reads.catalog(user, session.ownerId()).exercises();
        var keys = session.output().exerciseResolutions().stream().map(r -> r.key()).collect(Collectors.toSet());
        var decisions = new LinkedHashMap<>(session.decisions());
        var edited = new java.util.HashSet<String>();
        if (patch.decisions() != null) {
            for (var decision : patch.decisions()) {
                if (!keys.contains(decision.key()) || !edited.add(decision.key())) throw ImportFailure.invalid("Неизвестное или повторное упражнение.");
                var normalized = decision.kind().equals("new") ? new Decision(decision.key(), "new",
                        ProgramDraftCompiler.id(id, "exercise:" + decision.key()), decision.name(), decision.resultType(), decision.liftClass()) : decision;
                compiler.validateDecision(id, normalized, available);
                decisions.put(decision.key(), normalized);
            }
        }
        var name = patch.name() == null ? session.name() : patch.name().trim();
        if (name.isBlank()) throw ImportFailure.invalid("Укажите название программы.");
        sessions.patch(id, name, patch.startDate() == null ? session.startDate() : patch.startDate(), decisions);
        var updated = sessions.find(id).orElseThrow();
        if (decisions.keySet().containsAll(keys)) compiler.compile(updated, clock.instant(), available);
        return view(updated);
    }

    @Transactional
    public void cancel(UUID user, UUID id) {
        var session = owned(user, id, true);
        if (session.status().equals("committed")) throw ImportFailure.conflict();
        sessions.cancel(id);
    }

    public ImportSession owned(UUID user, UUID id, boolean lock) {
        var session = (lock ? sessions.lock(id) : sessions.find(id)).orElseThrow(ImportFailure::missing);
        authorize(user, session);
        return session;
    }

    private void authorize(UUID user, ImportSession session) {
        if (!session.userId().equals(user) || !access.canRead(user, session.ownerId(), AggregateType.PROGRAM))
            throw ImportFailure.missing();
        if (!session.status().equals("committed") && !session.status().equals("cancelled")
                && !session.expiresAt().isAfter(clock.instant()))
            throw new ImportFailure(HttpStatus.GONE, "import_expired", "Срок хранения импорта истёк. Загрузите источник снова.");
    }

    private View view(ImportSession session) {
        ProgramDraftCompiler.Compiled compiled = null;
        var warnings = List.<String>of();
        if (session.status().equals("review")) {
            try {
                compiled = compiler.compile(session, clock.instant(), reads.catalog(session.userId(), session.ownerId()).exercises());
                warnings = compiled.warnings();
            } catch (ImportFailure e) { warnings = List.of(e.getMessage()); }
        }
        var output = session.output();
        return new View(session.id(), session.ownerId(), session.status(), session.version(), session.round(), session.name(),
                session.startDate(), session.expiresAt(), output != null && output.needsClarification(),
                output == null ? "" : output.clarificationMessage(), output, session.normalized(), session.decisions(),
                compiled != null, compiled == null ? null : compiled.program(), warnings,
                session.errorCode() == null ? null : new Failure(session.errorCode(), session.errorMessage()),
                session.inputTokens(), session.outputTokens(), session.reservedCostMicros(), receipt(session));
    }

    public static Receipt receipt(ImportSession session) {
        return session.programRevision() == null ? null : new Receipt(session.id(), session.ownerId(), session.programId(), session.programRevision());
    }

    private void validateSource(Create input, byte[] bytes) {
        if (!SOURCES.contains(input.source())) throw ImportFailure.invalid("Выберите текст, файл или ссылку Google Sheets.");
        if (input.name() != null && input.name().isBlank()) throw ImportFailure.invalid("Название не должно быть пустым.");
        switch (input.source()) {
            case "text" -> {
                if (input.text() == null || input.text().isBlank() || input.text().length() > limits.maxSourceChars()
                        || bytes != null || input.url() != null) throw ImportFailure.invalid("Добавьте текст программы в пределах лимита.");
            }
            case "sheet" -> {
                if (input.url() == null || input.url().isBlank() || input.text() != null || bytes != null)
                    throw ImportFailure.invalid("Добавьте ссылку на Google Таблицу.");
            }
            case "file" -> {
                if (bytes == null || bytes.length == 0 || bytes.length > limits.maxFileBytes() || input.text() != null || input.url() != null)
                    throw ImportFailure.invalid("Загрузите XLSX, XLS или CSV в пределах лимита.");
            }
            default -> throw ImportFailure.invalid("Неизвестный источник.");
        }
    }

    private String hash(Create input, byte[] bytes, String filename) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8));
            if (bytes != null) digest.update(bytes);
            if (filename != null) digest.update(filename.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String safeFilename(String value) {
        var name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 200) throw ImportFailure.invalid("Слишком длинное имя файла.");
        return name;
    }
    private void enabled() { if (!limits.enabled()) throw ImportFailure.unavailable(); }
}
