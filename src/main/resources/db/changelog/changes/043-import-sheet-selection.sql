--liquibase formatted sql

--changeset sizylift:043-import-sheet-selection
-- Книга с несколькими листами ждёт выбора листов до вызова модели: в модель уходят только выбранные.
ALTER TABLE sizylift.import_session DROP CONSTRAINT import_session_status_check;
ALTER TABLE sizylift.import_session ADD CONSTRAINT import_session_status_check CHECK (status IN
    ('queued','processing','sheet_selection','clarification_required','review','committed','failed','cancelled','expired'));
ALTER TABLE sizylift.import_session ADD COLUMN sheets_selected boolean NOT NULL DEFAULT false;
--rollback ALTER TABLE sizylift.import_session DROP COLUMN sheets_selected;
--rollback ALTER TABLE sizylift.import_session DROP CONSTRAINT import_session_status_check;
--rollback ALTER TABLE sizylift.import_session ADD CONSTRAINT import_session_status_check CHECK (status IN ('queued','processing','clarification_required','review','committed','failed','cancelled','expired'));
