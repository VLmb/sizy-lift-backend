--liquibase formatted sql

--changeset sizylift:034-coach-rework-inbox
-- Переделка тренерского режима, этап 1 (docs/coach-workspace.md, раздел 7).
-- Комментарий, оставленный подопечным при завершении тренировки, приложение помечает флагом: он показывается
-- в строке «выполнил», а остальные комментарии подопечного дают событие comment_added.
ALTER TABLE sizylift.workout_comment ADD COLUMN at_completion boolean NOT NULL DEFAULT false;

-- comment_added: subject_id — тренировка (её открытие отмечает событие просмотренным), comment_id — комментарий.
ALTER TABLE sizylift.coach_event ADD COLUMN comment_id uuid;
ALTER TABLE sizylift.coach_event DROP CONSTRAINT coach_event_kind_check;
ALTER TABLE sizylift.coach_event ADD CONSTRAINT coach_event_kind_check
    CHECK (kind IN ('workout_completed', 'workout_rescheduled', 'workout_missed', 'program_ending',
                    'invite_accepted', 'comment_added'));
ALTER TABLE sizylift.coach_event ADD CONSTRAINT coach_event_comment_check
    CHECK ((kind = 'comment_added') = (comment_id IS NOT NULL));
CREATE INDEX coach_event_comment_idx ON sizylift.coach_event (comment_id) WHERE comment_id IS NOT NULL;

--rollback DROP INDEX sizylift.coach_event_comment_idx;
--rollback DELETE FROM sizylift.coach_event WHERE kind = 'comment_added';
--rollback ALTER TABLE sizylift.coach_event DROP CONSTRAINT coach_event_comment_check;
--rollback ALTER TABLE sizylift.coach_event DROP CONSTRAINT coach_event_kind_check;
--rollback ALTER TABLE sizylift.coach_event ADD CONSTRAINT coach_event_kind_check CHECK (kind IN ('workout_completed', 'workout_rescheduled', 'workout_missed', 'program_ending', 'invite_accepted'));
--rollback ALTER TABLE sizylift.coach_event DROP COLUMN comment_id;
--rollback ALTER TABLE sizylift.workout_comment DROP COLUMN at_completion;
