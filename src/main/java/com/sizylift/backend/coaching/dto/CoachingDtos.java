package com.sizylift.backend.coaching.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Контракт /api/v2/coaching (docs/coaching.md). */
public final class CoachingDtos {

    private CoachingDtos() {
    }

    public record EnabledRequest(@NotNull Boolean enabled) {
    }

    /** {@code coach} — связь, где вызывающий подопечный; {@code athletes} — где он тренер. Только активные. */
    public record OverviewResponse(boolean coachEnabled, LinkResponse coach, List<LinkResponse> athletes) {
    }

    /** {@code counterpartName} — имя второй стороны из её профиля, если профиль опубликован. */
    public record LinkResponse(UUID id, UUID coachId, UUID athleteId, boolean healthAccess, Instant createdAt,
                               Instant endedAt, String counterpartName) {
    }

    /** Токен показывается один раз; ссылку {@code https://<домен>/invite/<token>} строит приложение. */
    public record InviteResponse(String token, Instant expiresAt) {
    }

    /** Действующее приглашение в списке тренера; токена нет — ссылку показывают только при создании. */
    public record PendingInviteResponse(UUID id, Instant createdAt, Instant expiresAt) {
    }

    /** Что подопечный видит до принятия: кто приглашает и какие данные увидит тренер. */
    public record InvitePreviewResponse(UUID coachId, String coachName, Instant expiresAt, List<String> coachSees,
                                        List<String> coachSeesWithHealthAccess) {
    }
}
