package com.sizylift.backend.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.publication.dto.payload.AthleteProfilePayloadV1;
import com.sizylift.backend.publication.dto.payload.ContraindicationPayloadV1;
import com.sizylift.backend.publication.dto.payload.ExercisePayloadV1;
import com.sizylift.backend.publication.dto.payload.InjuryPayloadV1;
import com.sizylift.backend.publication.dto.payload.OneRepMaxPayloadV1;
import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;
import com.sizylift.backend.sync.dto.SyncPushRequest;
import com.sizylift.backend.sync.service.SyncChangesService;
import com.sizylift.backend.sync.service.SyncPushService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Лента изменений (docs/sync.md): одна строка на агрегат, курсор, payload v1 из таблиц. */
@SpringBootTest
@AutoConfigureMockMvc
class SyncChangesApiTest extends PostgresIntegrationTest {

    static final String ISSUER = "http://keycloak.test/realms/sizylift";
    static final String CONTRAINDICATION = "contraindication";
    static final String PHONE = "phone-1";
    static final Instant T10 = Instant.parse("2026-10-20T10:00:00Z");
    static final Map<String, Class<?>> PAYLOAD_TYPES = Map.of(
            "program", ProgramPayloadV1.class,
            "athlete_profile", AthleteProfilePayloadV1.class,
            "one_rep_max", OneRepMaxPayloadV1.class,
            "injury", InjuryPayloadV1.class,
            "contraindication", ContraindicationPayloadV1.class);
    /**
     * Необязательные поля, которые сервер уже отдаёт, а общие фикстуры приложения ещё не содержат
     * (docs/coach-workspace.md, разделы 1–2).
     */
    static final Map<String, List<String>> SERVER_ADDED_FIELDS = Map.of(
            "exercise", List.of("liftClass"),
            "program", List.of("cycles.[].weeks.[].workouts.[].blocks.[].exercises.[].sets.[]."
                    + "targetPercentOneRmTenths"));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MeterRegistry meters;

    @Autowired
    SyncPushService push;

    @Autowired
    SyncChangesService changes;

    String subject;

    @BeforeEach
    void newUser() {
        subject = UUID.randomUUID().toString();
    }

    /** Id случайные: id своих упражнений уникальны глобально, а база общая для всех тестов. */
    @ParameterizedTest
    @ValueSource(strings = {"workout", "program", "exercise", "athlete_profile", "one_rep_max", "injury",
            "contraindication"})
    void feedReturnsEveryAggregateTypeAsTheSharedV1Fixture(String type) throws Exception {
        var id = UUID.randomUUID();
        var fixture = fixture(type);
        assertThat(legacy(type, id, fixture)).isEqualTo("accepted");

        var change = read(0, 10).get("changes").get(0);

        assertThat(change.get("type").asString()).isEqualTo(type);
        assertThat(change.get("id").asString()).isEqualTo(id.toString());
        assertThat(change.get("kind").asString()).isEqualTo("upsert");
        assertThat(change.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(change.get("ownerId").asString()).isEqualTo(userId().toString());
        assertThat(normalized(type, change.get("payload"))).isEqualTo(normalized(type, fixture));
        assertThat(fieldNames(change.get("payload")))
                .isEqualTo(withServerAddedFields(type, fieldNames(fixture)));
        assertThat(change.get("payload").get("updatedAt").isString()).isTrue();
    }

    @Test
    void feedKeepsOneEntryPerAggregateWithTheLatestRevision() throws Exception {
        var id = UUID.randomUUID();
        var other = UUID.randomUUID();
        var first = pushEdit(id, 0, 1, "v1");
        pushEdit(other, 0, 1, "other");
        var latest = pushEdit(id, first, 2, "v2");

        var page = read(0, 10).get("changes");

        assertThat(page.size()).isEqualTo(2);
        assertThat(page.get(0).get("id").asString()).isEqualTo(other.toString());
        assertThat(page.get(1).get("id").asString()).isEqualTo(id.toString());
        assertThat(page.get(1).get("revision").asLong()).isEqualTo(latest);
        assertThat(page.get(1).get("payload").get("name").asString()).isEqualTo("v2");
        assertThat(page.get(1).get("changedAt").asString()).isEqualTo(T10.plusSeconds(2).toString());
    }

    @Test
    void deletionReachesTheFeedWithoutPayload() throws Exception {
        var id = UUID.randomUUID();
        var base = pushEdit(id, 0, 1, "doomed");
        var tombstone = item(id, base, 2, null);

        assertThat(push.push(userId(), List.of(tombstone)).results().getFirst().status().code())
                .isEqualTo("accepted");

        var change = read(0, 10).get("changes").get(0);
        assertThat(change.get("kind").asString()).isEqualTo("delete");
        assertThat(change.get("payload").isNull()).isTrue();
    }

    @Test
    void cursorPagesThroughTheFeedInOrder() throws Exception {
        for (int i = 0; i < 5; i++) {
            pushEdit(UUID.randomUUID(), 0, 1, "n" + i);
        }

        var first = read(0, 2);
        var second = read(first.get("nextCursor").asLong(), 2);
        var third = read(second.get("nextCursor").asLong(), 2);
        var empty = read(third.get("nextCursor").asLong(), 2);

        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(third.get("hasMore").asBoolean()).isFalse();
        assertThat(third.get("changes").size()).isEqualTo(1);
        assertThat(empty.get("changes").size()).isZero();
        assertThat(empty.get("nextCursor").asLong()).isEqualTo(third.get("nextCursor").asLong());
        var names = new ArrayList<String>();
        for (var page : List.of(first, second, third)) {
            page.get("changes").forEach(change -> names.add(change.get("payload").get("name").asString()));
        }
        assertThat(names).containsExactly("n0", "n1", "n2", "n3", "n4");
    }

    @Test
    void feedsAreSeparatePerUser() throws Exception {
        pushEdit(UUID.randomUUID(), 0, 1, "mine");

        subject = UUID.randomUUID().toString();
        assertThat(read(0, 10).get("changes").size()).isZero();
    }

    @Test
    void rejectsInvalidParametersAndAnonymousRequests() throws Exception {
        mvc.perform(get("/api/v2/sync/changes").param("limit", "0").with(token())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/sync/changes").param("limit", "501").with(token()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/sync/changes").param("after", "-1").with(token()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/sync/changes")).andExpect(status().isUnauthorized());
    }

    @Test
    void recordsPageSize() throws Exception {
        var pages = meters.find("sizylift.sync.changes.page.size").summary();
        var before = pages == null ? 0 : pages.count();

        read(0, 10);

        assertThat(meters.get("sizylift.sync.changes.page.size").summary().count()).isEqualTo(before + 1);
    }

    /**
     * Писатели параллельно правят агрегаты одного владельца, читатель одновременно идёт по ленте курсором.
     * Каждая ревизия видна не больше одного раза, позиции растут, а после дочитывания у читателя последняя
     * ревизия каждого агрегата.
     */
    @Test
    void concurrentWritersAndCursorReaderNeitherSkipNorRepeat() throws Exception {
        var owner = userId();
        int writers = 8;
        int aggregatesPerWriter = 10;
        int editsPerAggregate = 3;
        var start = new CountDownLatch(1);
        var writing = new AtomicBoolean(true);
        var pool = Executors.newFixedThreadPool(writers + 1);
        var futures = new ArrayList<Future<?>>();
        for (int w = 0; w < writers; w++) {
            var clientId = "client-" + w;
            futures.add(pool.submit(() -> {
                start.await();
                writeAggregates(owner, clientId, aggregatesPerWriter, editsPerAggregate);
                return null;
            }));
        }
        var seen = new ConcurrentHashMap<UUID, Long>();
        var seenRevisions = new HashSet<String>();
        var reader = pool.submit(() -> {
            start.await();
            var cursor = 0L;
            while (true) {
                var stillWriting = writing.get();
                var page = changes.changes(owner, cursor, 7);
                for (var change : page.changes()) {
                    assertThat(change.seq()).isGreaterThan(cursor);
                    cursor = change.seq();
                    assertThat(seenRevisions.add(change.id() + "@" + change.revision())).isTrue();
                    seen.merge(change.id(), change.revision(), Math::max);
                }
                if (!stillWriting && !page.hasMore()) {
                    return cursor;
                }
            }
        });
        start.countDown();
        for (var future : futures) {
            future.get();
        }
        writing.set(false);
        reader.get();
        pool.shutdown();

        var finalRevisions = new HashMap<UUID, Long>();
        jdbc.query("SELECT aggregate_id, revision FROM sizylift.published_aggregate WHERE user_id = ?",
                row -> {
                    finalRevisions.put(row.getObject(1, UUID.class), row.getLong(2));
                }, owner);
        assertThat(finalRevisions).hasSize(writers * aggregatesPerWriter);
        assertThat(seen).isEqualTo(finalRevisions);
    }

    private void writeAggregates(UUID owner, String clientId, int aggregates, int edits) {
        var ids = new ArrayList<UUID>();
        for (int a = 0; a < aggregates; a++) {
            ids.add(UUID.randomUUID());
        }
        var revisions = new HashMap<UUID, Long>();
        for (int e = 1; e <= edits; e++) {
            for (var id : ids) {
                var result = push.push(owner, List.of(item(id, revisions.getOrDefault(id, 0L), e, clientId,
                        "edit " + e))).results().getFirst();
                assertThat(result.status().code()).isEqualTo("accepted");
                revisions.put(id, result.revision());
            }
        }
    }

    private long pushEdit(UUID id, long baseRevision, long clientVersion, String name) throws Exception {
        var result = push.push(userId(), List.of(item(id, baseRevision, clientVersion, PHONE, name)))
                .results().getFirst();
        assertThat(result.status().code()).isEqualTo("accepted");
        return result.revision();
    }

    private SyncPushRequest.Item item(UUID id, long baseRevision, long clientVersion, String name)
            throws Exception {
        return item(id, baseRevision, clientVersion, PHONE, name);
    }

    private SyncPushRequest.Item item(UUID id, long baseRevision, long clientVersion, String clientId,
                                      String name) {
        ObjectNode payload = null;
        if (name != null) {
            payload = json.createObjectNode().put("name", name).putNull("description")
                    .put("updatedAt", T10.toString());
        }
        return new SyncPushRequest.Item(CONTRAINDICATION, id, null, baseRevision, T10.plusSeconds(clientVersion),
                clientId, clientVersion, 1, payload == null, payload);
    }

    private JsonNode read(long after, int limit) throws Exception {
        var body = mvc.perform(get("/api/v2/sync/changes").param("after", String.valueOf(after))
                        .param("limit", String.valueOf(limit)).with(token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String legacy(String type, UUID id, JsonNode payload) throws Exception {
        var item = new HashMap<String, Object>();
        item.put("type", type);
        item.put("id", id);
        item.put("version", 1);
        item.put("schemaVersion", 1);
        item.put("deleted", false);
        item.put("payload", payload);
        var body = mvc.perform(post("/api/v1/publication/batch").with(token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items", List.of(item)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("results").get(0).get("status").asString();
    }

    /**
     * Payload как значение: время сравнивается как момент, а не как строка ({@code .000Z} в фикстурах);
     * списки без позиции — без учёта порядка.
     */
    private Object normalized(String type, JsonNode payload) {
        return switch (type) {
            case "workout" -> {
                var workout = json.treeToValue(payload, WorkoutPayloadV1.class);
                yield new WorkoutPayloadV1(workout.workoutDate(), workout.title(), workout.description(),
                        workout.startedAt(), workout.completedAt(), workout.quality(), workout.plannedWorkoutId(),
                        workout.updatedAt(), workout.blocks(),
                        sorted(workout.planSetSnapshots(), Comparator.comparing(s -> s.id().toString())),
                        sorted(workout.recordEvents(), Comparator.comparing(e -> e.id().toString())));
            }
            case "exercise" -> {
                var exercise = json.treeToValue(payload, ExercisePayloadV1.class);
                yield ExercisePayloadV1.stored(exercise.name(), exercise.shortName(), exercise.movementCategoryId(),
                        exercise.resultType(), exercise.liftClassToStore(null), exercise.note(), exercise.updatedAt(),
                        sorted(exercise.muscles(), Comparator.comparing(m -> m.muscleGroupId().toString())),
                        sorted(exercise.equipmentIds(), Comparator.comparing(UUID::toString)));
            }
            default -> json.treeToValue(payload, PAYLOAD_TYPES.get(type));
        };
    }

    private static List<String> withServerAddedFields(String type, List<String> names) {
        return Stream.concat(names.stream(), SERVER_ADDED_FIELDS.getOrDefault(type, List.of()).stream())
                .distinct().sorted().toList();
    }

    private static <T> List<T> sorted(List<T> values, Comparator<T> order) {
        return values.stream().sorted(order).toList();
    }

    /** Имена полей на всех уровнях: лента отдаёт ровно контракт v1, без лишних и пропущенных полей. */
    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        collectFieldNames(node, "", names);
        return names.stream().distinct().sorted().toList();
    }

    private static void collectFieldNames(JsonNode node, String path, List<String> names) {
        if (node.isObject()) {
            for (var name : node.propertyNames()) {
                names.add(path + name);
                collectFieldNames(node.get(name), path + name + ".", names);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, path + "[].", names));
        }
    }

    private ObjectNode fixture(String type) throws Exception {
        try (var input = new ClassPathResource("publication/" + type + ".v1.json").getInputStream()) {
            return (ObjectNode) json.readTree(input);
        }
    }

    private UUID userId() throws Exception {
        mvc.perform(get("/api/v1/me").with(token())).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM sizylift.user_account WHERE issuer = ? AND subject = ?",
                UUID.class, ISSUER, subject);
    }

    private JwtRequestPostProcessor token() {
        return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("email", subject + "@example.com"));
    }
}
