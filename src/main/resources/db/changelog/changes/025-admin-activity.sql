--liquibase formatted sql

--changeset sizylift:025-admin-activity
-- Первое обращение остаётся created_at; прежнюю активность достоверно восстановить нельзя.
ALTER TABLE sizylift.user_account ADD COLUMN last_seen_at timestamptz;
--rollback ALTER TABLE sizylift.user_account DROP COLUMN last_seen_at;
