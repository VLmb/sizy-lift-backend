package com.sizylift.backend.access;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.sizylift.backend.PostgresIntegrationTest;
import com.sizylift.backend.access.service.AccessPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Матрица «роль × тип агрегата × состояние связи × health_access» (ADR-0007, раздел 6): чтение, запись и
 * фан-аут. Ожидания записаны явно по ADR-0006, а не выводятся из кода политики.
 */
@SpringBootTest
class AccessPolicyMatrixTest extends PostgresIntegrationTest {

    static final List<String> TYPES = List.of("workout", "program", "exercise", "athlete_profile", "one_rep_max",
            "injury", "contraindication");
    static final List<String> HEALTH_TYPES = List.of("injury", "contraindication");

    enum Role {
        /** Сам подопечный. */
        OWNER,
        /** Тренер с активной связью. */
        ACTIVE_COACH,
        /** Бывший тренер: связь разорвана, у подопечного уже другой тренер. */
        ENDED_COACH,
        /** Посторонний пользователь, сам тренер чужих подопечных. */
        STRANGER
    }

    @Autowired
    AccessPolicy access;

    @Autowired
    JdbcTemplate jdbc;

    static Stream<Arguments> matrix() {
        var cases = new ArrayList<Arguments>();
        for (var role : Role.values()) {
            for (var type : TYPES) {
                for (var health : List.of(false, true)) {
                    cases.add(Arguments.of(role, type, health));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} health={2}")
    @MethodSource("matrix")
    void readWriteAndFanOut(Role role, String type, boolean healthAccess) {
        var world = world(healthAccess);
        var caller = world.caller(role);

        assertThat(access.canRead(caller, world.athlete(), type)).as("read").isEqualTo(expectedRead(role, type,
                healthAccess));
        assertThat(access.readableOwners(caller, type).contains(world.athlete())).as("readable owners")
                .isEqualTo(expectedRead(role, type, healthAccess));
        assertThat(access.recipients(world.athlete(), type).contains(caller)).as("fan-out")
                .isEqualTo(expectedRead(role, type, healthAccess));
        assertThat(access.canWrite(caller, world.athlete(), type, null, false)).as("create")
                .isEqualTo(role == Role.OWNER || role == Role.ACTIVE_COACH && type.equals("program"));
        assertThat(access.canWrite(caller, world.athlete(), type, null, true)).as("edit owner's aggregate")
                .isEqualTo(role == Role.OWNER);
        assertThat(access.canWrite(caller, world.athlete(), type, world.activeCoach(), true))
                .as("edit active coach's aggregate")
                .isEqualTo(role == Role.ACTIVE_COACH && type.equals("program"));
        assertThat(access.canWrite(caller, world.athlete(), type, world.endedCoach(), true))
                .as("edit ended coach's aggregate").isFalse();
    }

    @ParameterizedTest(name = "health={0}")
    @MethodSource("healthStates")
    void recipientsAreInLockOrder(boolean healthAccess) {
        var world = world(healthAccess);

        var recipients = access.recipients(world.athlete(), "workout");

        assertThat(recipients).containsExactlyInAnyOrder(world.athlete(), world.activeCoach());
        assertThat(recipients).isSortedAccordingTo(AccessPolicy.LOCK_ORDER);
    }

    static Stream<Boolean> healthStates() {
        return Stream.of(false, true);
    }

    private static boolean expectedRead(Role role, String type, boolean healthAccess) {
        return switch (role) {
            case OWNER -> true;
            case ACTIVE_COACH -> !HEALTH_TYPES.contains(type) || healthAccess;
            case ENDED_COACH, STRANGER -> false;
        };
    }

    private World world(boolean healthAccess) {
        var athlete = user();
        var activeCoach = user();
        var endedCoach = user();
        var stranger = user();
        var strangersAthlete = user();
        jdbc.update("""
                INSERT INTO sizylift.coaching_link (coach_user_id, athlete_user_id, health_access, ended_at)
                VALUES (?, ?, true, now())
                """, endedCoach, athlete);
        jdbc.update("""
                INSERT INTO sizylift.coaching_link (coach_user_id, athlete_user_id, health_access) VALUES (?, ?, ?)
                """, activeCoach, athlete, healthAccess);
        jdbc.update("""
                INSERT INTO sizylift.coaching_link (coach_user_id, athlete_user_id, health_access) VALUES (?, ?, true)
                """, stranger, strangersAthlete);
        return new World(athlete, activeCoach, endedCoach, stranger);
    }

    private UUID user() {
        return jdbc.queryForObject("INSERT INTO sizylift.user_account (issuer, subject) VALUES ('test', ?) "
                + "RETURNING id", UUID.class, UUID.randomUUID().toString());
    }

    record World(UUID athlete, UUID activeCoach, UUID endedCoach, UUID stranger) {

        UUID caller(Role role) {
            return switch (role) {
                case OWNER -> athlete;
                case ACTIVE_COACH -> activeCoach;
                case ENDED_COACH -> endedCoach;
                case STRANGER -> stranger;
            };
        }
    }
}
