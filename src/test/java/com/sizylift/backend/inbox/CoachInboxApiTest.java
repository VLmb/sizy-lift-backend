package com.sizylift.backend.inbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.CoachWorkspaceApiSupport;
import com.sizylift.backend.inbox.service.CoachInboxDailyJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** «Входящие» тренера (docs/coach-workspace.md, раздел 4): создание событий, лента, отметка, список подопечных. */
@SpringBootTest
@AutoConfigureMockMvc
class CoachInboxApiTest extends CoachWorkspaceApiSupport {

    static final LocalDate LINKED_ON = LocalDate.parse("2026-10-01");

    @Autowired
    CoachInboxDailyJob dailyJob;

    String coach;
    String athlete;
    UUID athleteId;
    UUID linkId;

    @BeforeEach
    void linked() throws Exception {
        coach = newSubject();
        athlete = newSubject();
        athleteId = userId(athlete);
        linkId = link(coach, athlete);
    }

    @Test
    void acceptedInviteCreatesAnUnseenEvent() throws Exception {
        var inbox = inbox(coach, "");

        assertThat(inbox.get("unseenCount").asLong()).isEqualTo(1);
        var event = inbox.get("events").get(0);
        assertThat(event.get("kind").asString()).isEqualTo("invite_accepted");
        assertThat(event.get("athleteId").asString()).isEqualTo(athleteId.toString());
        assertThat(event.get("subjectId").isNull()).isTrue();
        assertThat(event.get("seenAt").isNull()).isTrue();
    }

    @Test
    void firstPublicationOfAWorkoutCreatesOneEventForV1AndV2() throws Exception {
        var legacyWorkout = UUID.randomUUID();
        legacy(athlete, "workout", legacyWorkout, 1, workout());
        markSeen(Map.of("subjectId", legacyWorkout));
        var edited = workout();
        edited.put("title", "Правка");
        legacy(athlete, "workout", legacyWorkout, 2, edited);
        var syncWorkout = UUID.randomUUID();
        push(athlete, item("workout", syncWorkout, null, 0, 1, workout()));

        var completed = events(inbox(coach, "?athleteId=" + athleteId), "workout_completed");
        assertThat(completed).hasSize(2);
        var legacyEvent = bySubject(completed, legacyWorkout);
        assertThat(legacyEvent.get("subjectDate").asString()).isEqualTo("2026-10-14");
        assertThat(legacyEvent.get("title").asString()).isEqualTo("Правка");
        assertThat(legacyEvent.get("seenAt").isNull()).as("повторная публикация не сбрасывает просмотр").isFalse();
        assertThat(bySubject(completed, syncWorkout).get("title").asString()).isEqualTo("Присед и жим");
    }

    @Test
    void deletingTheWorkoutRemovesItsEvent() throws Exception {
        var workout = UUID.randomUUID();
        legacy(athlete, "workout", workout, 1, workout());
        legacy(athlete, "workout", workout, 2, null);

        assertThat(events(inbox(coach, ""), "workout_completed")).isEmpty();
    }

    @Test
    void newRescheduleOfTheSameWorkoutUpdatesTheEventAndMakesItUnseen() throws Exception {
        var program = UUID.randomUUID();
        push(coach, item("program", program, athleteId, 0, 1, fixture("program")));
        var first = push(athlete, item("workout_reschedule", PLANNED_WORKOUT, null, 0, 1,
                reschedule(program, "2026-10-15")));
        var event = only(events(inbox(coach, ""), "workout_rescheduled"));
        assertThat(event.get("subjectId").asString()).isEqualTo(PLANNED_WORKOUT.toString());
        assertThat(event.get("subjectDate").asString()).isEqualTo("2026-10-15");
        assertThat(event.get("title").asString()).isEqualTo("День A");
        markSeen(Map.of("eventIds", List.of(event.get("id").asString())));

        push(athlete, item("workout_reschedule", PLANNED_WORKOUT, null, first.get("revision").asLong(), 2,
                reschedule(program, "2026-10-16")));

        var updated = only(events(inbox(coach, ""), "workout_rescheduled"));
        assertThat(updated.get("id").asString()).isEqualTo(event.get("id").asString());
        assertThat(updated.get("subjectDate").asString()).isEqualTo("2026-10-16");
        assertThat(updated.get("seenAt").isNull()).isTrue();
        assertThat(Instant.parse(updated.get("occurredAt").asString()))
                .isAfter(Instant.parse(event.get("occurredAt").asString()));
    }

    @Test
    void dailyJobFindsMissedWorkoutsOnceAndSkipsCompletedOnes() throws Exception {
        linkedSince(LINKED_ON);
        push(coach, item("program", UUID.randomUUID(), athleteId, 0, 1, fixture("program")));

        dailyJob.run(LocalDate.parse("2026-10-15"));
        assertThat(events(inbox(coach, ""), "workout_missed")).as("вчерашняя ещё не пропущена").isEmpty();

        dailyJob.run(LocalDate.parse("2026-10-16"));
        dailyJob.run(LocalDate.parse("2026-10-16"));
        var missed = only(events(inbox(coach, ""), "workout_missed"));
        assertThat(missed.get("subjectId").asString()).isEqualTo(PLANNED_WORKOUT.toString());
        assertThat(missed.get("subjectDate").asString()).isEqualTo("2026-10-14");
        assertThat(missed.get("title").asString()).isEqualTo("День A");

        legacy(athlete, "workout", UUID.randomUUID(), 1, workout());
        dailyJob.run(LocalDate.parse("2026-10-17"));
        assertThat(events(inbox(coach, ""), "workout_missed")).as("тренировку по плану выполнили").isEmpty();
    }

    @Test
    void dailyJobUsesTheRescheduledDateAndIgnoresOldOrPreLinkDates() throws Exception {
        linkedSince(LINKED_ON);
        var program = UUID.randomUUID();
        push(coach, item("program", program, athleteId, 0, 1, fixture("program")));
        push(athlete, item("workout_reschedule", PLANNED_WORKOUT, null, 0, 1, reschedule(program, "2026-10-20")));

        dailyJob.run(LocalDate.parse("2026-10-17"));
        assertThat(events(inbox(coach, ""), "workout_missed")).as("перенесена на будущее").isEmpty();
        dailyJob.run(LocalDate.parse("2026-11-20"));
        assertThat(events(inbox(coach, ""), "workout_missed")).as("старше окна просмотра").isEmpty();

        linkedSince(LocalDate.parse("2026-10-21"));
        dailyJob.run(LocalDate.parse("2026-10-22"));
        assertThat(events(inbox(coach, ""), "workout_missed")).as("до создания связи").isEmpty();

        linkedSince(LINKED_ON);
        dailyJob.run(LocalDate.parse("2026-10-22"));
        assertThat(only(events(inbox(coach, ""), "workout_missed")).get("subjectDate").asString())
                .isEqualTo("2026-10-20");
    }

    @Test
    void dailyJobWarnsAboutTheProgramEndingWithinAWeekOnce() throws Exception {
        var program = UUID.randomUUID();
        push(coach, item("program", program, athleteId, 0, 1, fixture("program")));

        dailyJob.run(LocalDate.parse("2026-10-10"));
        assertThat(events(inbox(coach, ""), "program_ending")).as("до конца 8 дней").isEmpty();
        dailyJob.run(LocalDate.parse("2026-10-11"));
        dailyJob.run(LocalDate.parse("2026-10-18"));

        var ending = only(events(inbox(coach, ""), "program_ending"));
        assertThat(ending.get("subjectId").asString()).isEqualTo(program.toString());
        assertThat(ending.get("subjectDate").asString()).isEqualTo("2026-10-18");
        assertThat(ending.get("title").asString()).isEqualTo("Силовой блок");
    }

    @Test
    void removingAPlannedWorkoutFromTheProgramRemovesItsMissedEvent() throws Exception {
        linkedSince(LINKED_ON);
        var program = UUID.randomUUID();
        var created = push(coach, item("program", program, athleteId, 0, 1, fixture("program")));
        dailyJob.run(LocalDate.parse("2026-10-16"));
        markSeen(Map.of("all", true));
        assertThat(events(inbox(coach, ""), "workout_missed")).hasSize(1);
        assertThat(events(inbox(coach, ""), "program_ending")).hasSize(1);

        var withoutWorkout = fixture("program");
        ((ArrayNode) withoutWorkout.get("cycles").get(0).get("weeks").get(0)
                .get("workouts")).removeAll();
        push(coach, item("program", program, athleteId, created.get("revision").asLong(), 2, withoutWorkout));

        assertThat(events(inbox(coach, ""), "workout_missed")).as("просмотренное тоже").isEmpty();
        assertThat(events(inbox(coach, ""), "program_ending")).as("программа осталась").hasSize(1);
    }

    @Test
    void deletingTheProgramRemovesItsMissedAndEndingEvents() throws Exception {
        linkedSince(LINKED_ON);
        var program = UUID.randomUUID();
        var created = push(coach, item("program", program, athleteId, 0, 1, fixture("program")));
        var other = UUID.randomUUID();
        var otherProgram = withFreshIds("program");
        otherProgram.put("name", "Другая");
        ((ArrayNode) otherProgram.get("cycles").get(0).get("weeks").get(0)
                .get("workouts")).removeAll();
        push(coach, item("program", other, athleteId, 0, 1, otherProgram));
        dailyJob.run(LocalDate.parse("2026-10-16"));
        assertThat(events(inbox(coach, ""), "workout_missed")).hasSize(1);
        assertThat(events(inbox(coach, ""), "program_ending")).hasSize(2);

        push(coach, item("program", program, athleteId, created.get("revision").asLong(), 2, null));

        assertThat(events(inbox(coach, ""), "workout_missed")).isEmpty();
        assertThat(only(events(inbox(coach, ""), "program_ending")).get("subjectId").asString())
                .as("событие другой программы остаётся").isEqualTo(other.toString());
    }

    @Test
    void otherCoachesAndEndedLinksSeeNothing() throws Exception {
        var stranger = newSubject();
        legacy(athlete, "workout", UUID.randomUUID(), 1, workout());
        var foreign = inbox(coach, "").get("events").get(0).get("id").asString();

        assertThat(inbox(stranger, "").get("events").size()).isZero();
        assertThat(inbox(stranger, "?athleteId=" + athleteId).get("unseenCount").asLong()).isZero();
        postJson(stranger, "/api/v2/web/inbox/seen", Map.of("eventIds", List.of(foreign)))
                .andExpect(status().isNoContent());
        postJson(stranger, "/api/v2/web/inbox/seen", Map.of("all", true)).andExpect(status().isNoContent());
        assertThat(inbox(coach, "").get("unseenCount").asLong()).isEqualTo(2);

        call(coach, delete("/api/v2/coaching/links/" + linkId)).andExpect(status().isNoContent());
        legacy(athlete, "workout", UUID.randomUUID(), 1, workout());

        var after = inbox(coach, "");
        assertThat(after.get("unseenCount").asLong()).isZero();
        assertThat(after.get("events").size()).isZero();
    }

    @Test
    void feedPagesByOccurredAtWithTieBreakAndFiltersUnseen() throws Exception {
        var base = Instant.parse("2026-10-01T00:00:00Z");
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 4; i++) {
            ids.add(insertEvent(base.plusSeconds(i), i == 0));
        }
        var tied = insertEvent(base.plusSeconds(2), false);

        var first = inbox(coach, "?limit=3").get("events");
        assertThat(first.size()).isEqualTo(3);
        assertThat(first.get(0).get("kind").asString()).isEqualTo("invite_accepted");
        var last = first.get(2);
        var next = inbox(coach, "?limit=3&before=" + last.get("occurredAt").asString() + "&beforeId="
                + last.get("id").asString()).get("events");
        var seenIds = new ArrayList<String>();
        first.forEach(event -> seenIds.add(event.get("id").asString()));
        next.forEach(event -> seenIds.add(event.get("id").asString()));
        assertThat(seenIds).doesNotHaveDuplicates().contains(tied.toString())
                .containsAll(ids.stream().map(UUID::toString).toList());

        var plain = inbox(coach, "?before=" + base.plusSeconds(2)).get("events");
        assertThat(plain.size()).isEqualTo(2);
        var unseen = inbox(coach, "?unseenOnly=true");
        assertThat(unseen.get("events").size()).isEqualTo(5);
        assertThat(unseen.get("unseenCount").asLong()).isEqualTo(5);

        call(coach, get("/api/v2/web/inbox?limit=0")).andExpect(status().isBadRequest());
        call(coach, get("/api/v2/web/inbox?limit=201")).andExpect(status().isBadRequest());
    }

    @Test
    void seenMarksByIdsAthleteSubjectOrAllAndRejectsAnEmptyBody() throws Exception {
        var workout = UUID.randomUUID();
        legacy(athlete, "workout", workout, 1, workout());
        var other = newSubject();
        link(coach, other);

        postJson(coach, "/api/v2/web/inbox/seen", "{}").andExpect(status().isBadRequest());
        postJson(coach, "/api/v2/web/inbox/seen", Map.of("all", false)).andExpect(status().isBadRequest());
        postJson(coach, "/api/v2/web/inbox/seen", Map.of("eventIds", List.of())).andExpect(status().isBadRequest());
        call(coach, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v2/web/inbox/seen")).andExpect(status().isBadRequest());
        assertThat(inbox(coach, "").get("unseenCount").asLong()).isEqualTo(3);

        markSeen(Map.of("subjectId", workout));
        assertThat(inbox(coach, "").get("unseenCount").asLong()).isEqualTo(2);
        markSeen(Map.of("athleteId", athleteId));
        assertThat(inbox(coach, "?athleteId=" + athleteId).get("unseenCount").asLong()).isZero();
        assertThat(inbox(coach, "").get("unseenCount").asLong()).isEqualTo(1);
        markSeen(Map.of("all", true));
        assertThat(inbox(coach, "").get("unseenCount").asLong()).isZero();
    }

    @Test
    void rosterCountsUnseenAndTasksAndPutsTheLongestWaitingFirst() throws Exception {
        var second = newSubject();
        var secondId = userId(second);
        var secondLink = link(coach, second);
        legacy(athlete, "workout", UUID.randomUUID(), 1, workout());
        createTask(athleteId, "2020-01-01");
        createTask(athleteId, "2099-01-01");
        createTask(athleteId, null);

        var roster = roster();
        assertThat(roster.get(0).get("athleteId").asString()).as("ждёт дольше").isEqualTo(athleteId.toString());
        var first = roster.get(0);
        assertThat(first.get("unseen").get("workoutCompleted").asLong()).isEqualTo(1);
        assertThat(first.get("unseen").get("inviteAccepted").asLong()).isEqualTo(1);
        assertThat(first.get("unseen").get("workoutMissed").asLong()).isZero();
        assertThat(first.get("oldestUnseenAt").isNull()).isFalse();
        assertThat(first.get("openTasks").asLong()).isEqualTo(3);
        assertThat(first.get("overdueTasks").asLong()).isEqualTo(1);
        assertThat(first.has("hasNew")).isTrue();

        markSeen(Map.of("athleteId", athleteId));
        roster = roster();
        assertThat(roster.get(0).get("athleteId").asString()).isEqualTo(secondId.toString());
        assertThat(roster.get(1).get("oldestUnseenAt").isNull()).isTrue();

        markSeen(Map.of("all", true));
        jdbc.update("UPDATE sizylift.coaching_link SET created_at = '2026-01-01' WHERE id = ?", secondLink);
        roster = roster();
        assertThat(roster.get(0).get("athleteId").asString()).as("затем по свежей активности")
                .isEqualTo(athleteId.toString());
    }

    private void linkedSince(LocalDate date) {
        jdbc.update("UPDATE sizylift.coaching_link SET created_at = ? WHERE id = ?",
                Timestamp.from(date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)), linkId);
    }

    private UUID insertEvent(Instant occurredAt, boolean seen) {
        return jdbc.queryForObject("""
                INSERT INTO sizylift.coach_event (link_id, kind, subject_id, subject_date, occurred_at, seen_at,
                    dedup_key)
                VALUES (?, 'workout_completed', gen_random_uuid(), '2026-10-01', ?, ?, gen_random_uuid()::text)
                RETURNING id
                """, UUID.class, linkId, Timestamp.from(occurredAt), seen ? Timestamp.from(occurredAt) : null);
    }

    private void createTask(UUID athlete, String dueOn) throws Exception {
        var request = new java.util.HashMap<String, Object>();
        request.put("athleteId", athlete);
        request.put("text", "Проверить технику");
        request.put("dueOn", dueOn);
        postJson(coach, "/api/v2/web/tasks", request).andExpect(status().isCreated());
    }

    private JsonNode roster() throws Exception {
        return body(call(coach, get("/api/v2/web/athletes")).andExpect(status().isOk()));
    }

    private void markSeen(Map<String, Object> request) throws Exception {
        postJson(coach, "/api/v2/web/inbox/seen", request).andExpect(status().isNoContent());
    }

    private JsonNode inbox(String subject, String query) throws Exception {
        return body(call(subject, get("/api/v2/web/inbox" + query)).andExpect(status().isOk()));
    }

    private static List<JsonNode> events(JsonNode inbox, String kind) {
        var result = new ArrayList<JsonNode>();
        inbox.get("events").forEach(event -> {
            if (event.get("kind").asString().equals(kind)) {
                result.add(event);
            }
        });
        return result;
    }

    private static JsonNode only(List<JsonNode> events) {
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private static JsonNode bySubject(List<JsonNode> events, UUID subject) {
        return events.stream().filter(event -> event.get("subjectId").asString().equals(subject.toString()))
                .findFirst().orElseThrow();
    }
}
