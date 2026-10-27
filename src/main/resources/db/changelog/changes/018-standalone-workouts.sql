--liquibase formatted sql

--changeset sizylift:018-standalone-workouts
CREATE TABLE sizylift.standalone_workout (
    user_id uuid NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    original_date date NOT NULL,
    workout_date date NOT NULL,
    payload jsonb,
    deleted boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id),
    CHECK (deleted OR payload IS NOT NULL)
);
CREATE INDEX standalone_workout_date_idx ON sizylift.standalone_workout (user_id, workout_date) WHERE NOT deleted;

--rollback DROP TABLE sizylift.standalone_workout;
