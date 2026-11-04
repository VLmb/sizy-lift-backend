--liquibase formatted sql
--changeset sizylift:032-import-progress
-- Прогресс текущего разбора по потоку ответа модели: этап, найденные упражнения, готовые недели.
ALTER TABLE sizylift.import_session ADD COLUMN progress jsonb;
--rollback ALTER TABLE sizylift.import_session DROP COLUMN progress;
