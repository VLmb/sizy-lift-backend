package com.sizylift.backend.voice.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import com.sizylift.backend.voice.parser.ExerciseDictionary;

/** Системный каталог из черновика синонимов — тот же, что миграция 037 кладёт в базу. */
public final class VoiceTestCatalog {
    private static final int SHORT_NAME = 1;
    private static final int FIRST_SYNONYM = 2;

    private VoiceTestCatalog() { }

    public static List<VoiceCatalog.Source> systemSources() {
        var out = new ArrayList<VoiceCatalog.Source>();
        for (var e : ExerciseDictionary.loadSystem()) {
            out.add(new VoiceCatalog.Source(UUID.fromString(e.id()), false, e.name(), e.aliases().get(SHORT_NAME), e.resultType()));
        }
        return out;
    }

    public static HashMap<UUID, List<String>> systemAliases() {
        var out = new HashMap<UUID, List<String>>();
        for (var e : ExerciseDictionary.loadSystem()) {
            out.put(UUID.fromString(e.id()), e.aliases().subList(FIRST_SYNONYM, e.aliases().size()));
        }
        return out;
    }

    public static VoiceCatalog system() {
        return VoiceCatalog.build(systemSources(), systemAliases(), List.of(), 900);
    }
}
