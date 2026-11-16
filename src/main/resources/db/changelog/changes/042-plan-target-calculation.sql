--liquibase formatted sql
--changeset sizylift:042-plan-target-calculation
ALTER TABLE sizylift.planned_set ADD COLUMN target_calculation varchar(64);
