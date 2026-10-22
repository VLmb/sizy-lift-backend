package com.sizylift.backend.sync;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.publication.service.ConflictRetention;
import com.sizylift.backend.sync.dto.SyncPushRequest;
import com.sizylift.backend.sync.service.SyncPushService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Запись v2 (docs/sync.md): ревизии, last-writer-wins, журнал конфликтов, идемпотентность. */
@SpringBootTest
@AutoConfigureMockMvc
class SyncPushApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final String CONTRAINDICATION = "contraindication";
    static final String PHONE = "phone-1";
    static final String WEB = "web-1";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    static final Instant T12 = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MeterRegistry meters;

    @Autowired
    SyncPushService sync;

    @Autowired
    ConflictRetention retention;

    String subject;
    UUID id;

    @BeforeEach
    void newUserAndAggregate() {
        subject = UUID.randomUUID().toString();
        id = UUID.randomUUID();
    }

    @Test
    void newAggregateGetsAServerRevisionAndWriterMetadata() throws Exception {
        var result = push(edit(0, T10, PHONE, 1, "Без прыжков")).get(0);

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        assertThat(result.get("clientVersion").asLong()).isEqualTo(1);
        assertThat(result.get("revision").asLong()).isPositive();
        var state = state();
        assertThat(state.get("revision")).isEqualTo(result.get("revision").asLong());
        assertThat(state.get("changed_at")).isEqualTo(java.sql.Timestamp.from(T10));
        assertThat(state.get("source_client_id")).isEqualTo(PHONE);
        assertThat(state.get("source_version")).isEqualTo(1L);
        assertThat(state.get("author_user_id")).isNull();
        assertThat(name()).isEqualTo("Без прыжков");
    }

    @Test
    void editFromTheCurrentRevisionFastForwardsWithoutConflict() throws Exception {
        var first = revision(push(edit(0, T10, PHONE, 1, "v1")).get(0));

        var second = push(edit(first, T12, PHONE, 2, "v2")).get(0);

        assertThat(second.get("status").asString()).isEqualTo("accepted");
        assertThat(revision(second)).isGreaterThan(first);
        assertThat(name()).isEqualTo("v2");
        assertThat(conflicts()).isZero();
    }

    @Test
    void laterEditWinsAnOutdatedBaseAndTheStoredVersionGoesToTheJournal() throws Exception {
        var base = revision(push(edit(0, T10, PHONE, 1, "phone")).get(0));
        push(edit(base, T10.plusSeconds(60), WEB, 1, "web"));

        var result = push(edit(base, T12, PHONE, 2, "phone, later")).get(0);

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        assertThat(name()).isEqualTo("phone, later");
        var conflict = conflict();
        assertThat(conflict.get("lost_client_id")).isEqualTo(WEB);
        assertThat(conflict.get("lost_changed_at")).isEqualTo(java.sql.Timestamp.from(T10.plusSeconds(60)));
        assertThat(conflict.get("winner_revision")).isEqualTo(revision(result));
    }

    @Test
    void earlierEditOnAnOutdatedBaseIsSupersededAndItsPayloadIsKept() throws Exception {
        var base = revision(push(edit(0, T10, PHONE, 1, "phone")).get(0));
        var winner = revision(push(edit(base, T12, WEB, 1, "web")).get(0));

        var result = push(edit(base, T10.plusSeconds(1), PHONE, 2, "phone, offline")).get(0);

        assertThat(result.get("status").asString()).isEqualTo("superseded");
        assertThat(revision(result)).isEqualTo(winner);
        assertThat(name()).isEqualTo("web");
        var conflict = conflict();
        assertThat(conflict.get("lost_client_id")).isEqualTo(PHONE);
        assertThat(conflict.get("winner_revision")).isEqualTo(winner);
        assertThat(json.readTree((String) conflict.get("lost_payload")).get("name").asString())
                .isEqualTo("phone, offline");
    }

    @Test
    void tieKeepsTheStoredVersion() throws Exception {
        var base = revision(push(edit(0, T10, PHONE, 1, "phone")).get(0));
        push(edit(base, T12, WEB, 1, "web"));

        var result = push(edit(base, T12, PHONE, 2, "tie")).get(0);

        assertThat(result.get("status").asString()).isEqualTo("superseded");
        assertThat(name()).isEqualTo("web");
    }

    @Test
    void changedAtFromTheFutureIsClampedToServerTime() throws Exception {
        var before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        push(edit(0, Instant.now().plus(365, ChronoUnit.DAYS), PHONE, 1, "clock is wrong"));

        var changedAt = ((java.sql.Timestamp) state().get("changed_at")).toInstant();
        assertThat(changedAt).isBetween(before, Instant.now());
    }

    @Test
    void futureEditDoesNotBeatARealLaterEditForever() throws Exception {
        push(edit(0, Instant.now().plus(365, ChronoUnit.DAYS), PHONE, 1, "from future"));
        Thread.sleep(20);

        var result = push(edit(0, Instant.now(), WEB, 1, "web")).get(0);

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        assertThat(name()).isEqualTo("web");
    }

    @Test
    void repeatedDeliveryFromTheSameClientIsStaleAndChangesNothing() throws Exception {
        var first = push(edit(0, T10, PHONE, 5, "once")).get(0);

        var repeated = push(edit(0, T12, PHONE, 5, "twice")).get(0);
        var older = push(edit(0, T12, PHONE, 4, "older")).get(0);

        assertThat(repeated.get("status").asString()).isEqualTo("stale");
        assertThat(older.get("status").asString()).isEqualTo("stale");
        assertThat(revision(repeated)).isEqualTo(revision(first));
        assertThat(name()).isEqualTo("once");
        assertThat(conflicts()).isZero();
    }

    @Test
    void deletionCompetesByChangedAtLikeAnEdit() throws Exception {
        var base = revision(push(edit(0, T10, PHONE, 1, "phone")).get(0));

        var deletion = push(tombstone(base, T12, WEB, 1)).get(0);
        var lateEdit = push(edit(base, T10.plusSeconds(5), PHONE, 2, "edited offline")).get(0);

        assertThat(deletion.get("status").asString()).isEqualTo("accepted");
        assertThat(lateEdit.get("status").asString()).isEqualTo("superseded");
        assertThat(count(CONTRAINDICATION)).isZero();
        assertThat(state().get("deleted")).isEqualTo(true);
    }

    @Test
    void editNewerThanTheDeletionRestoresTheAggregate() throws Exception {
        var base = revision(push(edit(0, T10, PHONE, 1, "phone")).get(0));
        push(tombstone(base, T10.plusSeconds(5), WEB, 1));

        var result = push(edit(base, T12, PHONE, 2, "restored")).get(0);

        assertThat(result.get("status").asString()).isEqualTo("accepted");
        assertThat(name()).isEqualTo("restored");
        assertThat(state().get("deleted")).isEqualTo(false);
    }

    @Test
    void cannotWriteSomeoneElsesData() throws Exception {
        var item = edit(0, T10, PHONE, 1, "x");
        item.put("ownerId", UUID.randomUUID());

        var result = push(item).get(0);

        assertThat(result.get("status").asString()).isEqualTo("rejected");
        assertThat(result.get("error").asString()).isEqualTo("forbidden");
        assertThat(count(CONTRAINDICATION)).isZero();
    }

    @Test
    void ownerIdEqualToTheCallerIsAllowed() throws Exception {
        var base = revision(push(edit(0, T10, PHONE, 1, "me")).get(0));
        var item = edit(base, T12, PHONE, 2, "me again");
        item.put("ownerId", userId());

        assertThat(push(item).get(0).get("status").asString()).isEqualTo("accepted");
    }

    @Test
    void invalidAndUnsupportedItemsAreRejectedWithoutAffectingTheBatch() throws Exception {
        var unknownSchema = edit(0, T10, PHONE, 1, "x");
        unknownSchema.put("schemaVersion", 2);
        var invalid = edit(0, T10, PHONE, 2, "x");
        invalid.put("payload", json.createObjectNode());
        var unknownType = edit(0, T10, PHONE, 3, "x");
        unknownType.put("type", "photo");

        var results = push(unknownSchema, invalid, unknownType, edit(0, T10, PHONE, 4, "fine"));

        assertThat(results.valueStream().map(r -> r.get("status").asString()))
                .containsExactly("rejected", "rejected", "rejected", "accepted");
        assertThat(results.valueStream().limit(3).map(r -> r.get("error").asString()))
                .containsExactly("unsupported_schema", "invalid_payload", "unsupported_type");
        assertThat(count(CONTRAINDICATION)).isEqualTo(1);
    }

    @Test
    void rejectsMalformedOversizedAndAnonymousRequests() throws Exception {
        var noChangedAt = edit(0, T10, PHONE, 1, "x");
        noChangedAt.remove("changedAt");
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < SyncPushRequest.MAX_ITEMS + 1; i++) {
            items.add(edit(0, T10, PHONE, i + 1, "x"));
        }

        send(Map.of("items", List.of(noChangedAt))).andExpect(status().isBadRequest());
        send(Map.of("items", items)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v2/sync/push").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyPublicationLosesToALaterSyncEditAndWinsOverAnEarlierOne() throws Exception {
        push(edit(0, T12, WEB, 1, "web"));

        var older = legacy(1, T10, "old phone copy");
        var newer = legacy(2, T12.plusSeconds(60), "new phone copy");

        assertThat(legacyStatus(older)).isEqualTo("stale");
        assertThat(name()).isEqualTo("web");
        assertThat(conflicts()).isEqualTo(1);
        assertThat(legacyStatus(newer)).isEqualTo("accepted");
        assertThat(name()).isEqualTo("new phone copy");
        assertThat(conflicts()).isEqualTo(2);
        assertThat(state().get("source_client_id")).isNull();
    }

    @Test
    void legacyPublicationAssignsRevisionAndChangedAtFromTheRootTimestamp() throws Exception {
        assertThat(legacyStatus(legacy(1, T10, "legacy"))).isEqualTo("accepted");

        var state = state();
        assertThat((Long) state.get("revision")).isPositive();
        assertThat(state.get("changed_at")).isEqualTo(java.sql.Timestamp.from(T10));
        assertThat(conflicts()).isZero();
    }

    @Test
    void parallelWritersToOneAggregateLeaveTheLatestEditAndLoseNothingSilently() throws Exception {
        push(edit(0, T10, PHONE, 1, "seed"));
        var userId = userId();
        int writers = 12;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(writers);
        var tasks = new ArrayList<Callable<String>>();
        for (int i = 0; i < writers; i++) {
            var changedAt = T12.plusSeconds(i);
            var item = item(edit(0, changedAt, "client-" + i, 1, "writer " + i));
            tasks.add(() -> {
                start.await();
                return sync.push(userId, List.of(item)).results().getFirst().status().code();
            });
        }
        var futures = tasks.stream().map(pool::submit).toList();
        start.countDown();
        var statuses = new ArrayList<String>();
        for (var future : futures) {
            statuses.add(future.get());
        }
        pool.shutdown();

        assertThat(statuses).allMatch(s -> s.equals("accepted") || s.equals("superseded"));
        assertThat(name()).isEqualTo("writer " + (writers - 1));
        assertThat(state().get("changed_at")).isEqualTo(java.sql.Timestamp.from(T12.plusSeconds(writers - 1)));
        assertThat(conflicts()).isEqualTo(writers);
        assertThat(count(CONTRAINDICATION)).isEqualTo(1);
    }

    @Test
    void recordsMetricsWithoutIdentifiersInTags() throws Exception {
        var base = revision(push(edit(0, T10, PHONE, 1, "a")).get(0));
        var accepted = itemCount("accepted");
        var superseded = itemCount("superseded");
        var conflicts = conflictCount("superseded");
        push(edit(base, T12, WEB, 1, "b"));

        push(edit(base, T10.plusSeconds(1), PHONE, 2, "c"));

        assertThat(itemCount("accepted")).isEqualTo(accepted + 1);
        assertThat(itemCount("superseded")).isEqualTo(superseded + 1);
        assertThat(conflictCount("superseded")).isEqualTo(conflicts + 1);
        assertThat(meters.get("sizylift.sync.push").timer().count()).isPositive();
        meters.getMeters().stream()
                .filter(m -> m.getId().getName().equals("sizylift.sync.push.items")
                        || m.getId().getName().equals("sizylift.sync.conflicts"))
                .forEach(m -> assertThat(m.getId().getTags()).allMatch(
                        tag -> tag.getKey().equals("type") || tag.getKey().equals("status")
                                || tag.getKey().equals("outcome")));
    }

    @Test
    void retentionRemovesOnlyConflictsOlderThanThirtyDays() throws Exception {
        var base = revision(push(edit(0, T10, PHONE, 1, "a")).get(0));
        push(edit(base, T12, WEB, 1, "b"));
        push(edit(base, T10.plusSeconds(1), PHONE, 2, "c"));
        jdbc.update("UPDATE sizylift.aggregate_conflict SET created_at = now() - interval '31 days' "
                + "WHERE user_id = ? AND lost_client_id = ?", userId(), PHONE);

        retention.purge();

        assertThat(conflicts()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sizylift.aggregate_conflict WHERE user_id = ?",
                Long.class, userId())).isZero();
    }

    @SafeVarargs
    private JsonNode push(Map<String, Object>... items) throws Exception {
        var body = send(Map.of("items", List.of(items))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("results");
    }

    private ResultActions send(Object body) throws Exception {
        return mvc.perform(post("/api/v2/sync/push").with(token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private Map<String, Object> edit(long baseRevision, Instant changedAt, String clientId, long clientVersion,
                                     String name) throws Exception {
        var payload = fixture();
        payload.put("name", name);
        var item = tombstone(baseRevision, changedAt, clientId, clientVersion);
        item.put("deleted", false);
        item.put("payload", payload);
        return item;
    }

    private Map<String, Object> tombstone(long baseRevision, Instant changedAt, String clientId,
                                          long clientVersion) {
        var item = new HashMap<String, Object>();
        item.put("type", CONTRAINDICATION);
        item.put("id", id);
        item.put("baseRevision", baseRevision);
        item.put("changedAt", changedAt.toString());
        item.put("clientId", clientId);
        item.put("clientVersion", clientVersion);
        item.put("schemaVersion", 1);
        item.put("deleted", true);
        return item;
    }

    private SyncPushRequest.Item item(Map<String, Object> map) {
        return json.convertValue(map, SyncPushRequest.Item.class);
    }

    private Map<String, Object> legacy(long version, Instant updatedAt, String name) throws Exception {
        var payload = fixture();
        payload.put("name", name);
        payload.put("updatedAt", updatedAt.toString());
        var item = new HashMap<String, Object>();
        item.put("type", CONTRAINDICATION);
        item.put("id", id);
        item.put("version", version);
        item.put("schemaVersion", 1);
        item.put("deleted", false);
        item.put("payload", payload);
        return item;
    }

    private String legacyStatus(Map<String, Object> item) throws Exception {
        var body = mvc.perform(post("/api/v1/publication/batch").with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items", List.of(item)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("results").get(0).get("status").asString();
    }

    private ObjectNode fixture() throws Exception {
        try (var input = new ClassPathResource("publication/contraindication.v1.json").getInputStream()) {
            return (ObjectNode) json.readTree(input);
        }
    }

    private static long revision(JsonNode result) {
        return result.get("revision").asLong();
    }

    private String name() {
        return jdbc.queryForObject("SELECT name FROM sizylift.contraindication WHERE user_id = ? AND id = ?",
                String.class, userId(), id);
    }

    private Map<String, Object> state() {
        return jdbc.queryForMap("""
                SELECT revision, changed_at, author_user_id, source_client_id, source_version, deleted
                FROM sizylift.published_aggregate WHERE user_id = ? AND type = ? AND aggregate_id = ?
                """, userId(), CONTRAINDICATION, id);
    }

    private Map<String, Object> conflict() {
        return jdbc.queryForMap("""
                SELECT lost_payload::text AS lost_payload, lost_changed_at, lost_client_id, winner_revision
                FROM sizylift.aggregate_conflict WHERE user_id = ? AND aggregate_id = ?
                """, userId(), id);
    }

    private long conflicts() {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift.aggregate_conflict WHERE user_id = ? "
                + "AND aggregate_id = ?", Long.class, userId(), id);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM sizylift." + table + " WHERE user_id = ?", Long.class,
                userId());
    }

    private double itemCount(String status) {
        var counter = meters.find("sizylift.sync.push.items").tags("type", CONTRAINDICATION, "status", status)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double conflictCount(String outcome) {
        var counter = meters.find("sizylift.sync.conflicts").tags("type", CONTRAINDICATION, "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private UUID userId() {
        return jdbc.queryForObject("SELECT id FROM sizylift.user_account WHERE issuer = ? AND subject = ?",
                UUID.class, ISSUER, subject);
    }

    private JwtRequestPostProcessor token() {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@example.com"));
    }
}
