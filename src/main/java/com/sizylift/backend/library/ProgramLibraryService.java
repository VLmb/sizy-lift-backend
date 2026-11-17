package com.sizylift.backend.library;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Папки и порядок шаблонов тренера. Проверяет только форму: имена папок от 1 до 80 символов без повторов, у
 * программы — одна запись и папка из списка (или без папки). Чьи это программы, не проверяется: запись — лишь
 * подсказка порядка, клиент показывает только свои программы.
 */
@Service
public class ProgramLibraryService {

    static final int MAX_FOLDERS = 50;
    static final int MAX_ENTRIES = 1000;
    static final int MAX_NAME = 80;

    private final ProgramLibraryRepository library;

    public ProgramLibraryService(ProgramLibraryRepository library) {
        this.library = library;
    }

    @Transactional(readOnly = true)
    public ProgramLibrary get(UUID userId) {
        return library.find(userId);
    }

    @Transactional
    public ProgramLibrary replace(UUID userId, ProgramLibrary request) {
        var clean = validated(request);
        library.replace(userId, clean);
        return library.find(userId);
    }

    private static ProgramLibrary validated(ProgramLibrary request) {
        if (request == null || request.folders() == null || request.entries() == null
                || request.folders().size() > MAX_FOLDERS || request.entries().size() > MAX_ENTRIES) {
            throw new InvalidLibrary();
        }
        var folders = new ArrayList<String>();
        for (var raw : request.folders()) {
            var name = raw == null ? "" : raw.strip();
            if (name.isEmpty() || name.length() > MAX_NAME || folders.contains(name)) {
                throw new InvalidLibrary();
            }
            folders.add(name);
        }
        var seen = new HashSet<UUID>();
        var entries = new ArrayList<ProgramLibrary.Entry>();
        for (var entry : request.entries()) {
            if (entry == null || entry.programId() == null || !seen.add(entry.programId())) {
                throw new InvalidLibrary();
            }
            var folder = entry.folder() == null ? null : entry.folder().strip();
            if (folder != null && !folders.contains(folder)) {
                throw new InvalidLibrary();
            }
            entries.add(new ProgramLibrary.Entry(entry.programId(), folder));
        }
        return new ProgramLibrary(List.copyOf(folders), List.copyOf(entries));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidLibrary extends RuntimeException {
    }
}
