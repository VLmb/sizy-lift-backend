--liquibase formatted sql

--changeset sizylift:053-reschedule-skip
-- PWA-09: отметка пропуска плановой тренировки едет в агрегате workout_reschedule и синхронизируется с телефоном,
-- вебом и тренером. planned_workout_skip остаётся проекцией для чтения в вебе и пишется вместе с переносом.
ALTER TABLE sizylift.workout_reschedule ADD COLUMN skipped boolean NOT NULL DEFAULT false;
--rollback ALTER TABLE sizylift.workout_reschedule DROP COLUMN skipped;
