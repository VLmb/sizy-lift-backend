package com.sizylift.backend.notifications.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.notifications.config.NotificationProperties;
import com.sizylift.backend.notifications.entity.NotificationKind;
import com.sizylift.backend.notifications.repository.DeviceRepository;
import com.sizylift.backend.notifications.repository.DisplayNameRepository;
import com.sizylift.backend.notifications.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Отправка и квитанции против заглушки Expo (MockRestServiceServer); в CI реальных вызовов нет. */
@SpringBootTest
class NotificationSenderTest extends PostgresIntegrationTest {

    static final String EXPO = "https://exp.test/--/api/v2/push";
    static final String TOKEN_A = "ExponentPushToken[aaaaaaaaaaaaaaaaaaaaaa]";
    static final String TOKEN_B = "ExponentPushToken[bbbbbbbbbbbbbbbbbbbbbb]";

    @Autowired
    NotificationOutboxRepository outbox;

    @Autowired
    DeviceRepository devices;

    @Autowired
    DisplayNameRepository names;

    @Autowired
    NotificationMetrics metrics;

    @Autowired
    NotificationQueue queue;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Clock clock;

    MockRestServiceServer expo;
    NotificationSender sender;
    ReceiptChecker receipts;

    @BeforeEach
    void stubExpo() {
        // Очередь общая для всех тестов: строки других классов сюда не попадают.
        jdbc.update("UPDATE sizylift.notification_outbox SET gave_up_at = now() "
                + "WHERE sent_at IS NULL AND gave_up_at IS NULL");
        var builder = RestClient.builder().baseUrl(EXPO)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-access-token");
        expo = MockRestServiceServer.bindTo(builder).build();
        var client = new ExpoPushClient(builder.build());
        var properties = new NotificationProperties(true, null);
        sender = new NotificationSender(outbox, devices, names, client, metrics, properties, clock);
        receipts = new ReceiptChecker(outbox, devices, client, metrics, properties);
    }

    @Test
    void sendsOneMessagePerDeviceWithoutUserDataAndDropsUnregisteredTokens() throws Exception {
        var user = newUser();
        // Сообщения идут в порядке id устройств: телефон первым, ему тикет ok, планшету — ошибка.
        var ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(2)
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        var phone = ids.get(0);
        var tablet = ids.get(1);
        devices.register(phone, user, "ios", TOKEN_A);
        devices.register(tablet, user, "android", TOKEN_B);
        var program = UUID.randomUUID();
        enqueue(user, NotificationKind.PROGRAM_UPDATED, program);
        expo.expect(once(), requestTo(EXPO + "/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-access-token"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Тренер обновил программу"))
                .andExpect(jsonPath("$[0].data.kind").value("program_updated"))
                .andExpect(jsonPath("$[0].data.aggregateId").value(program.toString()))
                .andRespond(withSuccess(tickets("""
                        {"status":"ok","id":"ticket-1"}""", """
                        {"status":"error","message":"gone","details":{"error":"DeviceNotRegistered"}}"""),
                        MediaType.APPLICATION_JSON));

        assertThat(sender.sendDue()).isEqualTo(1);

        expo.verify();
        var row = row(user);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("ticket_ids").toString()).contains("ticket-1").doesNotContain(TOKEN_B);
        var tokens = jdbc.queryForMap("SELECT "
                + "(SELECT push_token FROM sizylift.device WHERE id = ?) AS a, "
                + "(SELECT push_token FROM sizylift.device WHERE id = ?) AS b", phone, tablet);
        assertThat(tokens.get("a")).isEqualTo(TOKEN_A);
        assertThat(tokens.get("b")).as("DeviceNotRegistered обнуляет токен").isNull();
    }

    @Test
    void serverErrorsAreRetriedWithBackoffAndThenGivenUp() {
        var user = newUser();
        devices.register(UUID.randomUUID(), user, "ios", TOKEN_A);
        enqueue(user, NotificationKind.COMMENT_ADDED, UUID.randomUUID());
        expo.expect(times(NotificationSender.MAX_ATTEMPTS), requestTo(EXPO + "/send"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var before = Instant.now();
        sender.sendDue();
        var first = row(user);
        assertThat(first.get("attempts")).isEqualTo(1);
        assertThat(first.get("sent_at")).isNull();
        assertThat(((java.sql.Timestamp) first.get("next_attempt_at")).toInstant())
                .isAfterOrEqualTo(before.plus(NotificationSender.FIRST_RETRY).minusSeconds(1));

        for (int i = 1; i < NotificationSender.MAX_ATTEMPTS; i++) {
            jdbc.update("UPDATE sizylift.notification_outbox SET next_attempt_at = now() WHERE user_id = ?", user);
            sender.sendDue();
        }

        expo.verify();
        var last = row(user);
        assertThat(last.get("attempts")).isEqualTo(NotificationSender.MAX_ATTEMPTS);
        assertThat(last.get("gave_up_at")).isNotNull();
        assertThat(last.get("sent_at")).isNull();
        assertThat(meters.find("sizylift.notifications.sent").tags("kind", "comment_added", "outcome", "gave_up")
                .counter().count()).isPositive();
    }

    @Test
    void tooManyRequestsIsRetriedAndBadRequestIsNot() {
        var retried = newUser();
        var rejected = newUser();
        devices.register(UUID.randomUUID(), retried, "ios", TOKEN_A);
        enqueue(retried, NotificationKind.COMMENT_ADDED, UUID.randomUUID());
        expo.expect(once(), requestTo(EXPO + "/send")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        sender.sendDue();
        assertThat(row(retried).get("gave_up_at")).isNull();
        assertThat(row(retried).get("attempts")).isEqualTo(1);

        expo.reset();
        jdbc.update("UPDATE sizylift.notification_outbox SET gave_up_at = now() WHERE gave_up_at IS NULL "
                + "AND sent_at IS NULL");
        devices.register(UUID.randomUUID(), rejected, "ios", TOKEN_B);
        enqueue(rejected, NotificationKind.COMMENT_ADDED, UUID.randomUUID());
        expo.expect(once(), requestTo(EXPO + "/send")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"code\":\"VALIDATION_ERROR\",\"message\":\"bad\"}]}"));
        sender.sendDue();
        expo.verify();
        assertThat(row(rejected).get("gave_up_at")).isNotNull();
    }

    @Test
    void userWithoutDevicesIsMarkedSentWithoutCallingExpo() {
        var user = newUser();
        enqueue(user, NotificationKind.WORKOUT_COMPLETED, UUID.randomUUID());

        sender.sendDue();

        expo.verify();
        assertThat(row(user).get("sent_at")).isNotNull();
    }

    @Test
    void largeBatchesAreSplitIntoRequestsOfAtMostOneHundredMessages() {
        int users = ExpoPushClient.MAX_MESSAGES_PER_REQUEST + 1;
        for (int i = 0; i < users; i++) {
            var user = newUser();
            devices.register(UUID.randomUUID(), user, "android",
                    "ExponentPushToken[" + UUID.randomUUID().toString().replace("-", "") + "]");
            enqueue(user, NotificationKind.WORKOUT_COMPLETED, UUID.randomUUID());
        }
        expo.expect(once(), requestTo(EXPO + "/send")).andExpect(jsonPath("$.length()").value(100))
                .andRespond(withSuccess(okTickets(100), MediaType.APPLICATION_JSON));
        expo.expect(once(), requestTo(EXPO + "/send")).andExpect(jsonPath("$.length()").value(users - 100))
                .andRespond(withSuccess(okTickets(users - 100), MediaType.APPLICATION_JSON));

        var processed = sender.sendDue() + sender.sendDue();

        expo.verify();
        assertThat(processed).isEqualTo(users);
    }

    @Test
    void receiptsAreCheckedAfterFifteenMinutesAndUnregisteredDevicesLoseTheirToken() {
        var user = newUser();
        var phone = UUID.randomUUID();
        devices.register(phone, user, "ios", TOKEN_A);
        enqueue(user, NotificationKind.PROGRAM_UPDATED, UUID.randomUUID());
        expo.expect(once(), requestTo(EXPO + "/send"))
                .andRespond(withSuccess(tickets("{\"status\":\"ok\",\"id\":\"ticket-r\"}"),
                        MediaType.APPLICATION_JSON));
        expo.expect(once(), requestTo(EXPO + "/getReceipts"))
                .andExpect(jsonPath("$.ids[0]").value("ticket-r"))
                .andRespond(withSuccess("""
                        {"data":{"ticket-r":{"status":"error","message":"gone",
                        "details":{"error":"DeviceNotRegistered"}}}}""", MediaType.APPLICATION_JSON));
        sender.sendDue();
        assertThat(receipts.checkDue()).as("квитанции раньше 15 минут не запрашиваются").isZero();

        jdbc.update("UPDATE sizylift.notification_outbox SET sent_at = now() - interval '20 minutes' "
                + "WHERE user_id = ?", user);

        assertThat(receipts.checkDue()).isEqualTo(1);
        assertThat(receipts.checkDue()).as("квитанция проверяется один раз").isZero();

        expo.verify();
        assertThat(jdbc.queryForObject("SELECT push_token FROM sizylift.device WHERE id = ?", String.class, phone))
                .isNull();
    }

    @Test
    void reRegisteredTokenIsNotClearedByAnOldReceipt() {
        var user = newUser();
        var phone = UUID.randomUUID();
        devices.register(phone, user, "ios", TOKEN_A);
        enqueue(user, NotificationKind.PROGRAM_UPDATED, UUID.randomUUID());
        expo.expect(once(), requestTo(EXPO + "/send"))
                .andRespond(withSuccess(tickets("{\"status\":\"ok\",\"id\":\"ticket-old\"}"),
                        MediaType.APPLICATION_JSON));
        expo.expect(once(), requestTo(EXPO + "/getReceipts")).andRespond(withSuccess("""
                {"data":{"ticket-old":{"status":"error","details":{"error":"DeviceNotRegistered"}}}}""",
                MediaType.APPLICATION_JSON));
        sender.sendDue();
        devices.register(phone, user, "ios", TOKEN_B);
        jdbc.update("UPDATE sizylift.notification_outbox SET sent_at = now() - interval '20 minutes' "
                + "WHERE user_id = ?", user);

        receipts.checkDue();

        assertThat(jdbc.queryForObject("SELECT push_token FROM sizylift.device WHERE id = ?", String.class, phone))
                .isEqualTo(TOKEN_B);
    }

    @Test
    void queueGaugesAreRegistered() {
        assertThat(meters.get("sizylift.notifications.queue.size").gauge().value()).isGreaterThanOrEqualTo(0);
        assertThat(meters.get("sizylift.notifications.queue.oldest.age").gauge().value())
                .isGreaterThanOrEqualTo(0);
    }

    private void enqueue(UUID user, NotificationKind kind, UUID subject) {
        transactions.executeWithoutResult(status -> queue.enqueue(user, kind, subject,
                Map.of(NotificationQueue.AGGREGATE_ID, subject.toString())));
    }

    private Map<String, Object> row(UUID user) {
        return jdbc.queryForMap("SELECT sent_at, gave_up_at, attempts, next_attempt_at, ticket_ids::text "
                + "AS ticket_ids FROM sizylift.notification_outbox WHERE user_id = ?", user);
    }

    private static String tickets(String... tickets) {
        return "{\"data\":[" + String.join(",", tickets) + "]}";
    }

    private static String okTickets(int count) {
        var items = new String[count];
        for (int i = 0; i < count; i++) {
            items[i] = "{\"status\":\"ok\",\"id\":\"t-" + i + "\"}";
        }
        return tickets(items);
    }

    private UUID newUser() {
        return jdbc.queryForObject("INSERT INTO sizylift.user_account (issuer, subject) VALUES ('test', ?) "
                + "RETURNING id", UUID.class, UUID.randomUUID().toString());
    }
}
