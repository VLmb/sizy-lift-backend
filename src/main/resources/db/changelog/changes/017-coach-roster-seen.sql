--liquibase formatted sql

--changeset sizylift:017-coach-roster-seen
ALTER TABLE sizylift.coaching_link ADD COLUMN coach_seen_at timestamptz;

--rollback ALTER TABLE sizylift.coaching_link DROP COLUMN coach_seen_at;
