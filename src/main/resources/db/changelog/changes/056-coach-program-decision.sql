--liquibase formatted sql

--changeset sizylift:056-coach-program-decision
ALTER TABLE sizylift.published_aggregate DROP CONSTRAINT published_aggregate_type_check;
ALTER TABLE sizylift.published_aggregate ADD CONSTRAINT published_aggregate_type_check
    CHECK (type IN ('workout', 'program', 'exercise', 'athlete_profile', 'one_rep_max', 'injury',
                    'contraindication', 'workout_reschedule', 'workout_comment', 'standalone_workout',
                    'program_decision'));

-- Строка живёт отдельно от программы: редактор программы заменяет её целиком.
CREATE TABLE sizylift.coach_program_decision (
    user_id     uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    program_id  uuid        NOT NULL,
    decision    text        NOT NULL CHECK (decision IN ('pending', 'accepted', 'declined')),
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, program_id)
);

--rollback DROP TABLE sizylift.coach_program_decision;
--rollback DELETE FROM sizylift.sync_feed WHERE type = 'program_decision';
--rollback DELETE FROM sizylift.aggregate_client_version WHERE type = 'program_decision';
--rollback DELETE FROM sizylift.aggregate_conflict WHERE type = 'program_decision';
--rollback DELETE FROM sizylift.published_aggregate WHERE type = 'program_decision';
--rollback ALTER TABLE sizylift.published_aggregate DROP CONSTRAINT published_aggregate_type_check;
--rollback ALTER TABLE sizylift.published_aggregate ADD CONSTRAINT published_aggregate_type_check CHECK (type IN ('workout', 'program', 'exercise', 'athlete_profile', 'one_rep_max', 'injury', 'contraindication', 'workout_reschedule', 'workout_comment', 'standalone_workout'));
