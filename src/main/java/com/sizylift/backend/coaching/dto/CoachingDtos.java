package com.sizylift.backend.coaching.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import java.time.LocalDate;

import com.sizylift.backend.coaching.service.CoachProgramCopies;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

    /** Что подопечный видит до принятия: кто приглашает и какие данные увидит тренер. */
    public record InvitePreviewResponse(UUID coachId, String coachName, Instant expiresAt, List<String> coachSees,
                                        List<String> coachSeesWithHealthAccess) {
    }

    /** Создание приглашения; {@code programId} — необязательная своя программа тренера (CR-02). */
    public record CreateInviteRequest(UUID programId) {
    }

    /**
     * Страница приглашения до входа (CR-02): имя тренера, что он увидит, и состав привязанной программы
     * ({@code null}, если её нет). Больше ничего: ни id тренера, ни срока, ни весов.
     */
    public record PublicInviteResponse(String coachName, List<String> coachSees,
                                       List<String> coachSeesWithHealthAccess, CoachProgramCopies.Preview program) {
    }

    public static final int NOTES_MAX = 4000;
    public static final int START_NAME_MAX = 200;
    public static final int TARGETS_MAX = 20;

    /**
     * Карточка подопечного у тренера (CR-18): заметки, старт и целевые упражнения. {@code targetExerciseIds}
     * {@code null} — тренер не выбирал (клиент берёт присед, жим лёжа и становую); пустой список — выбрал «ничего».
     */
    public record CardRequest(@Size(max = NOTES_MAX) String notes, LocalDate startDate,
                              @Size(max = START_NAME_MAX) String startName,
                              @Size(max = TARGETS_MAX) List<@NotNull UUID> targetExerciseIds) {
    }

    public record CardResponse(String notes, LocalDate startDate, String startName, List<UUID> targetExerciseIds,
                               Instant updatedAt) {
    }
}
