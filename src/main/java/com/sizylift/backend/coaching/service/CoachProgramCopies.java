package com.sizylift.backend.coaching.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.publication.dto.payload.ProgramPayloadV1;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.publication.service.PayloadReader;
import com.sizylift.backend.sync.dto.SyncPushRequest.Item;
import com.sizylift.backend.sync.service.SyncPushService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Программы тренера для приглашения и назначения (docs/coach-workspace.md, раздел 8): короткий состав первой недели
 * для страницы приглашения и копия-черновик тренера у подопечного. Черновик тихий: уведомления подопечному нет,
 * пока тренер не сделает программу активной.
 */
@Service
public class CoachProgramCopies {

    static final String DRAFT = "draft";
    private static final String ID = "id";
    private static final int SCHEMA_VERSION = 1;
    private static final long FIRST_VERSION = 1;

    private final PayloadReader payloads;
    private final SyncPushService sync;
    private final ObjectMapper json;
    private final Clock clock;

    CoachProgramCopies(PayloadReader payloads, SyncPushService sync, ObjectMapper json, Clock clock) {
        this.payloads = payloads;
        this.sync = sync;
        this.json = json;
        this.clock = clock;
    }

    /** Своя неудалённая программа тренера. */
    public Optional<ProgramPayloadV1> own(UUID coachId, UUID programId) {
        return payloads.read(coachId, AggregateType.PROGRAM, programId).map(ProgramPayloadV1.class::cast);
    }

    /** День первой недели: день недели (1 — пн), название и упражнения по порядку, без весов и подходов. */
    public record PreviewDay(int dayOfWeek, String title, List<String> exercises) {
    }

    /** Что видно на странице приглашения до входа (CR-02). */
    public record Preview(String name, int weeks, int workoutsPerWeek, List<PreviewDay> firstWeek) {
    }

    public Preview preview(ProgramPayloadV1 program) {
        var weeks = program.cycles().stream().flatMap(c -> c.weeks().stream())
                .sorted(Comparator.comparing(ProgramPayloadV1.Week::startDate)).toList();
        var first = weeks.isEmpty() ? List.<ProgramPayloadV1.PlannedWorkout>of()
                : weeks.getFirst().workouts().stream()
                        .sorted(Comparator.comparing(ProgramPayloadV1.PlannedWorkout::workoutDate)
                                .thenComparingInt(ProgramPayloadV1.PlannedWorkout::position))
                        .toList();
        var days = first.stream().map(workout -> new PreviewDay(dayOfWeek(workout), workout.title(),
                workout.blocks().stream()
                        .sorted(Comparator.comparingInt(ProgramPayloadV1.Block::position))
                        .flatMap(block -> block.exercises().stream()
                                .sorted(Comparator.comparingInt(ProgramPayloadV1.Exercise::position)))
                        .map(ProgramPayloadV1.Exercise::exerciseName).toList()))
                .toList();
        return new Preview(program.name(), weeks.size(), first.size(), days);
    }

    /**
     * Копия-черновик программы у подопечного от имени тренера, в текущей транзакции. Все id новые; подходы как в
     * шаблоне: % от ПМ остаётся процентом, вес по нему посчитает веб (сервер ПМ не считает).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createDraft(UUID coachId, UUID athleteId, UUID templateId, ProgramPayloadV1 template) {
        var payload = (ObjectNode) json.valueToTree(template);
        freshIds(payload);
        payload.put("status", DRAFT);
        payload.put("sourceProgramId", templateId.toString());
        payload.put("updatedAt", clock.instant().toString());
        var id = UUID.randomUUID();
        create(coachId, athleteId, id, payload);
        return id;
    }

    /** Готовая копия от клиента (назначение нескольким, CR-13): только черновик и только новый агрегат. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long create(UUID coachId, UUID athleteId, UUID programId, JsonNode payload) {
        var item = new Item(AggregateType.PROGRAM, programId, athleteId, 0, clock.instant(),
                "coach-copy:" + programId, FIRST_VERSION, SCHEMA_VERSION, false, payload);
        return sync.createImportedProgram(coachId, List.of(item)).results().getFirst().revision();
    }

    private static int dayOfWeek(ProgramPayloadV1.PlannedWorkout workout) {
        DayOfWeek day = workout.workoutDate().getDayOfWeek();
        return day.getValue();
    }

    private static void freshIds(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has(ID)) {
                object.put(ID, UUID.randomUUID().toString());
            }
            object.forEach(CoachProgramCopies::freshIds);
        } else if (node.isArray()) {
            node.forEach(CoachProgramCopies::freshIds);
        }
    }
}
