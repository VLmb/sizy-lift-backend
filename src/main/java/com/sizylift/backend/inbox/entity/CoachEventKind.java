package com.sizylift.backend.inbox.entity;

/** Типы событий «Входящих»; {@link #code()} хранится в {@code coach_event.kind} и уходит в API. */
public enum CoachEventKind {
    WORKOUT_COMPLETED("workout_completed"),
    WORKOUT_RESCHEDULED("workout_rescheduled"),
    WORKOUT_MISSED("workout_missed"),
    PROGRAM_ENDING("program_ending"),
    INVITE_ACCEPTED("invite_accepted");

    private final String code;

    CoachEventKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CoachEventKind fromCode(String code) {
        for (var kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown coach event kind: " + code);
    }
}
