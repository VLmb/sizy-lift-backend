package com.sizylift.backend.tasks.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Задачи тренера (docs/coach-workspace.md, раздел 5). */
public final class TaskDtos {

    private TaskDtos() {
    }

    public record TaskListResponse(List<TaskResponse> tasks) {
    }

    public record TaskResponse(UUID id, UUID athleteId, String athleteName, String text, LocalDate dueOn,
                               Instant doneAt, Instant createdAt, Instant updatedAt) {
    }

    public record CreateTaskRequest(UUID athleteId, String text, LocalDate dueOn) {
    }

    /**
     * Частичная правка: отсутствующее поле не меняется. {@code dueOn} — {@code null}, если поля нет,
     * {@code Optional.empty()} — снять срок.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public record TaskPatch(String text, Optional<LocalDate> dueOn, Boolean done) {
    }
}
