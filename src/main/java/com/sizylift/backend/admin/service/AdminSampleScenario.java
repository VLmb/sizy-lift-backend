package com.sizylift.backend.admin.service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutPayloadV1;
import com.sizylift.backend.publication.dto.payload.WorkoutReschedulePayloadV1;
import com.sizylift.backend.sync.dto.SyncPushRequest.Item;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Фиксированный сценарий: четыре завершённые недели и шесть текущих, сейчас четвёртая. */
@Component
public class AdminSampleScenario {
    public static final String VERSION = "training-history-v1";
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final int FINISHED_WEEKS = 4;
    private static final int CURRENT_WEEKS = 6;
    private static final int PREVIOUS_CURRENT_WEEKS = 3;
    private static final int DAYS_IN_WEEK = 7;
    private static final int SESSIONS_PER_WEEK = 3;
    private static final int SETS_PER_EXERCISE = 4;
    private static final int MISSED_SESSION = 4;
    private static final int MOVED_SESSION = 8;
    private static final int DEVIATION_SESSION = 6;
    private static final int SKIPPED_SET_SESSION = 7;
    private static final int WEIGHT_STEP = 2500;
    private static final int REST_MS = 120000;
    private static final int SESSION_MINUTES = 70;
    private static final int REPS = 5;
    private static final int LOWER_REPS = 4;
    private static final int PLAN_RPE = 75;
    private static final int HARD_RPE = 90;
    private static final int DAYS_BETWEEN_SESSIONS = 2;
    private static final int WARMUP_DIVISOR = 2;
    private static final List<Lift> LIFTS = List.of(
            new Lift(UUID.fromString("14000000-0000-4000-8000-000000000001"), "Присед со штангой", 90000),
            new Lift(UUID.fromString("14000000-0000-4000-8000-000000000002"), "Жим штанги лёжа", 60000),
            new Lift(UUID.fromString("14000000-0000-4000-8000-000000000003"), "Становая тяга классическая", 120000));
    private static final List<String> TITLES = List.of("Присед и жим", "Тяга и жим", "Техника и объём");
    private final ObjectMapper json;

    public AdminSampleScenario(ObjectMapper json) { this.json = json; }

    public Scenario build(UUID owner, LocalDate today, Instant now) {
        var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var currentStart = monday.minusWeeks(PREVIOUS_CURRENT_WEEKS);
        var finishedStart = currentStart.minusWeeks(FINISHED_WEEKS);
        var items = new ArrayList<Item>();
        var finishedId = id(owner, "finished");
        var currentId = id(owner, "current");
        var finished = program(owner, "finished", finishedStart, FINISHED_WEEKS, now.minusMillis(1));
        var current = program(owner, "current", currentStart, CURRENT_WEEKS, now);
        items.add(item("program", finishedId, finished, now));
        items.add(item("program", currentId, current, now));
        int facts = 0;
        for (var program : List.of(finished, current)) {
            boolean historical = program == finished;
            int session = 0;
            for (var week : program.cycles().getFirst().weeks()) {
                for (var plan : week.workouts()) {
                    var date = !historical && session == MOVED_SESSION
                            ? plan.workoutDate().plusDays(1) : plan.workoutDate();
                    if (!historical && session == MOVED_SESSION) {
                        items.add(item("workout_reschedule", plan.id(),
                                new WorkoutReschedulePayloadV1(currentId, date, now), now));
                    }
                    if (date.isBefore(today) && (historical || session != MISSED_SESSION)) {
                        var fact = workout(owner, plan, date, historical ? -session - 1 : session, now);
                        items.add(item("workout", id(owner, "fact:" + plan.id()), fact, now));
                        facts++;
                    }
                    session++;
                }
            }
        }
        return new Scenario(List.copyOf(items), finishedId, currentId, currentStart, facts);
    }

    private ProgramPayloadV1 program(UUID owner, String key, LocalDate start, int length, Instant updated) {
        var weeks = new ArrayList<ProgramPayloadV1.Week>();
        for (int week = 0; week < length; week++) {
            var workouts = new ArrayList<ProgramPayloadV1.PlannedWorkout>();
            for (int day = 0; day < SESSIONS_PER_WEEK; day++) {
                var session = key + ":" + week + ":" + day;
                var blocks = new ArrayList<ProgramPayloadV1.Block>();
                blocks.add(new ProgramPayloadV1.Block(id(owner, session + ":warmup"), 0, "free", null,
                        "warmup", "Общая разминка", "8–10 минут, затем разминочные подходы.", List.of()));
                for (int liftIndex = 0; liftIndex < LIFTS.size(); liftIndex++) {
                    var lift = LIFTS.get((liftIndex + day) % LIFTS.size());
                    var exercise = session + ":lift:" + liftIndex;
                    var sets = new ArrayList<ProgramPayloadV1.Set>();
                    int weight = lift.baseWeight() + week * WEIGHT_STEP - (key.equals("finished") ? FINISHED_WEEKS * WEIGHT_STEP : 0);
                    for (int set = 0; set < SETS_PER_EXERCISE; set++) {
                        sets.add(new ProgramPayloadV1.Set(id(owner, exercise + ":set:" + set), set,
                                set == 0 ? "warmup" : "working", set == 0 ? weight / WARMUP_DIVISOR : weight,
                                REPS, null, null, set == 0 ? null : PLAN_RPE, false, null, REST_MS, null));
                    }
                    var planned = new ProgramPayloadV1.Exercise(id(owner, exercise), 0, lift.id(), lift.name(),
                            "weight_reps", "Оставить 2–3 повтора в запасе.", sets);
                    blocks.add(new ProgramPayloadV1.Block(id(owner, exercise + ":block"), liftIndex + 1,
                            "single", null, null, null, null, List.of(planned)));
                }
                workouts.add(new ProgramPayloadV1.PlannedWorkout(id(owner, session), TITLES.get(day),
                        start.plusWeeks(week).plusDays(day * (long) DAYS_BETWEEN_SESSIONS), day, blocks));
            }
            var weekStart = start.plusWeeks(week);
            weeks.add(new ProgramPayloadV1.Week(id(owner, key + ":week:" + week), week + 1,
                    "Неделя " + (week + 1), weekStart, weekStart.plusDays(DAYS_IN_WEEK - 1), week, workouts));
        }
        return new ProgramPayloadV1(key.equals("finished") ? "Тестовая · База, 4 недели" : "Тестовая · Сила, 6 недель",
                "Тестовые данные, созданные администратором. Можно редактировать и удалять.", start,
                "active", updated, List.of(new ProgramPayloadV1.Cycle(id(owner, key + ":cycle"), "Основной цикл", 0, weeks)));
    }

    private WorkoutPayloadV1 workout(UUID owner, ProgramPayloadV1.PlannedWorkout plan, LocalDate date,
            int session, Instant updated) {
        var started = date.atTime(18, 0).atZone(ZONE).toInstant();
        var completed = started.plusSeconds(SESSION_MINUTES * 60L);
        var blocks = new ArrayList<WorkoutPayloadV1.Block>();
        var snapshots = new ArrayList<WorkoutPayloadV1.PlanSetSnapshot>();
        for (var block : plan.blocks()) {
            var exercises = new ArrayList<WorkoutPayloadV1.Exercise>();
            for (var exercise : block.exercises()) {
                var sets = new ArrayList<WorkoutPayloadV1.Set>();
                for (var set : exercise.sets()) {
                    var actualId = id(owner, "actual:" + set.id());
                    boolean skipped = session == SKIPPED_SET_SESSION && block.position() == LIFTS.size()
                            && set.position() == SETS_PER_EXERCISE - 1;
                    int reps = session == DEVIATION_SESSION && block.position() == 1
                            && set.position() == SETS_PER_EXERCISE - 1 ? LOWER_REPS : set.repetitions();
                    sets.add(new WorkoutPayloadV1.Set(actualId, set.position(), set.setType(),
                            skipped ? "skipped" : "completed", skipped ? null : set.targetWeightGrams(),
                            skipped ? null : reps, null, null, skipped || set.position() == 0 ? null : session == DEVIATION_SESSION ? HARD_RPE : PLAN_RPE,
                            null, null, null, null, skipped ? "Не хватило сил на последний подход" : null,
                            skipped ? null : completed, set.id()));
                    snapshots.add(new WorkoutPayloadV1.PlanSetSnapshot(id(owner, "snapshot:" + set.id()),
                            actualId, exercise.id(), set.id(), snapshots.size(), exercise.resultType(), set.setType(),
                            set.targetWeightGrams(), set.repetitions(), null, null, set.targetRpeTenths(),
                            set.restMs(), set.note()));
                }
                exercises.add(new WorkoutPayloadV1.Exercise(id(owner, "actual:" + exercise.id()),
                        exercise.position(), exercise.exerciseId(), exercise.exerciseName(), null,
                        exercise.resultType(), exercise.exerciseId(), exercise.exerciseName(),
                        session == DEVIATION_SESSION ? "Последний подход дался тяжелее плана." : "Техника под контролем.",
                        exercise.planComment(), sets));
            }
            blocks.add(new WorkoutPayloadV1.Block(id(owner, "actual:" + block.id()), block.position(),
                    block.blockType(), block.groupLabel(), null, null, null, null, null,
                    block.freeKind(), block.title(), block.description(), exercises));
        }
        return new WorkoutPayloadV1(date, plan.title(), "Тестовая тренировка", started, completed,
                session == DEVIATION_SESSION || session == SKIPPED_SET_SESSION ? "bad" : session % 2 == 0 ? "great" : "okay",
                plan.id(), updated, blocks, snapshots, List.of());
    }

    private Item item(String type, UUID id, Object payload, Instant changed) {
        return new Item(type, id, null, 0, changed, VERSION, 1, 1, false, json.valueToTree(payload));
    }

    private UUID id(UUID owner, String key) {
        return UUID.nameUUIDFromBytes((VERSION + ":" + owner + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private record Lift(UUID id, String name, int baseWeight) { }
    public record Scenario(List<Item> items, UUID finishedProgramId, UUID currentProgramId,
                           LocalDate currentStart, int workouts) { }
}
