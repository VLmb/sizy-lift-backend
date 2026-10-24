package com.sizylift.backend.coaching.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sizylift.backend.access.service.AccessPolicy;
import com.sizylift.backend.coaching.entity.CoachingInvite;
import com.sizylift.backend.coaching.entity.CoachingLink;
import com.sizylift.backend.coaching.repository.CoachingRepository;
import com.sizylift.backend.coaching.service.CoachingMetrics.Event;
import com.sizylift.backend.notifications.entity.NotificationKind;
import com.sizylift.backend.notifications.service.NotificationQueue;
import com.sizylift.backend.publication.repository.PublishedAggregateRepository;
import com.sizylift.backend.publication.service.AggregateType;
import com.sizylift.backend.sync.service.FeedMaintenance;
import com.sizylift.backend.sync.service.FeedMaintenance.Operation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Режим тренера, приглашения и связи (ADR-0007, раздел 5). Каждая операция со связью сначала берёт
 * исключительную блокировку аккаунта подопечного: запись его данных берёт разделяемую, поэтому фан-аут
 * в ленты не видит связь наполовину изменённой.
 */
@Service
public class CoachingService {

    static final Duration INVITE_TTL = Duration.ofDays(7);

    private final CoachingRepository coaching;
    private final PublishedAggregateRepository aggregates;
    private final FeedMaintenance feeds;
    private final InviteTokens tokens;
    private final TransactionTemplate transactions;
    private final CoachingMetrics metrics;
    private final NotificationQueue notifications;
    private final Clock clock;

    CoachingService(CoachingRepository coaching, PublishedAggregateRepository aggregates, FeedMaintenance feeds,
                    InviteTokens tokens, TransactionTemplate transactions, CoachingMetrics metrics,
                    NotificationQueue notifications, Clock clock) {
        this.coaching = coaching;
        this.aggregates = aggregates;
        this.feeds = feeds;
        this.tokens = tokens;
        this.transactions = transactions;
        this.metrics = metrics;
        this.notifications = notifications;
        this.clock = clock;
    }

    public Overview overview(UUID userId) {
        return new Overview(coaching.coachEnabled(userId), coaching.activeAsAthlete(userId).orElse(null),
                coaching.activeAsCoach(userId));
    }

    /** Выключение режима запрещает новые приглашения; существующие связи остаются. */
    public Overview setCoachMode(UUID userId, boolean enabled) {
        coaching.setCoachMode(userId, enabled);
        return overview(userId);
    }

    public CreatedInvite createInvite(UUID coachId) {
        if (!coaching.coachEnabled(coachId)) {
            throw new CoachingExceptions.Forbidden();
        }
        var token = tokens.generate();
        var expiresAt = clock.instant().plus(INVITE_TTL);
        coaching.insertInvite(coachId, tokens.hash(token), expiresAt);
        metrics.count(Event.INVITE_CREATED);
        return new CreatedInvite(token, expiresAt);
    }

    /** Действующие приглашения тренера; токенов в ответе нет, они не хранятся. */
    public List<CoachingInvite> pendingInvites(UUID coachId) {
        return coaching.pendingInvites(coachId, clock.instant());
    }

    /**
     * Отзыв неиспользованного приглашения: ссылка начинает отвечать 410. Повторный отзыв ничего не меняет,
     * принятое приглашение отозвать нельзя — для этого разрывают связь.
     */
    public void revokeInvite(UUID coachId, UUID inviteId) {
        transactions.executeWithoutResult(status -> {
            var invite = coaching.lockInvite(inviteId).filter(found -> found.coachId().equals(coachId))
                    .orElseThrow(CoachingExceptions.NotFound::new);
            if (invite.acceptedAt() != null) {
                throw new CoachingExceptions.Conflict();
            }
            if (invite.revokedAt() == null) {
                coaching.revokeInvite(inviteId);
                metrics.count(Event.INVITE_REVOKED);
            }
        });
    }

    /** Что увидит подопечный до принятия: кто приглашает и до какого срока. */
    public CoachingInvite preview(String token) {
        var invite = findInvite(token).orElseThrow(CoachingExceptions.NotFound::new);
        if (!invite.usableAt(clock.instant())) {
            throw new CoachingExceptions.InviteGone();
        }
        return invite;
    }

    /** Действует ли приглашение по ссылке; для публичной страницы приглашения, без данных тренера. */
    public boolean inviteUsable(String token) {
        return findInvite(token).filter(invite -> invite.usableAt(clock.instant())).isPresent();
    }

    /** Создаёт связь и засевает ленту тренера всем, что он видит у подопечного. */
    public CoachingLink accept(UUID athleteId, String token) {
        if (!tokens.wellFormed(token)) {
            throw new CoachingExceptions.NotFound();
        }
        try {
            return transactions.execute(status -> {
                coaching.lockAccount(athleteId);
                var invite = coaching.lockInvite(tokens.hash(token)).orElseThrow(CoachingExceptions.NotFound::new);
                if (!invite.usableAt(clock.instant())) {
                    throw new CoachingExceptions.InviteGone();
                }
                if (invite.coachId().equals(athleteId) || coaching.activeAsAthlete(athleteId).isPresent()) {
                    throw new CoachingExceptions.Conflict();
                }
                var link = coaching.insertLink(invite.coachId(), athleteId);
                coaching.acceptInvite(invite.id(), athleteId);
                feeds.apply(List.of(new FeedMaintenance.Seed(link.coachId(), athleteId,
                        AccessPolicy.coachVisibleTypes(false))));
                notifications.enqueue(link.coachId(), NotificationKind.INVITE_ACCEPTED, link.id(),
                        Map.of(NotificationQueue.LINK_ID, link.id().toString(),
                                NotificationQueue.ACTOR_ID, athleteId.toString()));
                metrics.count(Event.LINK_CREATED);
                return link;
            });
        } catch (DuplicateKeyException e) {
            throw new CoachingExceptions.Conflict();
        }
    }

    /** Разрешение на травмы меняет только подопечный. */
    public CoachingLink setHealthAccess(UUID callerId, UUID linkId, boolean enabled) {
        var found = participantLink(callerId, linkId);
        if (!found.athleteId().equals(callerId)) {
            throw new CoachingExceptions.Forbidden();
        }
        return transactions.execute(status -> {
            coaching.lockAccount(found.athleteId());
            var link = coaching.lockLink(linkId);
            if (!link.active()) {
                throw new CoachingExceptions.Conflict();
            }
            if (link.healthAccess() == enabled) {
                return link;
            }
            coaching.setHealthAccess(linkId, enabled);
            Operation operation = enabled
                    ? new FeedMaintenance.Seed(link.coachId(), link.athleteId(), AccessPolicy.HEALTH)
                    : new FeedMaintenance.Revoke(link.coachId(), link.athleteId(), AccessPolicy.HEALTH);
            feeds.apply(List.of(operation));
            metrics.count(enabled ? Event.HEALTH_GRANTED : Event.HEALTH_REVOKED);
            return coaching.lockLink(linkId);
        });
    }

    /**
     * Разрыв любой стороной: тренеру — {@code revoke} по всем данным подопечного, программы тренера переходят
     * подопечному (автор сбрасывается, новая ревизия в его ленту). Повторный разрыв ничего не меняет.
     */
    public void end(UUID callerId, UUID linkId) {
        var found = participantLink(callerId, linkId);
        transactions.executeWithoutResult(status -> endLocked(found.athleteId(), linkId));
    }

    /**
     * Разрывает все активные связи пользователя в транзакции вызывающего (удаление аккаунта). Аккаунты
     * подопечных блокируются в {@link AccessPolicy#LOCK_ORDER}, затем собственный: после этого новая связь
     * с пользователем не появится до конца транзакции.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void endAll(UUID userId) {
        var asCoach = coaching.activeAsCoach(userId).stream()
                .sorted(Comparator.comparing(CoachingLink::athleteId, AccessPolicy.LOCK_ORDER)).toList();
        for (var link : asCoach) {
            endLocked(link.athleteId(), link.id());
        }
        coaching.lockAccount(userId);
        for (var link : coaching.activeAsCoach(userId)) {
            endLocked(link.athleteId(), link.id());
        }
        coaching.activeAsAthlete(userId).ifPresent(link -> endLocked(userId, link.id()));
    }

    private void endLocked(UUID athleteId, UUID linkId) {
        coaching.lockAccount(athleteId);
        var link = coaching.lockLink(linkId);
        if (!link.active()) {
            return;
        }
        coaching.endLink(linkId);
        var operations = new ArrayList<Operation>();
        for (var program : aggregates.transferToOwner(link.athleteId(), AggregateType.PROGRAM, link.coachId())) {
            operations.add(new FeedMaintenance.Upsert(link.athleteId(), link.athleteId(), AggregateType.PROGRAM,
                    program.aggregateId(), program.revision(), program.deleted()));
        }
        operations.add(new FeedMaintenance.Revoke(link.coachId(), link.athleteId(),
                AccessPolicy.coachVisibleTypes(true)));
        feeds.apply(operations);
        metrics.count(Event.LINK_ENDED);
    }

    public String displayName(UUID userId) {
        return coaching.displayName(userId);
    }

    private CoachingLink participantLink(UUID callerId, UUID linkId) {
        return coaching.findLink(linkId).filter(link -> link.involves(callerId))
                .orElseThrow(CoachingExceptions.NotFound::new);
    }

    private Optional<CoachingInvite> findInvite(String token) {
        return tokens.wellFormed(token) ? coaching.findInvite(tokens.hash(token)) : Optional.empty();
    }

    /** {@code token} отдаётся только создателю один раз; в базе его нет. */
    public record CreatedInvite(String token, Instant expiresAt) {
    }

    public record Overview(boolean coachEnabled, CoachingLink coach, List<CoachingLink> athletes) {
    }
}
