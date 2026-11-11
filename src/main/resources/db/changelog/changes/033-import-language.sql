--liquibase formatted sql
--changeset sizylift:033-import-language
-- Язык уточняющих вопросов фиксируется при создании импорта и переживает перезапуск worker.
ALTER TABLE sizylift.import_session ADD COLUMN ui_language text NOT NULL DEFAULT 'ru'
    CHECK (ui_language IN ('ru', 'en'));
--rollback ALTER TABLE sizylift.import_session DROP COLUMN ui_language;
