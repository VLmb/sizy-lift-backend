--liquibase formatted sql

--changeset sizylift:003-create-exercise-catalog
CREATE TABLE sizylift.movement_category (
    id   uuid PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL
);

CREATE TABLE sizylift.equipment (
    id   uuid PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL
);

CREATE TABLE sizylift.muscle_group (
    id   uuid PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL
);

-- System exercises (user_id IS NULL) come from the app's catalog seed with the same UUIDs;
-- user exercises are published from the phone.
CREATE TABLE sizylift.exercise (
    id                   uuid PRIMARY KEY,
    user_id              uuid REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    name                 text    NOT NULL,
    short_name           text,
    movement_category_id uuid REFERENCES sizylift.movement_category (id),
    result_type          text    NOT NULL CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'duration', 'distance_time', 'free')),
    is_sbd               boolean NOT NULL DEFAULT false,
    note                 text,
    updated_at           timestamptz
);

CREATE INDEX exercise_user_idx ON sizylift.exercise (user_id);

CREATE TABLE sizylift.exercise_muscle (
    exercise_id     uuid NOT NULL REFERENCES sizylift.exercise (id) ON DELETE CASCADE,
    muscle_group_id uuid NOT NULL REFERENCES sizylift.muscle_group (id),
    role            text NOT NULL CHECK (role IN ('primary', 'secondary')),
    PRIMARY KEY (exercise_id, muscle_group_id, role)
);

CREATE TABLE sizylift.exercise_equipment (
    exercise_id  uuid NOT NULL REFERENCES sizylift.exercise (id) ON DELETE CASCADE,
    equipment_id uuid NOT NULL REFERENCES sizylift.equipment (id),
    PRIMARY KEY (exercise_id, equipment_id)
);

CREATE TABLE sizylift.exercise_catalog_version (
    version    text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

--rollback DROP TABLE sizylift.exercise_catalog_version, sizylift.exercise_equipment, sizylift.exercise_muscle, sizylift.exercise, sizylift.muscle_group, sizylift.equipment, sizylift.movement_category;
