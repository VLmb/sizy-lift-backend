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
import com.sizylift.backend.publication.service.AggregateChangeListener;
import com.sizylift.backend.sync.dto.SyncChangesResponse.Change;
import com.sizylift.backend.sync.dto.SyncPushRequest;
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
 * Коммиты не по порядку (ADR-0007, раздел 4): запись A получила позицию и ещё не закоммичена, запись B
 * того же получателя идёт параллельно, читатель читает посередине. Читатель не должен перескочить A.
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
    ObjectMapper json;

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

        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final FeedFanout delegate;
        private volatile UUID pauseAfter;

        PausingListener(FeedFanout delegate) {
            this.delegate = delegate;
        }

        void pauseAfter(UUID aggregateId) {
            pauseAfter = aggregateId;
        }

        @Override
        public void changed(UUID ownerId, String type, UUID aggregateId, long revision, boolean deleted) {
            delegate.changed(ownerId, type, aggregateId, revision, deleted);
            if (aggregateId.equals(pauseAfter)) {
                paused.countDown();
                try {
                    release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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
