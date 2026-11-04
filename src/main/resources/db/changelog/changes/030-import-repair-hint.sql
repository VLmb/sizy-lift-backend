--liquibase formatted sql
--changeset sizylift:030-import-repair-hint
-- Конкретные ошибки отклонённого ответа модели для следующей технической попытки того же раунда.
ALTER TABLE sizylift.import_session ADD COLUMN repair_hint text;
--rollback ALTER TABLE sizylift.import_session DROP COLUMN repair_hint;
