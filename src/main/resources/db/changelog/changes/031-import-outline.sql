--liquibase formatted sql
--changeset sizylift:031-import-outline
-- План большой программы текущего раунда: недели разбираются частями, после перезапуска — без повторного плана.
ALTER TABLE sizylift.import_session ADD COLUMN outline jsonb;
--rollback ALTER TABLE sizylift.import_session DROP COLUMN outline;
