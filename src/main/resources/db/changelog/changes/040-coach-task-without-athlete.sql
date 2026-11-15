--liquibase formatted sql

--changeset sizylift:040-coach-task-without-athlete
-- Общие задачи тренера без подопечного (напоминания себе): задача принадлежит тренеру, связь необязательна.
ALTER TABLE sizylift.coach_task
    ADD COLUMN coach_user_id uuid REFERENCES sizylift.user_account (id) ON DELETE CASCADE;
UPDATE sizylift.coach_task t SET coach_user_id = l.coach_user_id
FROM sizylift.coaching_link l WHERE l.id = t.link_id;
ALTER TABLE sizylift.coach_task ALTER COLUMN coach_user_id SET NOT NULL;
ALTER TABLE sizylift.coach_task ALTER COLUMN link_id DROP NOT NULL;
CREATE INDEX coach_task_coach_idx ON sizylift.coach_task (coach_user_id);
--rollback DELETE FROM sizylift.coach_task WHERE link_id IS NULL; ALTER TABLE sizylift.coach_task ALTER COLUMN link_id SET NOT NULL; DROP INDEX sizylift.coach_task_coach_idx; ALTER TABLE sizylift.coach_task DROP COLUMN coach_user_id;
