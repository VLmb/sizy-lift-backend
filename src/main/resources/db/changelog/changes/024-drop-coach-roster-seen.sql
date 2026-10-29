--liquibase formatted sql

--changeset sizylift:024-drop-coach-roster-seen
-- Отметка просмотра подопечного заменена «Входящими» (coach_event.seen_at); веб её больше не пишет и не читает.
ALTER TABLE sizylift.coaching_link DROP COLUMN coach_seen_at;

--rollback ALTER TABLE sizylift.coaching_link ADD COLUMN coach_seen_at timestamptz;
