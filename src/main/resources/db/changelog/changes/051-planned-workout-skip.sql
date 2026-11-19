--liquibase formatted sql

--changeset sizylift:051-planned-workout-skip
CREATE TABLE sizylift.planned_workout_skip (
    user_id            uuid NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    planned_workout_id uuid NOT NULL,
    PRIMARY KEY (user_id, planned_workout_id)
);
--rollback DROP TABLE sizylift.planned_workout_skip;
