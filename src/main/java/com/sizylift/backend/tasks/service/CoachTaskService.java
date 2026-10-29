package com.sizylift.backend.tasks.service;

import java.util.Locale;
import java.util.UUID;

import com.sizylift.backend.inbox.config.CoachCalendar;
import com.sizylift.backend.tasks.dto.TaskDtos.CreateTaskRequest;
import com.sizylift.backend.tasks.dto.TaskDtos.TaskListResponse;
import com.sizylift.backend.tasks.dto.TaskDtos.TaskPatch;
import com.sizylift.backend.tasks.dto.TaskDtos.TaskResponse;
import com.sizylift.backend.tasks.repository.CoachTaskRepository;
import com.sizylift.backend.tasks.repository.CoachTaskRepository.TaskRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Задачи тренера по подопечным: видны и меняются только по активной связи, иначе 404. */
@Service
public class CoachTaskService {

    public static final int MAX_TEXT_LENGTH = 500;

    private final CoachTaskRepository tasks;
    private final CoachCalendar calendar;

    CoachTaskService(CoachTaskRepository tasks, CoachCalendar calendar) {
        this.tasks = tasks;
        this.calendar = calendar;
    }

    /** Какие задачи показывать; по умолчанию открытые. */
    public enum Status {
        OPEN, DONE, ALL;

        public static Status parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new InvalidTask();
            }
        }
    }

    @Transactional(readOnly = true)
    public TaskListResponse list(UUID coachId, UUID athleteId, Status status) {
        if (athleteId != null && tasks.activeLink(coachId, athleteId).isEmpty()) {
            throw new TaskNotFound();
        }
        var rows = tasks.list(coachId, athleteId, status != Status.DONE, status != Status.OPEN);
        return new TaskListResponse(rows.stream().map(CoachTaskService::response).toList());
    }

    @Transactional
    public TaskResponse create(UUID coachId, CreateTaskRequest request) {
        if (request == null || request.athleteId() == null) {
            throw new InvalidTask();
        }
        var text = validText(request.text());
        var link = tasks.activeLink(coachId, request.athleteId()).orElseThrow(TaskNotFound::new);
        var id = tasks.insert(link, text, request.dueOn(), calendar.now());
        return found(coachId, id);
    }

    @Transactional
    public TaskResponse update(UUID coachId, UUID taskId, TaskPatch patch) {
        var current = tasks.find(coachId, taskId).orElseThrow(TaskNotFound::new);
        var now = calendar.now();
        var text = patch.text() == null ? current.text() : validText(patch.text());
        var dueOn = patch.dueOn() == null ? current.dueOn() : patch.dueOn().orElse(null);
        var doneAt = patch.done() == null ? current.doneAt()
                : patch.done() ? (current.doneAt() == null ? now : current.doneAt()) : null;
        var changed = new TaskRow(current.id(), current.athleteId(), current.athleteName(), text, dueOn, doneAt,
                current.createdAt(), now);
        if (!tasks.update(coachId, changed, now)) {
            throw new TaskNotFound();
        }
        return found(coachId, taskId);
    }

    @Transactional
    public void delete(UUID coachId, UUID taskId) {
        if (!tasks.delete(coachId, taskId)) {
            throw new TaskNotFound();
        }
    }

    private TaskResponse found(UUID coachId, UUID taskId) {
        return response(tasks.find(coachId, taskId).orElseThrow(TaskNotFound::new));
    }

    private static String validText(String text) {
        var trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TEXT_LENGTH) {
            throw new InvalidTask();
        }
        return trimmed;
    }

    private static TaskResponse response(TaskRow row) {
        return new TaskResponse(row.id(), row.athleteId(), row.athleteName(), row.text(), row.dueOn(), row.doneAt(),
                row.createdAt(), row.updatedAt());
    }

    /** Чужая задача или подопечный без активной связи. */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class TaskNotFound extends RuntimeException {
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidTask extends RuntimeException {
    }
}
