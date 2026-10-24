package com.sizylift.backend.notifications.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.sizylift.backend.notifications.config.NotificationProperties;
import com.sizylift.backend.notifications.entity.PendingNotification;
import com.sizylift.backend.notifications.entity.PushTarget;
import com.sizylift.backend.notifications.entity.TicketRef;
import com.sizylift.backend.notifications.repository.DeviceRepository;
import com.sizylift.backend.notifications.repository.DisplayNameRepository;
import com.sizylift.backend.notifications.repository.NotificationOutboxRepository;
import com.sizylift.backend.notifications.service.ExpoPushClient.Message;
import com.sizylift.backend.notifications.service.NotificationMetrics.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Отправляет outbox в Expo (ADR-0009, раздел 3). Строки забираются с арендой через {@code SKIP LOCKED},
 * поэтому HTTP-запрос не держит транзакцию, а второй экземпляр не возьмёт те же строки. Сообщения одной
 * строки не делятся между запросами: повтор после ошибки не задвоит уже доставленное.
 */
@Service
public class NotificationSender {

    static final int BATCH_ROWS = 100;
    static final int MAX_ATTEMPTS = 6;
    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    static final Duration MAX_RETRY = Duration.ofHours(1);
    static final String SOUND = "default";
    static final String KIND = "kind";
    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final NotificationOutboxRepository outbox;
    private final DeviceRepository devices;
    private final DisplayNameRepository names;
    private final ExpoPushClient expo;
    private final NotificationMetrics metrics;
    private final NotificationProperties properties;
    private final Clock clock;

    NotificationSender(NotificationOutboxRepository outbox, DeviceRepository devices, DisplayNameRepository names,
                       ExpoPushClient expo, NotificationMetrics metrics, NotificationProperties properties,
                       Clock clock) {
        this.outbox = outbox;
        this.devices = devices;
        this.names = names;
        this.expo = expo;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** В dev и тестах отправка выключена: строки копятся в outbox. */
    @Scheduled(fixedDelayString = "${sizylift.notifications.send-interval}")
    public void sendScheduled() {
        if (properties.enabled()) {
            sendDue();
        }
    }

    /** Одна пачка; возвращает число обработанных строк. */
    public int sendDue() {
        var rows = outbox.claimDue(BATCH_ROWS, LEASE);
        if (rows.isEmpty()) {
            return 0;
        }
        var targets = devices.targets(rows.stream().map(PendingNotification::userId).distinct().toList()).stream()
                .collect(Collectors.groupingBy(PushTarget::userId));
        var actorNames = names.namesOf(rows.stream().map(row -> row.data().get(NotificationQueue.ACTOR_ID))
                .filter(Objects::nonNull).map(UUID::fromString).distinct().toList());
        var chunk = new ArrayList<Delivery>();
        var chunkSize = 0;
        for (var row : rows) {
            var rowTargets = targets.getOrDefault(row.userId(), List.of()).stream()
                    .limit(ExpoPushClient.MAX_MESSAGES_PER_REQUEST).toList();
            if (rowTargets.isEmpty()) {
                outbox.markSent(row.id(), List.of());
                metrics.sent(row.kind(), Outcome.NO_DEVICE);
                continue;
            }
            if (chunkSize + rowTargets.size() > ExpoPushClient.MAX_MESSAGES_PER_REQUEST) {
                deliver(chunk);
                chunk = new ArrayList<>();
                chunkSize = 0;
            }
            chunk.add(new Delivery(row, rowTargets, message(row, actorNames)));
            chunkSize += rowTargets.size();
        }
        deliver(chunk);
        return rows.size();
    }

    private void deliver(List<Delivery> chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        var messages = new ArrayList<Message>();
        chunk.forEach(delivery -> delivery.targets().forEach(target -> messages.add(delivery.message(target))));
        try {
            var tickets = expo.send(messages);
            var position = 0;
            for (var delivery : chunk) {
                var refs = new ArrayList<TicketRef>();
                for (var target : delivery.targets()) {
                    accept(tickets.get(position++), target, refs);
                }
                outbox.markSent(delivery.row().id(), refs);
                metrics.sent(delivery.row().kind(), refs.isEmpty() ? Outcome.FAILED : Outcome.SENT);
            }
        } catch (ExpoPushClient.Unavailable e) {
            log.warn("Expo push unavailable, {} notifications will be retried", chunk.size());
            chunk.forEach(delivery -> retry(delivery.row()));
        } catch (ExpoPushClient.Rejected e) {
            log.error("Expo push rejected {} notifications: {}", chunk.size(), e.getMessage());
            chunk.forEach(delivery -> {
                outbox.giveUp(delivery.row().id());
                metrics.sent(delivery.row().kind(), Outcome.GAVE_UP);
            });
        }
    }

    private void accept(ExpoPushClient.Ticket ticket, PushTarget target, List<TicketRef> refs) {
        if (ticket.ok()) {
            refs.add(new TicketRef(ticket.id(), target.deviceId(), target.pushToken()));
        } else if (ExpoPushClient.DEVICE_NOT_REGISTERED.equals(ticket.error())) {
            devices.clearToken(target.deviceId(), target.pushToken());
        }
    }

    /** Экспоненциальная пауза; после {@link #MAX_ATTEMPTS} строка остаётся неотправленной. */
    private void retry(PendingNotification row) {
        var attempt = row.attempts() + 1;
        if (attempt >= MAX_ATTEMPTS) {
            outbox.giveUp(row.id());
            metrics.sent(row.kind(), Outcome.GAVE_UP);
            return;
        }
        var delay = FIRST_RETRY.multipliedBy(1L << row.attempts());
        outbox.retryAt(row.id(), clock.instant().plus(delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay));
        metrics.sent(row.kind(), Outcome.RETRY);
    }

    private static MessageTemplate message(PendingNotification row, Map<UUID, String> actorNames) {
        var data = new HashMap<>(row.data());
        data.put(KIND, row.kind().code());
        var actor = row.data().get(NotificationQueue.ACTOR_ID);
        var body = actor == null ? null : actorNames.get(UUID.fromString(actor));
        return new MessageTemplate(row.kind().title(), body, Map.copyOf(data));
    }

    private record MessageTemplate(String title, String body, Map<String, String> data) {
    }

    private record Delivery(PendingNotification row, List<PushTarget> targets, MessageTemplate template) {

        Message message(PushTarget target) {
            return new Message(target.pushToken(), template.title(), template.body(), template.data(), SOUND);
        }
    }
}
