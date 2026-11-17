package com.sizylift.backend.library;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Папки и порядок библиотеки (changeset 045). Запись целиком заменяет прежнюю. */
@Repository
public class ProgramLibraryRepository {

    private static final String FOLDERS = """
            SELECT name FROM sizylift.program_library_folder WHERE user_id = ? ORDER BY position, name
            """;
    private static final String ENTRIES = """
            SELECT program_id, folder FROM sizylift.program_library_entry WHERE user_id = ? ORDER BY position
            """;
    private static final String DELETE_FOLDERS = "DELETE FROM sizylift.program_library_folder WHERE user_id = ?";
    private static final String DELETE_ENTRIES = "DELETE FROM sizylift.program_library_entry WHERE user_id = ?";
    private static final String INSERT_FOLDER = """
            INSERT INTO sizylift.program_library_folder (user_id, name, position) VALUES (?, ?, ?)
            """;
    private static final String INSERT_ENTRY = """
            INSERT INTO sizylift.program_library_entry (user_id, program_id, folder, position) VALUES (?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public ProgramLibraryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ProgramLibrary find(UUID userId) {
        var folders = jdbc.queryForList(FOLDERS, String.class, userId);
        var entries = jdbc.query(ENTRIES, (row, n) -> new ProgramLibrary.Entry(
                row.getObject("program_id", UUID.class), row.getString("folder")), userId);
        return new ProgramLibrary(folders, entries);
    }

    public void replace(UUID userId, ProgramLibrary library) {
        jdbc.update(DELETE_ENTRIES, userId);
        jdbc.update(DELETE_FOLDERS, userId);
        List<String> folders = library.folders();
        for (int i = 0; i < folders.size(); i++) {
            jdbc.update(INSERT_FOLDER, userId, folders.get(i), i);
        }
        var entries = library.entries();
        for (int i = 0; i < entries.size(); i++) {
            jdbc.update(INSERT_ENTRY, userId, entries.get(i).programId(), entries.get(i).folder(), i);
        }
    }
}
