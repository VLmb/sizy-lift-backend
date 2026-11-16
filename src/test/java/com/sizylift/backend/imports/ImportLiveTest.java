package com.sizylift.backend.imports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.service.ImportWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Настоящий DeepSeek на настоящих программах (платные вызовы): полный путь через API, worker и разбор.
 * Запускается только с DEEPSEEK_API_KEY и IMPORT_LIVE_DIR — папкой с файлами XLSX/XLS/CSV/TXT. В
 * IMPORT_LIVE_DIR/results для каждого файла (или, с IMPORT_LIVE_URL, для каждой вкладки Google Таблицы отдельно) — итоговое состояние сессии, вызовы модели с временем и токенами
 * и сырые ответы модели: по ним правится промпт.
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IMPORT_LIVE_DIR", matches = ".+")
@SpringBootTest(properties = {"sizylift.imports.enabled=true", "sizylift.imports.worker-enabled=false",
        "sizylift.imports.daily-limit=1000", "sizylift.imports.active-per-user=1000",
        "sizylift.imports.deepseek.api-key=${DEEPSEEK_API_KEY}", "sizylift.imports.deepseek.url=https://api.deepseek.com"})
@AutoConfigureMockMvc
class ImportLiveTest extends CoachWorkspaceApiSupport {
    @Autowired ImportWorker worker;

    @Test
    void importsEveryFileOfTheCorpus() throws Exception {
        var dir = Path.of(System.getenv("IMPORT_LIVE_DIR"));
        var results = Files.createDirectories(dir.resolve("results"));
        var only = System.getenv("IMPORT_LIVE_ONLY");
        var url = System.getenv("IMPORT_LIVE_URL");
        if (url != null && !url.isBlank()) {
            linkSheetBySheet(url, results);
            return;
        }
        try (var files = Files.list(dir)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                var name = file.getFileName().toString();
                if (only != null && !only.isBlank() && !name.contains(only)) continue;
                if (name.matches("(?i).+\\.(xlsx|xls|csv|txt)")) run(file, results);
            }
        }
    }

    /** Записывает каждый вызов модели: время до первого фрагмента, длительность, токены и текст ответа. */
    @TestConfiguration
    static class Recording {
        static final List<Map<String, Object>> CALLS = new ArrayList<>();
        static volatile long started;

        @Bean
        static BeanPostProcessor recordModelCalls() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof ProgramParserModel model)) return bean;
                    return (ProgramParserModel) (request, listener) -> record(model, request, listener);
                }
            };
        }

        private static ProgramParserModel.Result record(ProgramParserModel model, ProgramParserModel.Request request,
                                                        ProgramParserModel.Listener listener) {
            long begin = System.nanoTime();
            var first = new AtomicLong();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("task", request.input().task().kind().name());
            entry.put("weeks", request.input().task().weeks());
            entry.put("startMs", (begin - started) / 1_000_000);
            try {
                var result = model.parse(request, delta -> {
                    first.compareAndSet(0, System.nanoTime());
                    listener.onText(delta);
                });
                entry.put("firstTextMs", first.get() == 0 ? null : (first.get() - begin) / 1_000_000);
                entry.put("durationMs", (System.nanoTime() - begin) / 1_000_000);
                entry.put("inputTokens", result.inputTokens());
                entry.put("cachedTokens", result.cachedTokens());
                entry.put("outputTokens", result.outputTokens());
                entry.put("truncated", result.truncated());
                entry.put("text", result.text());
                return result;
            } catch (RuntimeException e) {
                entry.put("durationMs", (System.nanoTime() - begin) / 1_000_000);
                entry.put("error", e.getClass().getSimpleName());
                throw e;
            } finally {
                synchronized (CALLS) { CALLS.add(entry); }
            }
        }
    }

    /** Ссылка Google Sheets: каждая вкладка — отдельный импорт, как если бы пользователь выбрал только её. */
    private void linkSheetBySheet(String url, Path results) throws Exception {
        var probe = newSubject();
        var id = createLink(probe, url);
        assertThat(worker.processOne()).isTrue();
        var waiting = body(call(probe, get("/api/v2/imports/" + id)).andExpect(status().isOk()));
        Files.writeString(results.resolve("sheets.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(waiting.get("sheets")));
        System.out.printf("Листы: %s%n", waiting.get("sheets"));
        for (var sheet : waiting.get("sheets")) {
            synchronized (Recording.CALLS) { Recording.CALLS.clear(); }
            long started = System.nanoTime();
            Recording.started = started;
            var owner = newSubject();
            var session = createLink(owner, url);
            assertThat(worker.processOne()).isTrue();
            var current = body(call(owner, get("/api/v2/imports/" + session)).andExpect(status().isOk()));
            postJson(owner, "/api/v2/imports/" + session + "/sheets", Map.of("version", current.get("version").asLong(),
                    "sheets", List.of(sheet.get("index").asInt()))).andExpect(status().isAccepted());
            assertThat(worker.processOne()).isTrue();
            report(results, "sheet-" + sheet.get("index").asInt() + " " + sheet.get("name").asString(), owner, session, started);
        }
    }

    private String createLink(String owner, String url) throws Exception {
        var request = Map.of("requestId", UUID.randomUUID().toString(), "source", "sheet", "url", url, "startDate", "2026-11-16");
        return body(postJson(owner, "/api/v2/imports", request).andExpect(status().isAccepted())).get("id").asString();
    }

    private void report(Path results, String name, String owner, String id, long started) throws Exception {
        long total = (System.nanoTime() - started) / 1_000_000;
        var view = body(call(owner, get("/api/v2/imports/" + id)).andExpect(status().isOk()));
        var report = new LinkedHashMap<String, Object>();
        report.put("file", name);
        report.put("totalMs", total);
        synchronized (Recording.CALLS) { report.put("calls", new ArrayList<>(Recording.CALLS)); }
        report.put("view", view);
        // Имя отчёта — ASCII: в окружениях без UTF-8 файловой системы кириллица в имени файла недопустима.
        Files.writeString(results.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.printf("%s: %s за %d мс%n", name, view.get("status").asString(), total);
    }

    private void run(Path file, Path results) throws Exception {
        var name = file.getFileName().toString();
        synchronized (Recording.CALLS) { Recording.CALLS.clear(); }
        long started = System.nanoTime();
        Recording.started = started;
        var owner = newSubject();
        var metadata = Map.of("requestId", UUID.randomUUID().toString(), "source", "file", "startDate", "2026-11-16");
        var content = Files.readAllBytes(file);
        var created = body(mvc.perform(multipart("/api/v2/imports")
                        .file(new MockMultipartFile("file", file.getFileName().toString(), "application/octet-stream", content))
                        .file(new MockMultipartFile("metadata", "", "application/json", json.writeValueAsBytes(metadata)))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(token -> token.issuer(ISSUER).subject(owner).claim("email", owner + "@example.com"))))
                .andExpect(status().isAccepted()));
        var id = created.get("id").asString();
        assertThat(worker.processOne()).isTrue();
        var waiting = body(call(owner, get("/api/v2/imports/" + id)).andExpect(status().isOk()));
        if (waiting.get("status").asString().equals("sheet_selection")) {
            // Корпус — программы целиком: выбираются все листы, как кнопкой «Выбрать все».
            var sheets = new ArrayList<Integer>();
            waiting.get("sheets").forEach(sheet -> sheets.add(sheet.get("index").asInt()));
            postJson(owner, "/api/v2/imports/" + id + "/sheets", Map.of("version", waiting.get("version").asLong(), "sheets", sheets))
                    .andExpect(status().isAccepted());
            assertThat(worker.processOne()).isTrue();
        }
        report(results, name, owner, id, started);
    }
}
