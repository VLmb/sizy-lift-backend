package com.sizylift.backend.publication.service;

import java.time.LocalDate;

/** What the user allows the server to keep. */
public record PublicationScope(
        boolean enabled,
        boolean workouts,
        LocalDate workoutsSince,
        Programs programs,
        boolean profile,
        boolean records,
        boolean health) {

    /** Everything except injuries and contraindications, which need a separate consent. */
    public static final PublicationScope DEFAULT =
            new PublicationScope(true, true, null, Programs.ALL, true, true, false);

    public boolean allowsExercises() {
        return workouts || programs != Programs.NONE;
    }

    public enum Programs {
        NONE("none"), ACTIVE("active"), ALL("all");

        private final String code;

        Programs(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Programs fromCode(String code) {
            for (var value : values()) {
                if (value.code.equals(code)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown programs scope: " + code);
        }
    }
}
