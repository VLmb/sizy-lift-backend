package com.sizylift.backend.notifications.entity;

import java.util.Arrays;

/**
 * Вид уведомления и его общий текст без данных пользователя: экран блокировки видят посторонние
 * (ADR-0009, раздел 4). Имя второй стороны допустимо и подставляется в текст при отправке.
 */
public enum NotificationKind {

    PROGRAM_UPDATED("program_updated", "Тренер обновил программу"),
    WORKOUT_PLANNED("workout_planned", "Тренер запланировал тренировку"),
    COMMENT_ADDED("comment_added", "Новый комментарий к тренировке"),
    WORKOUT_COMPLETED("workout_completed", "Подопечный завершил тренировку"),
    WORKOUT_RESCHEDULED("workout_rescheduled", "Подопечный перенёс тренировку"),
    INVITE_ACCEPTED("invite_accepted", "Приглашение принято");

    private final String code;
    private final String title;

    NotificationKind(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public static NotificationKind of(String code) {
        return Arrays.stream(values()).filter(kind -> kind.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification kind"));
    }
}
