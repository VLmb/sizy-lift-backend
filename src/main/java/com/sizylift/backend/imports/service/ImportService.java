package com.sizylift.backend.imports.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.imports.config.ImportProperties;
import com.sizylift.backend.imports.dto.ImportDtos.Answer;
import com.sizylift.backend.imports.dto.ImportDtos.Draft;
import com.sizylift.backend.imports.dto.ImportDtos.ImportResponse;
import com.sizylift.backend.imports.dto.ImportDtos.Option;
import com.sizylift.backend.imports.dto.ImportDtos.Question;
import com.sizylift.backend.imports.dto.ImportDtos.Warning;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.entity.ImportSource;
import com.sizylift.backend.imports.entity.ImportStatus;
import com.sizylift.backend.imports.entity.Round;
import com.sizylift.backend.imports.model.ModelUnavailableException;
import com.sizylift.backend.imports.model.ParsedReply;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.model.ProgramParserModel.ModelReply;
import com.sizylift.backend.imports.model.ProgramParserModel.Role;
import com.sizylift.backend.imports.model.ProgramParserModel.Turn;
import com.sizylift.backend.imports.repository.ImportCatalogRepository;
import com.sizylift.backend.imports.repository.ImportSessionRepository;
import com.sizylift.backend.publication.service.AggregateType;
import jakarta.validation.Validator;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * ИИ-импорт программы (ADR-0008): нормализация входа → вызов модели вне транзакции → вопросы или
 * детерминированный черновик. Сервер программу не сохраняет: черновик клиент записывает обычной записью.
 * В логах нет текста программы и ответов модели.
 */
@Service
public class ImportService {

    static final String OUTCOME_QUESTIONS = "questions";
    static final String OUTCOME_DRAFT = "draft";
    static final String OUTCOME_FAILED = "failed";
    static final String OUTCOME_UNAVAILABLE = "unavailable";
    static final String OUTCOME_REJECTED = "rejected";
    private static final String FIX_REQUEST = "Ответ не прошёл проверку: %s. Верни исправленный json.";
    private static final String ANSWERS_PREFIX = "Ответы пользователя (json): ";
    private static final int ZIP_HEADER_BYTES = 4;
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportProperties properties;
    private final ImportSessionRepository sessions;
    private final ImportCatalogRepository catalog;
    private final ProgramParserModel model;
    private final SheetFetcher sheets;
    private final TableGrid grid;
    private final ImportLimits limits;
    private final ImportMetrics metrics;
    private final AccessPolicy access;
    private final Validator validator;
    private final ObjectMapper json;
    private final Clock clock;

    ImportService(ImportProperties properties, ImportSessionRepository sessions, ImportCatalogRepository catalog,
                  ProgramParserModel model, SheetFetcher sheets, TableGrid grid, ImportLimits limits,
                  ImportMetrics metrics, AccessPolicy access, Validator validator, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.sessions = sessions;
        this.catalog = catalog;
        this.model = model;
        this.sheets = sheets;
        this.grid = grid;
        this.limits = limits;
        this.metrics = metrics;
        this.access = access;
        this.validator = validator;
        this.json = json;
        this.clock = clock;
    }

    public ImportResponse importText(UUID callerId, String text, LocalDate startDate, UUID targetOwnerId) {
        return start(callerId, ImportSource.TEXT, () -> text, startDate, targetOwnerId);
    }

    public ImportResponse importSheet(UUID callerId, String url, LocalDate startDate, UUID targetOwnerId) {
        return start(callerId, ImportSource.SHEET_URL, () -> grid.fromCsv(sheets.fetchCsv(url)), startDate,
                targetOwnerId);
    }

    /** {@code .xlsx} определяется по сигнатуре ZIP, остальное читается как CSV в UTF-8. */
    public ImportResponse importFile(UUID callerId, byte[] file, LocalDate startDate, UUID targetOwnerId) {
        return start(callerId, ImportSource.FILE, () -> fileGrid(file), startDate, targetOwnerId);
    }

    public ImportResponse answer(UUID callerId, UUID sessionId, List<Answer> answers) {
        var session = sessions.find(sessionId).filter(found -> found.userId().equals(callerId))
                .orElseThrow(() -> new ImportException(HttpStatus.NOT_FOUND, "not_found", "Импорт не найден"));
        if (!session.expiresAt().isAfter(clock.instant())) {
            throw new ImportException(HttpStatus.GONE, "expired", "Импорт устарел, начните заново");
        }
        try (var slot = limits.userSlot(callerId)) {
            if (!sessions.claimForAnswers(sessionId, callerId)) {
                throw new ImportException(HttpStatus.CONFLICT, "not_awaiting_answers", "Импорт не ждёт ответов");
            }
            var rounds = new ArrayList<>(session.rounds());
            var last = rounds.removeLast();
            rounds.add(new Round(last.reply(), answers));
            var finalRound = rounds.size() >= properties.maxRounds();
            return metrics.observe(() -> run(session, rounds, finalRound, ImportMetrics.ANSWERS_SOURCE));
        }
    }

    private ImportResponse start(UUID callerId, ImportSource source, InputSupplier input, LocalDate startDate,
                                 UUID targetOwnerId) {
        var owner = targetOwnerId == null ? callerId : targetOwnerId;
        if (!access.canWriteFor(callerId, owner, AggregateType.PROGRAM)) {
            metrics.request(source, OUTCOME_REJECTED);
            throw new ImportException(HttpStatus.FORBIDDEN, "forbidden",
                    "Нет права писать программы этого пользователя");
        }
        limits.checkDaily(callerId);
        try (var slot = limits.userSlot(callerId)) {
            var normalized = normalize(input.get());
            var start = startDate == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : startDate;
            var id = sessions.create(callerId, owner, source, normalized, start,
                    clock.instant().plus(properties.sessionTtl()));
            var session = new ImportSession(id, callerId, owner, normalized, start, ImportStatus.PENDING, List.of(),
                    clock.instant().plus(properties.sessionTtl()));
            return metrics.observe(() -> run(session, new ArrayList<>(), false, source.code()));
        } catch (ImportException e) {
            if (e.status().is4xxClientError()) {
                metrics.request(source, OUTCOME_REJECTED);
            }
            throw e;
        }
    }

    /** Один раунд: вызов модели, проверка, вопросы или черновик; итог записывается в сессию. */
    private ImportResponse run(ImportSession session, List<Round> rounds, boolean finalRound, String source) {
        var matcher = new ExerciseMatcher(catalog,
                Stream.of(session.targetOwnerId(), session.userId()).distinct().toList());
        var tokens = new int[2];
        try {
            var reply = askValid(session.input(), matcher.names(), dialogue(rounds), finalRound, tokens);
            rounds.add(new Round(reply.raw(), List.of()));
            var parsed = reply.parsed();
            if (!parsed.questionsOrEmpty().isEmpty() && !finalRound) {
                sessions.finish(session.id(), ImportStatus.QUESTIONS, rounds, null, tokens[0], tokens[1]);
                metrics.request(source, OUTCOME_QUESTIONS);
                return response(session, ImportStatus.QUESTIONS, rounds.size(), questions(parsed), null, List.of());
            }
            if (parsed.program() == null) {
                throw failed("no_program", "Не удалось собрать программу, уточните текст и попробуйте снова");
            }
            var result = new DraftAssembler(matcher::match, catalog.oneRepMaxGrams(session.targetOwnerId()),
                    session.startDate(), clock.instant()).assemble(parsed);
            if (!validator.validate(result.draft().program()).isEmpty()) {
                throw failed("draft_invalid", "Черновик не прошёл проверку, уточните текст и попробуйте снова");
            }
            sessions.finish(session.id(), ImportStatus.DRAFT, rounds, result.draft(), tokens[0], tokens[1]);
            metrics.request(source, OUTCOME_DRAFT);
            metrics.draftAfterRounds(rounds.size() - 1);
            return response(session, ImportStatus.DRAFT, rounds.size(), List.of(), result.draft(),
                    result.warnings());
        } catch (ImportException e) {
            sessions.finish(session.id(), ImportStatus.FAILED, rounds, null, tokens[0], tokens[1]);
            metrics.request(source, e.status() == HttpStatus.SERVICE_UNAVAILABLE ? OUTCOME_UNAVAILABLE
                    : OUTCOME_FAILED);
            throw e;
        } finally {
            metrics.tokens(tokens[0], tokens[1]);
        }
    }

    /** Невалидный ответ — один повтор с ошибками проверки в промпте, затем понятная ошибка. */
    private Checked askValid(String input, List<String> names, List<Turn> dialogue, boolean finalRound,
                             int[] tokens) {
        var reply = ask(input, names, dialogue, finalRound, tokens);
        var problems = problems(reply.json(), finalRound);
        if (problems.isEmpty()) {
            return new Checked(reply.json(), read(reply.json()));
        }
        log.info("Model reply failed validation, retrying once");
        var retry = new ArrayList<>(dialogue);
        retry.add(new Turn(Role.MODEL, reply.json()));
        retry.add(new Turn(Role.USER, FIX_REQUEST.formatted(problems)));
        var second = ask(input, names, retry, finalRound, tokens);
        if (!problems(second.json(), finalRound).isEmpty()) {
            throw failed("model_output_invalid", "Не удалось разобрать программу, уточните текст и попробуйте "
                    + "снова");
        }
        return new Checked(second.json(), read(second.json()));
    }

    /** Сбой провайдера — один повтор с паузой, затем {@code 503 import_unavailable}. */
    private ModelReply ask(String input, List<String> names, List<Turn> dialogue, boolean finalRound,
                           int[] tokens) {
        for (int attempt = 0; ; attempt++) {
            try (var slot = limits.modelSlot()) {
                var reply = model.parse(input, names, dialogue, finalRound);
                tokens[0] += reply.tokensIn();
                tokens[1] += reply.tokensOut();
                return reply;
            } catch (ModelUnavailableException e) {
                log.warn("Model provider failure on attempt {}: {}", attempt + 1, e.getMessage());
                if (!e.retryable() || attempt > 0) {
                    throw new ImportException(HttpStatus.SERVICE_UNAVAILABLE, "import_unavailable",
                            "Импорт сейчас недоступен, попробуйте позже");
                }
                pause();
            }
        }
    }

    /** Пусто — ответ годится. */
    private String problems(String raw, boolean finalRound) {
        ParsedReply parsed;
        try {
            parsed = read(raw);
        } catch (JacksonException e) {
            return "это не json по формату";
        }
        if (parsed == null) {
            return "пустой ответ";
        }
        var violations = validator.validate(parsed).stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .sorted().collect(Collectors.joining("; "));
        if (!violations.isEmpty()) {
            return violations;
        }
        var hasQuestions = !parsed.questionsOrEmpty().isEmpty();
        if (hasQuestions == (parsed.program() != null) && !(finalRound && parsed.program() != null)) {
            return "нужно либо questions, либо program";
        }
        return "";
    }

    private ParsedReply read(String raw) {
        return raw == null || raw.isBlank() ? null : json.readValue(raw, ParsedReply.class);
    }

    private List<Turn> dialogue(List<Round> rounds) {
        var turns = new ArrayList<Turn>();
        for (var round : rounds) {
            turns.add(new Turn(Role.MODEL, round.reply()));
            if (!round.answers().isEmpty()) {
                turns.add(new Turn(Role.USER, ANSWERS_PREFIX + json.writeValueAsString(round.answers())));
            }
        }
        return turns;
    }

    private String normalize(String input) {
        if (input == null || input.isBlank()) {
            throw ImportException.invalidInput("empty_input", "Пустой ввод");
        }
        var normalized = input.strip();
        if (normalized.length() > properties.maxInputChars()) {
            throw ImportException.invalidInput("input_too_long",
                    "Не больше " + properties.maxInputChars() + " символов");
        }
        return normalized;
    }

    private String fileGrid(byte[] file) {
        if (file == null || file.length == 0) {
            throw ImportException.invalidInput("empty_input", "Пустой файл");
        }
        if (file.length > properties.maxFileBytes()) {
            throw ImportException.invalidInput("file_too_large", "Файл больше " + properties.maxFileBytes()
                    + " байт");
        }
        var header = Arrays.copyOf(file, ZIP_HEADER_BYTES);
        return ReadableWorkbook.isOOXMLZipHeader(header)
                ? grid.fromXlsx(new ByteArrayInputStream(file))
                : grid.fromCsv(new String(file, StandardCharsets.UTF_8));
    }

    private void pause() {
        try {
            Thread.sleep(properties.model().retryPause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<Question> questions(ParsedReply parsed) {
        return parsed.questionsOrEmpty().stream()
                .map(question -> new Question(question.id(), question.text(), question.options().stream()
                        .map(option -> new Option(option.id(), option.text())).toList()))
                .toList();
    }

    private static ImportResponse response(ImportSession session, ImportStatus status, int round,
                                           List<Question> questions, Draft draft, List<Warning> warnings) {
        return new ImportResponse(session.id(), status, round, questions, draft, warnings, session.expiresAt());
    }

    private static ImportException failed(String code, String message) {
        return new ImportException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    /** Ответ модели, прошедший проверку. */
    private record Checked(String raw, ParsedReply parsed) {
    }

    @FunctionalInterface
    private interface InputSupplier {
        String get();
    }
}
