--liquibase formatted sql

--changeset sizylift:020-coach-inbox
-- «Входящие» тренера (docs/coach-workspace.md, раздел 4). Событие висит на связи: после разрыва его не видно,
-- а удаление связи удаляет и события. dedup_key склеивает повторы одного события по одному объекту.
CREATE TABLE sizylift.coach_event (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    link_id      uuid        NOT NULL REFERENCES sizylift.coaching_link (id) ON DELETE CASCADE,
    kind         text        NOT NULL CHECK (kind IN ('workout_completed', 'workout_rescheduled', 'workout_missed',
                                                      'program_ending', 'invite_accepted')),
    subject_id   uuid,
    subject_date date,
    occurred_at  timestamptz NOT NULL,
    seen_at      timestamptz,
    dedup_key    text        NOT NULL,
    UNIQUE (link_id, dedup_key)
);

-- Лента тренера по связи от новых к старым и курсор before.
CREATE INDEX coach_event_feed_idx ON sizylift.coach_event (link_id, occurred_at DESC, id DESC);
-- Счётчики непросмотренного и самое старое непросмотренное в списке подопечных.
CREATE INDEX coach_event_unseen_idx ON sizylift.coach_event (link_id, occurred_at) WHERE seen_at IS NULL;
-- Отметка просмотра по объекту и удаление событий удалённой тренировки.
CREATE INDEX coach_event_subject_idx ON sizylift.coach_event (subject_id) WHERE subject_id IS NOT NULL;
-- Ежедневная задача ищет завершённую тренировку по плановой.
CREATE INDEX workout_planned_workout_idx ON sizylift.workout (user_id, planned_workout_id)
    WHERE planned_workout_id IS NOT NULL;

--rollback DROP INDEX sizylift.workout_planned_workout_idx;
--rollback DROP TABLE sizylift.coach_event;
