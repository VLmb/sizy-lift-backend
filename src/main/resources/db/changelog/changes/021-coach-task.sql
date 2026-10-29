--liquibase formatted sql

--changeset sizylift:021-coach-task
-- Задачи тренера по подопечному (docs/coach-workspace.md, раздел 5). Видит только тренер и только по активной
-- связи; новая связь с тем же подопечным начинается без задач.
CREATE TABLE sizylift.coach_task (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    link_id    uuid        NOT NULL REFERENCES sizylift.coaching_link (id) ON DELETE CASCADE,
    text       text        NOT NULL CHECK (char_length(text) BETWEEN 1 AND 500),
    due_on     date,
    done_at    timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX coach_task_link_idx ON sizylift.coach_task (link_id);
-- Счётчики открытых и просроченных задач в списке подопечных.
CREATE INDEX coach_task_open_idx ON sizylift.coach_task (link_id, due_on) WHERE done_at IS NULL;

--rollback DROP TABLE sizylift.coach_task;
