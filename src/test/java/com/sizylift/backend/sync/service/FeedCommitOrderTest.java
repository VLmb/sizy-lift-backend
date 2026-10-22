package com.sizylift.backend.sync.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.coaching.service.CoachingService;
import com.sizylift.backend.publication.service.AggregateChangeListener;
import com.sizylift.backend.sync.dto.SyncChangesResponse.Change;
import com.sizylift.backend.sync.dto.SyncPushRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Порядок записей в ленты при параллельных транзакциях (ADR-0007, разделы 4–5). Тестовый слушатель держит
 * транзакцию записи открытой в нужной точке, чтобы воспроизвести гонку детерминированно.
 */
@SpringBootTest
class FeedCommitOrderTest extends PostgresIntegrationTest {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final String WAITING_FOR_LOCK = """
            SELECT count(*) FROM pg_stat_activity
            WHERE datname = current_database() AND wait_event_type = 'Lock'
            """;

    @Autowired
    SyncPushService push;

    @Autowired
    SyncChangesService changes;

    @Autowired
    PausingListener listener;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CoachingService coaching;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    @Autowired
    ObjectMapper json;

    /**
     * Коммиты не по порядку: запись A получила позицию и ещё не закоммичена, запись B того же получателя идёт
     * параллельно, читатель читает посередине. Читатель не должен перескочить A.
     */
    @Test
    void readerDoesNotSkipAWriteThatCommitsAfterALaterOne() throws Exception {
        var owner = newUser();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        listener.pauseAfter(first);
        var pool = Executors.newFixedThreadPool(2);

        var writeA = pool.submit(() -> push.push(owner, List.of(item(first))));
        assertThat(listener.paused.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        var writeB = pool.submit(() -> push.push(owner, List.of(item(second))));
        awaitBlockedOrDone(writeB);
        var middle = changes.changes(owner, 0, SyncChangesService.MAX_LIMIT);
        listener.release.countDown();
        writeA.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        writeB.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        var rest = changes.changes(owner, middle.nextCursor(), SyncChangesService.MAX_LIMIT);
        pool.shutdown();

        var seen = new HashSet<UUID>();
        middle.changes().stream().map(Change::id).forEach(seen::add);
        rest.changes().stream().map(Change::id).forEach(seen::add);
        assertThat(seen).containsExactlyInAnyOrder(first, second);
    }

    /**
     * Запись данных подопечного держит разделяемую блокировку его аккаунта до коммита, поэтому разрыв связи
     * ждёт её: фан-аут записи не может посчитать тренера получателем и дописать ему данные после {@code revoke}.
     */
    @Test
    void linkCannotEndWhileAnAthleteWriteIsInFlight() throws Exception {
        var athlete = newUser();
        var coach = newUser();
        var linkId = jdbc.queryForObject("INSERT INTO sizylift.coaching_link (coach_user_id, athlete_user_id, "
                + "health_access) VALUES (?, ?, true) RETURNING id", UUID.class, coach, athlete);
        listener.pauseBeforeWriteOf(athlete);
        var pool = Executors.newFixedThreadPool(2);

        var write = pool.submit(() -> push.push(athlete, List.of(item(UUID.randomUUID()))));
        assertThat(listener.paused.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        var end = pool.submit(() -> coaching.end(athlete, linkId));
        awaitBlockedOrDone(end);
        var endedWhileWriting = end.isDone();
        listener.release.countDown();
        write.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        end.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(endedWhileWriting).isFalse();
        var coachFeed = changes.changes(coach, 0, SyncChangesService.MAX_LIMIT).changes();
        assertThat(coachFeed).hasSize(1);
        assertThat(coachFeed.getFirst().kind().code()).isEqualTo("revoke");
    }

    /** B либо ждёт блокировку курсора (правильная лента), либо уже закоммичен (наивная). */
    private void awaitBlockedOrDone(java.util.concurrent.Future<?> write) throws InterruptedException {
        var deadline = Instant.now().plus(TIMEOUT);
        while (!write.isDone() && jdbc.queryForObject(WAITING_FOR_LOCK, Long.class) == 0) {
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(10);
        }
    }

    private SyncPushRequest.Item item(UUID id) {
        var payload = json.createObjectNode().put("name", "x").putNull("description")
                .put("updatedAt", "2026-10-20T10:00:00Z");
        return new SyncPushRequest.Item("contraindication", id, null, 0, Instant.parse("2026-10-20T10:00:00Z"),
                "phone", 1, 1, false, payload);
    }

    private UUID newUser() {
        return jdbc.queryForObject("INSERT INTO sizylift.user_account (issuer, subject) VALUES ('test', ?) "
                + "RETURNING id", UUID.class, UUID.randomUUID().toString());
    }

    /** Держит транзакцию записи открытой после выдачи позиции в ленте. */
    static class PausingListener implements AggregateChangeListener {

        volatile CountDownLatch paused;
        volatile CountDownLatch release;
        private final FeedFanout delegate;
        private volatile UUID pauseAfter;
        private volatile UUID pauseBeforeWriteOf;

        PausingListener(FeedFanout delegate) {
            this.delegate = delegate;
            reset();
        }

        void reset() {
            paused = new CountDownLatch(1);
            release = new CountDownLatch(1);
            pauseAfter = null;
            pauseBeforeWriteOf = null;
        }

        void pauseAfter(UUID aggregateId) {
            pauseAfter = aggregateId;
        }

        void pauseBeforeWriteOf(UUID ownerId) {
            pauseBeforeWriteOf = ownerId;
        }

        @Override
        public void beforeWrite(UUID ownerId) {
            delegate.beforeWrite(ownerId);
            if (ownerId.equals(pauseBeforeWriteOf)) {
                pause();
            }
        }

        @Override
        public void changed(UUID ownerId, String type, UUID aggregateId, long revision, boolean deleted) {
            delegate.changed(ownerId, type, aggregateId, revision, deleted);
            if (aggregateId.equals(pauseAfter)) {
                pause();
            }
        }

        private void pause() {
            paused.countDown();
            try {
                release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @TestConfiguration
    static class PausingConfiguration {

        @Bean
        @Primary
        PausingListener pausingListener(FeedFanout feed) {
            return new PausingListener(feed);
        }
    }
}
