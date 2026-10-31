--liquibase formatted sql

--changeset sizylift:026-planned-set-rpe-auto
-- ADR-0011: RPE подхода посчитан клиентом (true) или введён вручную (false). Старые подходы — ручные.
ALTER TABLE sizylift.planned_set ADD COLUMN target_rpe_auto boolean NOT NULL DEFAULT false;

--rollback ALTER TABLE sizylift.planned_set DROP COLUMN target_rpe_auto;
