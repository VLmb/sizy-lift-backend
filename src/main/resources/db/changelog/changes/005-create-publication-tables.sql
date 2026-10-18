--liquibase formatted sql

--changeset sizylift:005-create-publication-tables
-- Copy of data published from the phone (docs/publication.md). Every row belongs to a user;
-- ids come from the app, so keys include user_id. Units match the app: grams, ms, metres, RPE x10.

-- Last accepted version of every aggregate; makes repeated and reordered deliveries harmless.
CREATE TABLE sizylift.published_aggregate (
    user_id      uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    type         text        NOT NULL CHECK (type IN ('workout', 'program', 'exercise', 'athlete_profile', 'one_rep_max', 'injury', 'contraindication')),
    aggregate_id uuid        NOT NULL,
    version      bigint      NOT NULL CHECK (version >= 0),
    deleted      boolean     NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, type, aggregate_id)
);

-- Completed workouts (fact) --------------------------------------------------------------

CREATE TABLE sizylift.workout (
    user_id            uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    id                 uuid        NOT NULL,
    workout_date       date        NOT NULL,
    title              text,
    description        text,
    started_at         timestamptz NOT NULL,
    completed_at       timestamptz NOT NULL,
    quality            text        NOT NULL CHECK (quality IN ('bad', 'okay', 'great')),
    planned_workout_id uuid,
    updated_at         timestamptz NOT NULL,
    PRIMARY KEY (user_id, id)
);

CREATE INDEX workout_user_date_idx ON sizylift.workout (user_id, workout_date);

CREATE TABLE sizylift.workout_block (
    user_id          uuid    NOT NULL,
    id               uuid    NOT NULL,
    workout_id       uuid    NOT NULL,
    position         integer NOT NULL CHECK (position >= 0),
    block_type       text    NOT NULL CHECK (block_type IN ('single', 'group', 'circuit', 'interval', 'free')),
    group_label      text CHECK (group_label IN ('superset', 'triset', 'giant_set')),
    planned_rounds   integer CHECK (planned_rounds >= 1),
    current_round    integer CHECK (current_round >= 1),
    completed_rounds integer CHECK (completed_rounds >= 0),
    interval_work_ms integer CHECK (interval_work_ms > 0),
    interval_rest_ms integer CHECK (interval_rest_ms >= 0),
    free_kind        text CHECK (free_kind IN ('warmup', 'cooldown', 'mobility')),
    title            text,
    description      text,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, workout_id) REFERENCES sizylift.workout (user_id, id) ON DELETE CASCADE
);

CREATE TABLE sizylift.workout_exercise (
    user_id                uuid    NOT NULL,
    id                     uuid    NOT NULL,
    workout_id             uuid    NOT NULL,
    block_id               uuid    NOT NULL,
    position               integer NOT NULL CHECK (position >= 0),
    exercise_id            uuid    NOT NULL,
    exercise_name          text    NOT NULL,
    exercise_short_name    text,
    result_type            text    NOT NULL CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'duration', 'distance_time', 'free')),
    original_exercise_id   uuid    NOT NULL,
    original_exercise_name text    NOT NULL,
    actual_comment         text,
    plan_comment           text,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, block_id) REFERENCES sizylift.workout_block (user_id, id) ON DELETE CASCADE
);

CREATE INDEX workout_exercise_history_idx ON sizylift.workout_exercise (user_id, exercise_id);

CREATE TABLE sizylift.workout_set (
    user_id                uuid    NOT NULL,
    id                     uuid    NOT NULL,
    workout_id             uuid    NOT NULL,
    workout_exercise_id    uuid    NOT NULL,
    position               integer NOT NULL CHECK (position >= 0),
    result_type            text    NOT NULL CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'duration', 'distance_time', 'free')),
    set_type               text    NOT NULL CHECK (set_type IN ('warmup', 'working', 'top', 'backoff', 'drop')),
    completion_status      text    NOT NULL CHECK (completion_status IN ('pending', 'completed', 'skipped')),
    weight_grams           integer CHECK (weight_grams >= 0),
    repetitions            integer CHECK (repetitions >= 0),
    duration_ms            integer CHECK (duration_ms >= 0),
    distance_m             integer CHECK (distance_m >= 0),
    rpe_tenths             integer CHECK (rpe_tenths BETWEEN 50 AND 100 AND rpe_tenths % 5 = 0),
    average_heart_rate_bpm integer CHECK (average_heart_rate_bpm > 0),
    maximum_heart_rate_bpm integer CHECK (maximum_heart_rate_bpm > 0),
    power_watts            integer CHECK (power_watts >= 0),
    elevation_gain_m       integer CHECK (elevation_gain_m >= 0),
    note                   text,
    completed_at           timestamptz,
    source_planned_set_id  uuid,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, workout_exercise_id) REFERENCES sizylift.workout_exercise (user_id, id) ON DELETE CASCADE
);

-- Plan targets captured when a planned workout was started ("plan vs fact").
CREATE TABLE sizylift.workout_plan_set_snapshot (
    user_id                    uuid    NOT NULL,
    id                         uuid    NOT NULL,
    workout_id                 uuid    NOT NULL,
    actual_set_id              uuid,
    source_planned_exercise_id uuid    NOT NULL,
    source_planned_set_id      uuid    NOT NULL,
    position                   integer NOT NULL CHECK (position >= 0),
    result_type                text    NOT NULL CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'duration', 'distance_time', 'free')),
    set_type                   text    NOT NULL CHECK (set_type IN ('warmup', 'working', 'top', 'backoff', 'drop')),
    target_weight_grams        integer CHECK (target_weight_grams >= 0),
    repetitions                integer CHECK (repetitions >= 0),
    duration_ms                integer CHECK (duration_ms >= 0),
    target_distance_m          integer CHECK (target_distance_m >= 0),
    target_rpe_tenths          integer CHECK (target_rpe_tenths BETWEEN 50 AND 100 AND target_rpe_tenths % 5 = 0),
    rest_ms                    integer CHECK (rest_ms >= 0),
    note                       text,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, workout_id) REFERENCES sizylift.workout (user_id, id) ON DELETE CASCADE
);

CREATE TABLE sizylift.workout_record_event (
    user_id             uuid        NOT NULL,
    id                  uuid        NOT NULL,
    workout_id          uuid        NOT NULL,
    workout_exercise_id uuid        NOT NULL,
    actual_set_id       uuid        NOT NULL,
    record_type         text        NOT NULL CHECK (record_type IN ('maximum_weight', 'estimated_one_rep_max')),
    value               integer     NOT NULL CHECK (value >= 0),
    previous_value      integer CHECK (previous_value >= 0),
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, workout_id) REFERENCES sizylift.workout (user_id, id) ON DELETE CASCADE
);

-- Training programs (plan) ----------------------------------------------------------------

CREATE TABLE sizylift.program (
    user_id     uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    id          uuid        NOT NULL,
    name        text        NOT NULL,
    description text,
    start_date  date,
    status      text        NOT NULL CHECK (status IN ('draft', 'active')),
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, id)
);

CREATE TABLE sizylift.program_cycle (
    user_id    uuid    NOT NULL,
    id         uuid    NOT NULL,
    program_id uuid    NOT NULL,
    name       text    NOT NULL,
    position   integer NOT NULL CHECK (position >= 0),
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, program_id) REFERENCES sizylift.program (user_id, id) ON DELETE CASCADE
);

CREATE TABLE sizylift.training_week (
    user_id    uuid    NOT NULL,
    id         uuid    NOT NULL,
    program_id uuid    NOT NULL,
    cycle_id   uuid    NOT NULL,
    number     integer NOT NULL CHECK (number >= 1),
    name       text,
    start_date date    NOT NULL,
    end_date   date    NOT NULL CHECK (start_date <= end_date),
    position   integer NOT NULL CHECK (position >= 0),
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, cycle_id) REFERENCES sizylift.program_cycle (user_id, id) ON DELETE CASCADE
);

CREATE TABLE sizylift.planned_workout (
    user_id          uuid    NOT NULL,
    id               uuid    NOT NULL,
    program_id       uuid    NOT NULL,
    training_week_id uuid    NOT NULL,
    title            text,
    workout_date     date    NOT NULL,
    position         integer NOT NULL CHECK (position >= 0),
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, training_week_id) REFERENCES sizylift.training_week (user_id, id) ON DELETE CASCADE
);

CREATE INDEX planned_workout_user_date_idx ON sizylift.planned_workout (user_id, workout_date);

CREATE TABLE sizylift.planned_block (
    user_id            uuid    NOT NULL,
    id                 uuid    NOT NULL,
    program_id         uuid    NOT NULL,
    planned_workout_id uuid    NOT NULL,
    position           integer NOT NULL CHECK (position >= 0),
    block_type         text    NOT NULL CHECK (block_type IN ('single', 'group', 'circuit', 'interval', 'free')),
    group_label        text CHECK (group_label IN ('superset', 'triset', 'giant_set')),
    free_kind          text CHECK (free_kind IN ('warmup', 'cooldown', 'mobility')),
    title              text,
    description        text,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, planned_workout_id) REFERENCES sizylift.planned_workout (user_id, id) ON DELETE CASCADE
);

CREATE TABLE sizylift.planned_exercise (
    user_id          uuid    NOT NULL,
    id               uuid    NOT NULL,
    program_id       uuid    NOT NULL,
    planned_block_id uuid    NOT NULL,
    position         integer NOT NULL CHECK (position >= 0),
    exercise_id      uuid    NOT NULL,
    exercise_name    text    NOT NULL,
    result_type      text    NOT NULL CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'duration', 'distance_time', 'free')),
    plan_comment     text,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, planned_block_id) REFERENCES sizylift.planned_block (user_id, id) ON DELETE CASCADE
);

CREATE TABLE sizylift.planned_set (
    user_id             uuid    NOT NULL,
    id                  uuid    NOT NULL,
    program_id          uuid    NOT NULL,
    planned_exercise_id uuid    NOT NULL,
    position            integer NOT NULL CHECK (position >= 0),
    result_type         text    NOT NULL CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'duration', 'distance_time', 'free')),
    set_type            text    NOT NULL CHECK (set_type IN ('warmup', 'working', 'top', 'backoff', 'drop')),
    target_weight_grams integer CHECK (target_weight_grams >= 0),
    repetitions         integer CHECK (repetitions >= 0),
    duration_ms         integer CHECK (duration_ms >= 0),
    target_distance_m   integer CHECK (target_distance_m >= 0),
    target_rpe_tenths   integer CHECK (target_rpe_tenths BETWEEN 50 AND 100 AND target_rpe_tenths % 5 = 0),
    rest_ms             integer CHECK (rest_ms >= 0),
    note                text,
    PRIMARY KEY (user_id, id),
    FOREIGN KEY (user_id, planned_exercise_id) REFERENCES sizylift.planned_exercise (user_id, id) ON DELETE CASCADE
);

-- Athlete ----------------------------------------------------------------------------------

CREATE TABLE sizylift.athlete_profile (
    user_id              uuid PRIMARY KEY REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    id                   uuid        NOT NULL,
    display_name         text,
    birth_date           date,
    sex                  text CHECK (sex IN ('female', 'male', 'other', 'unspecified')),
    height_mm            integer CHECK (height_mm > 0),
    weight_grams         integer CHECK (weight_grams > 0),
    training_started_on  date,
    sport_directions     text[]      NOT NULL DEFAULT '{}',
    goals                text,
    goal_deadline        date,
    equipment_notes      text,
    exercise_preferences text,
    schedule             text,
    competition_notes    text,
    updated_at           timestamptz NOT NULL
);

-- Actual one-rep max entered by the user; record events live with their workouts.
CREATE TABLE sizylift.one_rep_max (
    user_id      uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    exercise_id  uuid        NOT NULL,
    weight_grams integer     NOT NULL CHECK (weight_grams > 0),
    achieved_on  date        NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (user_id, exercise_id)
);

-- Sensitive: published only with the separate "health" consent.
CREATE TABLE sizylift.injury (
    user_id     uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    id          uuid        NOT NULL,
    name        text        NOT NULL,
    description text,
    status      text        NOT NULL CHECK (status IN ('active', 'past')),
    started_on  date,
    ended_on    date,
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, id)
);

CREATE TABLE sizylift.contraindication (
    user_id     uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    id          uuid        NOT NULL,
    name        text        NOT NULL,
    description text,
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, id)
);

--rollback DROP TABLE sizylift.contraindication, sizylift.injury, sizylift.one_rep_max, sizylift.athlete_profile, sizylift.planned_set, sizylift.planned_exercise, sizylift.planned_block, sizylift.planned_workout, sizylift.training_week, sizylift.program_cycle, sizylift.program, sizylift.workout_record_event, sizylift.workout_plan_set_snapshot, sizylift.workout_set, sizylift.workout_exercise, sizylift.workout_block, sizylift.workout, sizylift.published_aggregate;
