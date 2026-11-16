package com.sizylift.backend.imports.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sizylift.backend.imports.entity.ImportSession;
import com.sizylift.backend.voice.repository.VoiceRepository;
import com.sizylift.backend.voice.service.VoiceCatalog;
import org.springframework.stereotype.Component;

/**
 * Каталог импорта — тот же, что у голосового ввода: снимок упражнений сессии, системные синонимы и личные синонимы
 * пользователя. Из него строятся каталог в инструкции модели и словарь сопоставления названий.
 */
@Component
public class ImportCatalogs {
    private static final int MAX_USER_ALIASES = 500;
    /** Подсказки распознавания речи импорту не нужны. */
    private static final int NO_KEYTERMS = 0;
    private final VoiceRepository voice;
    private volatile Map<UUID, List<String>> systemAliases;

    public ImportCatalogs(VoiceRepository voice) { this.voice = voice; }

    public VoiceCatalog of(ImportSession session) {
        var sources = session.catalog().stream()
                .map(e -> new VoiceCatalog.Source(e.id(), e.ownerId() != null, e.name(), e.shortName(), e.resultType()))
                .toList();
        return VoiceCatalog.build(sources, systemAliases(), voice.userAliases(session.userId(), MAX_USER_ALIASES), NO_KEYTERMS);
    }

    /** Системные синонимы меняются только миграцией: читаются один раз. */
    private Map<UUID, List<String>> systemAliases() {
        var aliases = systemAliases;
        if (aliases == null) systemAliases = aliases = voice.systemAliases();
        return aliases;
    }
}
