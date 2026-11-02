package com.sizylift.backend.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.imports.dto.ImportDraftV1;
import com.sizylift.backend.imports.dto.ImportDraftV1.*;

final class ImportFixtures {
    static final UUID SQUAT = UUID.fromString("14000000-0000-4000-8000-000000000001");
    static final String TEXT = "Присед со штангой 3×5 100 кг @8";
    private ImportFixtures() { }

    static Resolution matched(String ref) {
        return new Resolution("squat", "Присед", "matched", SQUAT.toString(), List.of(), "", "", List.of(ref));
    }
    static Resolution custom(String key, String name, String type) {
        return new Resolution(key, name, "new", "", List.of(), name, type, List.of("text:1"));
    }
    static ImportDraftV1 clarification(String question) {
        return new ImportDraftV1(1, true, question, new Draft("", "", List.of()), List.of());
    }
    static ImportDraftV1 draft(Resolution... resolutions) {
        var blocks = new ArrayList<Block>();
        for (int i = 0; i < resolutions.length; i++) {
            var r = resolutions[i];
            var set = new Prescription("set" + i, "3", "5", "100", "", "", "", "8", "", "", "working", "", r.sourceRefs());
            var ex = new Exercise("entry" + i, r.key(), "", r.sourceRefs(), List.of(set));
            blocks.add(new Block("block" + i, "single", "", "", "", List.of(ex)));
        }
        var day = new Day("day", "Тренировка", 0, blocks);
        var week = new Week("week", 1, "", List.of(day));
        var cycle = new Cycle("cycle", "Цикл", List.of(week));
        return new ImportDraftV1(1, false, "", new Draft("Программа", "", List.of(cycle)), List.of(resolutions));
    }
}
