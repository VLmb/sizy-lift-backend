package com.sizylift.backend.voice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.sizylift.backend.voice.dto.VoiceDtos.Candidate;
import com.sizylift.backend.voice.dto.VoiceDtos.Item;
import com.sizylift.backend.voice.dto.VoiceDtos.Line;
import com.sizylift.backend.voice.dto.VoiceDtos.ParseResponse;
import com.sizylift.backend.voice.dto.VoiceDtos.Proposal;
import com.sizylift.backend.voice.parser.ParsedWorkout;
import com.sizylift.backend.voice.parser.ParsedWorkout.SetLine;

/** Изменяемый результат разбора: его дополняет запасная модель, затем он становится ответом. */
final class VoiceDraft {
    static final String SOURCE_SPOKEN = "spoken";
    static final String SOURCE_MODEL = "model";
    /** Упражнение выбрано, но с сомнением: приложение показывает пометку «проверьте». */
    static final String MEDIUM = "medium";
    /** Больше подходов в одной строке не бывает: «тридцать подходов» — ошибка распознавания. */
    private static final int MAX_SETS = 20;
    private static final int MAX_ITEMS = 30;
    private static final int MAX_LINES = 30;

    static final class DraftItem {
        UUID exerciseId;
        String exerciseName;
        String resultType;
        String spoken;
        String confidence;
        Integer restSeconds;
        List<SetLine> lines;
        final List<String> notes = new ArrayList<>();
        final List<String> unrecognized;
        final List<String> issues;
        List<Candidate> candidates;
        Proposal proposal;

        DraftItem(ParsedWorkout.Item i) {
            exerciseId = i.exerciseId() == null ? null : UUID.fromString(i.exerciseId());
            exerciseName = i.exerciseName();
            resultType = i.resultType();
            spoken = i.spoken();
            confidence = i.confidence().name().toLowerCase(Locale.ROOT);
            restSeconds = i.restSeconds();
            lines = new ArrayList<>(i.lines());
            unrecognized = new ArrayList<>(i.unrecognized());
            issues = new ArrayList<>(i.issues());
            candidates = i.candidates().stream().map(c -> new Candidate(UUID.fromString(c.exerciseId()), c.name())).toList();
            if (exerciseId == null && !spoken.isBlank()) proposal = new Proposal(capitalized(spoken), null, SOURCE_SPOKEN);
        }

        boolean unknown() { return exerciseId == null; }
    }

    final List<DraftItem> items;
    final List<String> unrecognized;

    private VoiceDraft(List<DraftItem> items, List<String> unrecognized) {
        this.items = items;
        this.unrecognized = unrecognized;
    }

    static VoiceDraft of(ParsedWorkout parsed) {
        return new VoiceDraft(new ArrayList<>(parsed.items().stream().map(DraftItem::new).toList()),
                new ArrayList<>(parsed.unrecognized()));
    }

    boolean hasGaps() {
        return !unrecognized.isEmpty() || items.stream().anyMatch(i -> !i.unrecognized.isEmpty() || i.unknown() && !i.spoken.isBlank());
    }

    ParseResponse toResponse(UUID requestId, UUID ownerId, boolean speechDetected) {
        var out = items.stream().limit(MAX_ITEMS).map(VoiceDraft::item).toList();
        return new ParseResponse(requestId, ownerId, speechDetected, out, List.copyOf(unrecognized));
    }

    private static Item item(DraftItem d) {
        var issues = new ArrayList<>(d.issues);
        var lines = d.lines.stream().limit(MAX_LINES).map(l -> line(l, issues)).toList();
        return new Item(d.exerciseId, d.exerciseName, d.resultType, d.spoken, d.confidence, d.restSeconds, lines,
                List.copyOf(d.notes), List.copyOf(d.unrecognized), issues, d.candidates, d.proposal);
    }

    private static Line line(SetLine l, List<String> issues) {
        var count = l.count();
        if (count != null && (count < 1 || count > MAX_SETS)) {
            issues.add("n=" + count + " — слишком много подходов");
            count = null;
        }
        return new Line(count, l.reps(), l.repsMax(), l.toFailure(), l.weightKg(), l.percent(), l.seconds(), l.meters(),
                l.rpe(), l.rir(), l.type(), l.note());
    }

    static String capitalized(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
