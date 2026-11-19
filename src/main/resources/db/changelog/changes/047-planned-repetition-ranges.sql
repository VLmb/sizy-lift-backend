-- liquibase formatted sql
-- changeset sizylift:047-planned-repetition-ranges
ALTER TABLE sizylift.planned_set
    ADD COLUMN repetitions_max integer,
    ADD CONSTRAINT planned_set_repetition_range_check
        CHECK (repetitions_max IS NULL OR (repetitions IS NOT NULL AND repetitions > 0 AND repetitions_max > repetitions AND repetitions_max <= 1000));

ALTER TABLE sizylift.workout_plan_set_snapshot
    ADD COLUMN repetitions_max integer,
    ADD CONSTRAINT workout_plan_set_snapshot_repetition_range_check
        CHECK (repetitions_max IS NULL OR (repetitions IS NOT NULL AND repetitions > 0 AND repetitions_max > repetitions AND repetitions_max <= 1000));

--rollback ALTER TABLE sizylift.workout_plan_set_snapshot DROP CONSTRAINT workout_plan_set_snapshot_repetition_range_check, DROP COLUMN repetitions_max;
--rollback ALTER TABLE sizylift.planned_set DROP CONSTRAINT planned_set_repetition_range_check, DROP COLUMN repetitions_max;
