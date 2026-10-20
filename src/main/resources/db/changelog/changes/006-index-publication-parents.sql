--liquibase formatted sql

--changeset sizylift:006-index-publication-parents
-- Индексы поиска потомков при каскадной замене опубликованного агрегата.
CREATE INDEX workout_block_parent_idx ON sizylift.workout_block (user_id, workout_id);
CREATE INDEX workout_exercise_parent_idx ON sizylift.workout_exercise (user_id, block_id);
CREATE INDEX workout_set_parent_idx ON sizylift.workout_set (user_id, workout_exercise_id);
CREATE INDEX workout_plan_set_snapshot_parent_idx ON sizylift.workout_plan_set_snapshot (user_id, workout_id);
CREATE INDEX workout_record_event_parent_idx ON sizylift.workout_record_event (user_id, workout_id);
CREATE INDEX program_cycle_parent_idx ON sizylift.program_cycle (user_id, program_id);
CREATE INDEX training_week_parent_idx ON sizylift.training_week (user_id, cycle_id);
CREATE INDEX planned_workout_parent_idx ON sizylift.planned_workout (user_id, training_week_id);
CREATE INDEX planned_block_parent_idx ON sizylift.planned_block (user_id, planned_workout_id);
CREATE INDEX planned_exercise_parent_idx ON sizylift.planned_exercise (user_id, planned_block_id);
CREATE INDEX planned_set_parent_idx ON sizylift.planned_set (user_id, planned_exercise_id);

--rollback DROP INDEX sizylift.planned_set_parent_idx, sizylift.planned_exercise_parent_idx, sizylift.planned_block_parent_idx, sizylift.planned_workout_parent_idx, sizylift.training_week_parent_idx, sizylift.program_cycle_parent_idx, sizylift.workout_record_event_parent_idx, sizylift.workout_plan_set_snapshot_parent_idx, sizylift.workout_set_parent_idx, sizylift.workout_exercise_parent_idx, sizylift.workout_block_parent_idx;
