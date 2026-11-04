package com.sizylift.backend.imports.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.sizylift.backend.imports.dto.ImportDtos.Progress;
import com.sizylift.backend.imports.dto.ImportPlan;
import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.imports.repository.ImportRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ImportProgressTest {
    private final ImportRepository sessions = mock(ImportRepository.class);
    private final ImportSession session = mock(ImportSession.class);
    private Instant now = Instant.parse("2026-11-04T10:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final ImportProgress progress = new ImportProgress(sessions, clock);

    @Test
    void thePlanReportsFoundExercisesAndPlannedWeeksAtMostOncePerSecond() {
        when(sessions.progress(eq(session), any(), any())).thenReturn(true);
        var tracker = progress.plan(session);
        var call = tracker.call();
        call.onArguments("{\"exercises\":[{\"id\":\"e1\"},{\"id\":\"e2\"}],");
        now = now.plus(Duration.ofSeconds(2));
        call.onArguments("\"plan\":[{\"week\":1},{\"week\":2},{\"week\":3}],\"weeks\":[");
        assertThat(written()).containsExactly(new Progress("plan", 0, 0, null), new Progress("plan", 2, 0, 3));
    }

    @Test
    void weeksCountCompletedPartsAndStreamedWeeksAndNeverGoBack() {
        when(sessions.progress(eq(session), any(), any())).thenReturn(true);
        var plan = new ImportPlan("", "", List.of(), List.of(new ImportPlan.Week(1, "", "", 1), new ImportPlan.Week(2, "", "", 1),
                new ImportPlan.Week(3, "", "", 1), new ImportPlan.Week(4, "", "", 1)));
        var tracker = progress.weeks(session, plan);
        var first = tracker.call();
        var second = tracker.call();
        now = now.plus(Duration.ofSeconds(2));
        first.onArguments("{\"exercises\":[],\"weeks\":[{\"week\":1},");
        now = now.plus(Duration.ofSeconds(2));
        tracker.completed(first, 2);
        now = now.plus(Duration.ofSeconds(2));
        second.onArguments("{\"exercises\":[],\"weeks\":[{\"week\":3},");
        tracker.abandoned(second);
        now = now.plus(Duration.ofSeconds(2));
        tracker.completed(tracker.call(), 0);
        assertThat(written()).extracting(Progress::weeksDone).containsExactly(0, 1, 2, 3);
        assertThat(written()).allMatch(p -> p.stage().equals("weeks") && p.weeksTotal() == 4);
    }

    private List<Progress> written() {
        var captor = ArgumentCaptor.forClass(Progress.class);
        verify(sessions, atLeastOnce()).progress(eq(session), captor.capture(), any());
        return captor.getAllValues();
    }
}
