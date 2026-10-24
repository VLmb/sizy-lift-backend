package com.sizylift.backend.notifications.service;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Expo Push API (https://docs.expo.dev/push-notifications/sending-notifications/, сверено 22.10.2026):
 * до 100 сообщений в запросе, до 1000 id квитанций в запросе. Сетевые ошибки, 429 и 5xx — повторяемые,
 * остальные отказы сами не пройдут.
 */
public class ExpoPushClient {

    /** Ограничения Expo на один запрос. */
    public static final int MAX_MESSAGES_PER_REQUEST = 100;
    public static final int MAX_RECEIPTS_PER_REQUEST = 1000;
    /** Ошибка тикета или квитанции: токен больше не действует, слать на него нельзя. */
    public static final String DEVICE_NOT_REGISTERED = "DeviceNotRegistered";
    static final String STATUS_OK = "ok";
    private static final String SEND_PATH = "/send";
    private static final String RECEIPTS_PATH = "/getReceipts";

    private final RestClient rest;

    public ExpoPushClient(RestClient rest) {
        this.rest = rest;
    }

    /** Тикеты в порядке сообщений. */
    public List<Ticket> send(List<Message> messages) {
        var response = call(() -> rest.post().uri(SEND_PATH).contentType(MediaType.APPLICATION_JSON)
                .body(messages).retrieve().body(SendResponse.class));
        if (response == null || response.data() == null || response.data().size() != messages.size()) {
            throw new Rejected("Unexpected Expo send response");
        }
        return response.data();
    }

    /** Квитанции по id тикетов; тикеты без квитанции в ответе отсутствуют. */
    public Map<String, Receipt> receipts(List<String> ticketIds) {
        var response = call(() -> rest.post().uri(RECEIPTS_PATH).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ids", ticketIds)).retrieve().body(ReceiptsResponse.class));
        return response == null || response.data() == null ? Map.of() : response.data();
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new Unavailable(e);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                throw new Unavailable(e);
            }
            throw new Rejected("Expo rejected the request with status " + e.getStatusCode().value());
        }
    }

    /** Сообщение без данных пользователя: только текст вида и id в {@code data}. */
    public record Message(String to, String title, String body, Map<String, String> data, String sound) {
    }

    public record Ticket(String status, String id, String message, Details details) {

        public boolean ok() {
            return STATUS_OK.equals(status);
        }

        public String error() {
            return details == null ? null : details.error();
        }
    }

    public record Receipt(String status, String message, Details details) {

        public boolean ok() {
            return STATUS_OK.equals(status);
        }

        public String error() {
            return details == null ? null : details.error();
        }
    }

    public record Details(String error) {
    }

    record SendResponse(List<Ticket> data) {
    }

    record ReceiptsResponse(Map<String, Receipt> data) {
    }

    /** Повторяемая ошибка: сеть, 429, 5xx. */
    public static class Unavailable extends RuntimeException {

        Unavailable(Throwable cause) {
            super("Expo push service unavailable", cause);
        }
    }

    /** Отказ, который сам не пройдёт: неверный запрос или доступ. */
    public static class Rejected extends RuntimeException {

        Rejected(String message) {
            super(message);
        }
    }
}
