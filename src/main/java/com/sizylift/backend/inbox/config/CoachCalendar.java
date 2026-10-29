package com.sizylift.backend.inbox.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

/** «Сегодня» рабочего места тренера — в зоне из настроек, а не в UTC сервера. */
@Component
public class CoachCalendar {

    private final Clock clock;
    private final CoachWorkspaceProperties properties;

    CoachCalendar(Clock clock, CoachWorkspaceProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), properties.zone());
    }

    public String zoneId() {
        return properties.zone().getId();
    }
}
