package com.sizylift.backend.access.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.sizylift.backend.access.entity.CoachingRelation;
import com.sizylift.backend.access.repository.AccessRepository;
import com.sizylift.backend.publication.service.AggregateType;
import org.springframework.stereotype.Service;

/**
 * Кто чьи данные читает и пишет (ADR-0007, раздел 6). Единственное место, где принимаются решения о доступе
 * к чужим данным; фан-аут в ленты использует те же правила, что и чтение.
 */
@Service
public class AccessPolicy {

    /** Что тренер видит у подопечного всегда (ADR-0006: тренировки, программы, профиль, рекорды). */
    public static final Set<String> COACH_VISIBLE = Set.of(AggregateType.WORKOUT, AggregateType.PROGRAM,
            AggregateType.EXERCISE, AggregateType.ATHLETE_PROFILE, AggregateType.ONE_REP_MAX);
    /** Травмы и противопоказания — только с разрешения подопечного. */
    public static final Set<String> HEALTH = Set.of(AggregateType.INJURY, AggregateType.CONTRAINDICATION);
    /** Что тренер пишет за подопечного. */
    public static final Set<String> COACH_WRITABLE = Set.of(AggregateType.PROGRAM);
    /** Порядок блокировок по пользователю: строковое представление совпадает с порядком uuid в PostgreSQL. */
    public static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

    private final AccessRepository relations;

    AccessPolicy(AccessRepository relations) {
        this.relations = relations;
    }

    /** Первым шагом транзакции записи данных владельца: связи не меняются до её коммита. */
    public void lockOwner(UUID ownerId) {
        relations.lockOwnerShared(ownerId);
    }

    /** Может ли {@code caller} вообще писать данные этого типа за {@code owner}; проверяется до блокировки агрегата. */
    public boolean canWriteFor(UUID caller, UUID owner, String type) {
        return caller.equals(owner) || COACH_WRITABLE.contains(type) && isActiveCoach(caller, owner);
    }

    /**
     * Право записи агрегата. {@code author} — сохранённый автор ({@code null} — пишет владелец),
     * {@code exists} — агрегат уже записывался.
     */
    public boolean canWrite(UUID caller, UUID owner, String type, UUID author, boolean exists) {
        if (caller.equals(owner)) {
            return author == null || author.equals(owner);
        }
        if (!COACH_WRITABLE.contains(type) || !isActiveCoach(caller, owner)) {
            return false;
        }
        return exists ? caller.equals(author) : true;
    }

    public boolean canRead(UUID caller, UUID owner, String type) {
        if (caller.equals(owner)) {
            return true;
        }
        return relations.activeCoachOf(owner)
                .filter(relation -> relation.coachId().equals(caller))
                .filter(relation -> coachSees(relation, type))
                .isPresent();
    }

    /** Чьи данные этого типа видит {@code caller}: он сам и активные подопечные. */
    public List<UUID> readableOwners(UUID caller, String type) {
        return Stream.concat(Stream.of(caller), relations.activeAthletesOf(caller).stream()
                        .filter(relation -> coachSees(relation, type))
                        .map(CoachingRelation::athleteId))
                .toList();
    }

    /** Чьи ленты получают изменение агрегата владельца — в порядке блокировки курсоров. */
    public List<UUID> recipients(UUID owner, String type) {
        var recipients = new ArrayList<UUID>();
        recipients.add(owner);
        relations.activeCoachOf(owner)
                .filter(relation -> coachSees(relation, type))
                .ifPresent(relation -> recipients.add(relation.coachId()));
        recipients.sort(LOCK_ORDER);
        return recipients;
    }

    /** Типы, которые тренер видит при текущем разрешении на травмы. */
    public static Set<String> coachVisibleTypes(boolean healthAccess) {
        if (!healthAccess) {
            return COACH_VISIBLE;
        }
        var types = new HashSet<>(COACH_VISIBLE);
        types.addAll(HEALTH);
        return Set.copyOf(types);
    }

    private boolean isActiveCoach(UUID caller, UUID owner) {
        return relations.activeCoachOf(owner).filter(relation -> relation.coachId().equals(caller)).isPresent();
    }

    private static boolean coachSees(CoachingRelation relation, String type) {
        return COACH_VISIBLE.contains(type) || relation.healthAccess() && HEALTH.contains(type);
    }
}
