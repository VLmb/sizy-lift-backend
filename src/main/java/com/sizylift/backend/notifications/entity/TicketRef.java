package com.sizylift.backend.notifications.entity;

import java.util.UUID;

/**
 * Тикет Expo и устройство, которому ушло сообщение; токен нужен, чтобы по квитанции {@code DeviceNotRegistered}
 * обнулить именно его, а не новый токен того же устройства.
 */
public record TicketRef(String ticket, UUID deviceId, String pushToken) {
}
