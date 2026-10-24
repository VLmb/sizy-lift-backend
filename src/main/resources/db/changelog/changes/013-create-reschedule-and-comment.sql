--liquibase formatted sql

--changeset sizylift:013-create-reschedule-and-comment
-- Данные «между двумя» (ADR-0007, разделы 7–8). Оба агрегата лежат под подопечным: перенос — его,
-- комментарий — к его тренировке. Кто написал комментарий, хранит published_aggregate.author_user_id.
-- Ссылки на программу и тренировку без FK, как у остальных агрегатов: агрегаты приходят в любом порядке,
-- а программа и тренировка заменяются целиком. Каскады выполняет сервер и пишет delete в ленты.
ALTER TABLE sizylift.published_aggregate DROP CONSTRAINT published_aggregate_type_check;
ALTER TABLE sizylift.published_aggregate ADD CONSTRAINT published_aggregate_type_check
    CHECK (type IN ('workout', 'program', 'exercise', 'athlete_profile', 'one_rep_max', 'injury',
                    'contraindication', 'workout_reschedule', 'workout_comment'));

CREATE TABLE sizylift.workout_reschedule (
    user_id            uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    planned_workout_id uuid        NOT NULL,
    program_id         uuid        NOT NULL,
    scheduled_date     date        NOT NULL,
    updated_at         timestamptz NOT NULL,
    PRIMARY KEY (user_id, planned_workout_id)
);

CREATE INDEX workout_reschedule_program_idx ON sizylift.workout_reschedule (user_id, program_id);

CREATE TABLE sizylift.workout_comment (
    user_id    uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    id         uuid        NOT NULL,
    workout_id uuid        NOT NULL,
    text       text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, id)
);

CREATE INDEX workout_comment_workout_idx ON sizylift.workout_comment (user_id, workout_id);

--rollback DROP TABLE sizylift.workout_comment;
--rollback DROP TABLE sizylift.workout_reschedule;
--rollback ALTER TABLE sizylift.published_aggregate DROP CONSTRAINT published_aggregate_type_check;
--rollback ALTER TABLE sizylift.published_aggregate ADD CONSTRAINT published_aggregate_type_check CHECK (type IN ('workout', 'program', 'exercise', 'athlete_profile', 'one_rep_max', 'injury', 'contraindication'));
