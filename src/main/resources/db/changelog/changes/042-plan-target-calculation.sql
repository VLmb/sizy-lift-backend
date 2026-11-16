--liquibase formatted sql
--changeset sizylift:042-plan-target-calculation
ALTER TABLE sizylift.planned_set ADD COLUMN target_calculation varchar(64);
--rollback ALTER TABLE sizylift.planned_set DROP COLUMN target_calculation;
