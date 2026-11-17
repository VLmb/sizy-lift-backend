package com.sizylift.backend.library;

import java.util.List;
import java.util.UUID;

/**
 * Библиотека шаблонов тренера на сайте: папки по порядку и программы по порядку с папкой ({@code null} — без
 * папки). Только оформление списка; программы и их синхронизация не меняются (changeset 045).
 */
public record ProgramLibrary(List<String> folders, List<Entry> entries) {

    public record Entry(UUID programId, String folder) {
    }
}
