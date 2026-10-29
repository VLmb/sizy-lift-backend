package com.sizylift.backend.admin.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.sizylift.backend.admin.dto.AdminUser;
import com.sizylift.backend.admin.repository.AdminUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUserService {
    private static final Set<String> STATES = Set.of("all", "active", "pending", "disabled");
    private static final Set<String> COACHES = Set.of("all", "true", "false");
    private final KeycloakDirectory directory;
    private final AdminUserRepository accounts;
    private final String issuer;

    public AdminUserService(KeycloakDirectory directory, AdminUserRepository accounts,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.directory = directory;
        this.accounts = accounts;
        this.issuer = issuer;
    }

    public Page users(String search, String state, String coach, String sort, int page, int size) {
        if (!STATES.contains(state) || !COACHES.contains(coach))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var comparator = order(sort);
        var term = search.strip().toLowerCase(Locale.ROOT);
        var rows = all().stream()
                .filter(user -> (user.name() + " " + user.email()).toLowerCase(Locale.ROOT).contains(term))
                .filter(user -> state.equals("all") || state.equals(user.state()))
                .filter(user -> coach.equals("all") || Boolean.parseBoolean(coach) == user.coachEnabled())
                .sorted(comparator).toList();
        return new Page(rows.stream().skip((long) page * size).limit(size).toList(), rows.size(), page, size);
    }

    public AdminUser user(String id) {
        return all().stream().filter(user -> user.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Overview overview() {
        var users = all();
        var week = Instant.now().minus(7, ChronoUnit.DAYS);
        return new Overview(users.size(), users.stream()
                .filter(user -> user.registeredAt() != null && !user.registeredAt().isBefore(week)).count(),
                users.stream().filter(user -> user.accountId() != null).count());
    }

    private List<AdminUser> all() {
        var people = directory.users();
        var summaries = accounts.summaries(issuer);
        return people.stream().map(person -> {
            var account = summaries.get(person.id());
            return new AdminUser(person.id(), account == null ? null : account.id(), person.name(), person.email(),
                    person.emailVerified(), person.registeredAt(), person.state(), account != null && account.coach(),
                    account == null ? null : account.firstSeen(), account == null ? null : account.lastSeen(),
                    account == null ? 0 : account.workouts(), account == null ? 0 : account.programs(),
                    account == null ? 0 : account.athletes(), account != null && account.hasCoach());
        }).toList();
    }

    private static Comparator<AdminUser> order(String sort) {
        Comparator<AdminUser> comparator = switch (sort) {
            case "registered" -> Comparator.comparing(AdminUser::registeredAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case "activity" -> Comparator.comparing(AdminUser::lastSeenAt, Comparator.nullsLast(Comparator.reverseOrder()));
            case "name" -> Comparator.comparing(AdminUser::name, String.CASE_INSENSITIVE_ORDER);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        return comparator.thenComparing(AdminUser::id);
    }

    public record Page(List<AdminUser> items, long total, int page, int size) {
    }

    public record Overview(long registered, long newLastWeek, long started) {
    }
}
