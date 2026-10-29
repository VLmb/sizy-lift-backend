package com.sizylift.backend.tasks.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.identity.controller.AuthenticatedAccount;
import com.sizylift.backend.tasks.dto.TaskDtos.CreateTaskRequest;
import com.sizylift.backend.tasks.dto.TaskDtos.TaskListResponse;
import com.sizylift.backend.tasks.dto.TaskDtos.TaskPatch;
import com.sizylift.backend.tasks.dto.TaskDtos.TaskResponse;
import com.sizylift.backend.tasks.service.CoachTaskService;
import com.sizylift.backend.tasks.service.CoachTaskService.InvalidTask;
import com.sizylift.backend.tasks.service.CoachTaskService.Status;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Задачи тренера (docs/coach-workspace.md, раздел 5). */
@RestController
@RequestMapping("/api/v2/web/tasks")
public class CoachTaskController {

    private static final String DEFAULT_STATUS = "open";
    private static final String TEXT = "text";
    private static final String DUE_ON = "dueOn";
    private static final String DONE = "done";

    private final AuthenticatedAccount accounts;
    private final CoachTaskService tasks;

    public CoachTaskController(AuthenticatedAccount accounts, CoachTaskService tasks) {
        this.accounts = accounts;
        this.tasks = tasks;
    }

    @GetMapping
    public TaskListResponse list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID athleteId,
                                 @RequestParam(defaultValue = DEFAULT_STATUS) String status) {
        return tasks.list(accounts.resolve(jwt).id(), athleteId, Status.parse(status));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateTaskRequest request) {
        return tasks.create(accounts.resolve(jwt).id(), request);
    }

    @PatchMapping("/{id}")
    public TaskResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @RequestBody JsonNode body) {
        return tasks.update(accounts.resolve(jwt).id(), id, patch(body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        tasks.delete(accounts.resolve(jwt).id(), id);
    }

    /** Отличает «поля нет» от {@code "dueOn": null} — второе снимает срок. */
    private static TaskPatch patch(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new InvalidTask();
        }
        return new TaskPatch(text(body.get(TEXT)), dueOn(body.get(DUE_ON)), done(body.get(DONE)));
    }

    private static String text(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isString()) {
            throw new InvalidTask();
        }
        return node.asString();
    }

    @SuppressWarnings("OptionalAssignedToNull")
    private static Optional<LocalDate> dueOn(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            return Optional.empty();
        }
        if (!node.isString()) {
            throw new InvalidTask();
        }
        try {
            return Optional.of(LocalDate.parse(node.asString()));
        } catch (DateTimeParseException e) {
            throw new InvalidTask();
        }
    }

    private static Boolean done(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new InvalidTask();
        }
        return node.booleanValue();
    }
}
