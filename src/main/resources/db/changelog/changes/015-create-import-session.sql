--liquibase formatted sql

--changeset sizylift:015-enable-pg-trgm
-- Нечёткое сопоставление названий упражнений при ИИ-импорте (ADR-0008). Расширение доверенное
-- (PostgreSQL 13+), владелец базы ставит его без прав суперпользователя.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX exercise_name_trgm_idx ON sizylift.exercise USING gin (lower(name) public.gin_trgm_ops);

--rollback DROP INDEX sizylift.exercise_name_trgm_idx;

--changeset sizylift:015-create-import-session
-- Сессия ИИ-импорта: вопросы, ответы и черновик; живёт сутки. pending — идёт вызов модели.
CREATE TABLE sizylift.import_session (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    target_owner_id uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    source          text        NOT NULL CHECK (source IN ('text', 'sheet_url', 'file')),
    input           text        NOT NULL,
    start_date      date        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('pending', 'questions', 'draft', 'failed')),
    rounds          jsonb       NOT NULL DEFAULT '[]',
    draft           jsonb,
    tokens_in       integer     NOT NULL DEFAULT 0,
    tokens_out      integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL
);

CREATE INDEX import_session_user_created_idx ON sizylift.import_session (user_id, created_at);
CREATE INDEX import_session_expires_idx ON sizylift.import_session (expires_at);

--rollback DROP TABLE sizylift.import_session;
