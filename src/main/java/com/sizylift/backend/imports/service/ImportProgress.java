package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.model.ProgramParserModel;
import com.sizylift.backend.imports.repository.ImportRepository;
import com.sizylift.backend.imports.template.ImportAssembly;
import org.springframework.stereotype.Component;

/**
 * Частичный черновик и прогресс, пока модель пишет ответ: каркас, упражнения и тренировки появляются у пользователя
 * по мере генерации (docs/imports.md, «Поток разбора»). Запись в сессию не чаще раза в секунду и с проверкой аренды.
 */
@Component
public class ImportProgress {
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private final ImportRepository sessions;
    private final Clock clock;

    public ImportProgress(ImportRepository sessions, Clock clock) { this.sessions = sessions; this.clock = clock; }

    public Publisher start(ImportSession session, ImportAssembly assembly) { return new Publisher(session, assembly); }

    public final class Publisher {
        private final ImportSession session;
        private final ImportAssembly assembly;
        private long published = -1;
        private Instant written = Instant.MIN;

        private Publisher(ImportSession session, ImportAssembly assembly) {
            this.session = session;
            this.assembly = assembly;
            publish(true);
        }

        /** Слушатель одного вызова: делит поток на строки и передаёт их курсору. */
        public Lines lines(ImportAssembly.Cursor cursor) { return new Lines(cursor); }

        public synchronized void publish(boolean force) {
            var changes = assembly.changes();
            if (changes == published) return;
            var now = clock.instant();
            if (!force && now.isBefore(written.plus(INTERVAL))) return;
            if (sessions.partial(session, assembly.snapshot(false), assembly.progress(), now)) {
                published = changes;
                written = now;
            }
        }

        /**
         * Строки ответа одного вызова. Последняя строка без перевода приходит в {@link #end(boolean)}: у обрезанного
         * ответа она не закончена и отбрасывается.
         */
        public final class Lines implements ProgramParserModel.Listener {
            private final ImportAssembly.Cursor cursor;
            private final StringBuilder buffer = new StringBuilder();
            private boolean received;

            private Lines(ImportAssembly.Cursor cursor) { this.cursor = cursor; }

            @Override
            public void onText(String delta) {
                received = true;
                boolean complete = false;
                for (int i = 0; i < delta.length(); i++) {
                    char c = delta.charAt(i);
                    if (c == '\n') {
                        cursor.line(buffer.toString());
                        buffer.setLength(0);
                        complete = true;
                    } else if (c != '\r') {
                        buffer.append(c);
                    }
                }
                if (complete) publish(false);
            }

            /** Конец вызова; если ответ пришёл не потоком, а целиком, его строки читаются здесь. */
            public java.util.List<Integer> finish(ProgramParserModel.Result result) {
                if (!received && result.text() != null) onText(result.text());
                return end(result.truncated());
            }

            public java.util.List<Integer> end(boolean truncated) {
                if (!truncated && !buffer.isEmpty()) cursor.line(buffer.toString());
                buffer.setLength(0);
                var done = cursor.end(truncated);
                // Конец вызова — готовый каркас или недели: записывается сразу, без ограничения частоты.
                publish(true);
                return done;
            }
        }
    }
}
