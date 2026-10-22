package com.sizylift.backend.notifications.service;

import java.time.Duration;
import java.util.List;

import com.sizylift.backend.notifications.config.NotificationProperties;
import com.sizylift.backend.notifications.entity.TicketRef;
import com.sizylift.backend.notifications.repository.DeviceRepository;
import com.sizylift.backend.notifications.repository.NotificationOutboxRepository;
import com.sizylift.backend.notifications.service.NotificationMetrics.ReceiptOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Квитанции Expo (ADR-0009, раздел 3): проверяются через 15 минут после отправки, Expo хранит их 24 часа.
 * {@code DeviceNotRegistered} обнуляет токен устройства, остальные ошибки идут в метрику.
 */
@Service
public class ReceiptChecker {

    static final Duration MIN_AGE = Duration.ofMinutes(15);
    static final Duration MAX_AGE = Duration.ofHours(24);
    static final int ROWS_PER_RUN = 500;
    private static final Logger log = LoggerFactory.getLogger(ReceiptChecker.class);

    private final NotificationOutboxRepository outbox;
    private final DeviceRepository devices;
    private final ExpoPushClient expo;
    private final NotificationMetrics metrics;
    private final NotificationProperties properties;

    ReceiptChecker(NotificationOutboxRepository outbox, DeviceRepository devices, ExpoPushClient expo,
                   NotificationMetrics metrics, NotificationProperties properties) {
        this.outbox = outbox;
        this.devices = devices;
        this.expo = expo;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${sizylift.notifications.receipts-interval}")
    public void checkScheduled() {
        if (properties.enabled()) {
            checkDue();
        }
    }

    /** Возвращает число проверенных тикетов. */
    public int checkDue() {
        var tickets = outbox.claimReceipts(MIN_AGE, MAX_AGE, ROWS_PER_RUN);
        for (int from = 0; from < tickets.size(); from += ExpoPushClient.MAX_RECEIPTS_PER_REQUEST) {
            check(tickets.subList(from, Math.min(tickets.size(), from + ExpoPushClient.MAX_RECEIPTS_PER_REQUEST)));
        }
        return tickets.size();
    }

    private void check(List<TicketRef> tickets) {
        try {
            var receipts = expo.receipts(tickets.stream().map(TicketRef::ticket).toList());
            for (var ticket : tickets) {
                var receipt = receipts.get(ticket.ticket());
                if (receipt == null) {
                    continue;
                }
                if (receipt.ok()) {
                    metrics.receipt(ReceiptOutcome.OK);
                } else if (ExpoPushClient.DEVICE_NOT_REGISTERED.equals(receipt.error())) {
                    devices.clearToken(ticket.deviceId(), ticket.pushToken());
                    metrics.receipt(ReceiptOutcome.DEVICE_NOT_REGISTERED);
                } else {
                    metrics.receipt(ReceiptOutcome.ERROR);
                }
            }
        } catch (ExpoPushClient.Unavailable | ExpoPushClient.Rejected e) {
            log.warn("Expo receipts unavailable for {} tickets: {}", tickets.size(), e.getClass().getSimpleName());
            metrics.receipt(ReceiptOutcome.UNAVAILABLE);
        }
    }
}
