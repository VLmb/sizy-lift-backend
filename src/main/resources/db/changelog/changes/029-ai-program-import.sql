--liquibase formatted sql
--changeset sizylift:029-ai-program-import
CREATE TABLE sizylift.import_session (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES sizylift.user_account(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES sizylift.user_account(id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    request_hash text NOT NULL,
    status text NOT NULL CHECK (status IN ('queued','processing','clarification_required','review','committed','failed','cancelled','expired')),
    version bigint NOT NULL DEFAULT 1,
    source_kind text NOT NULL CHECK (source_kind IN ('text','file','sheet')),
    source_text text,
    source_bytes bytea,
    source_name text NOT NULL,
    normalized jsonb,
    catalog jsonb NOT NULL,
    model_output jsonb,
    decisions jsonb NOT NULL DEFAULT '{}',
    history jsonb NOT NULL DEFAULT '[]',
    name text NOT NULL,
    start_date date NOT NULL,
    program_id uuid NOT NULL,
    program_revision bigint,
    round integer NOT NULL DEFAULT 0,
    attempts integer NOT NULL DEFAULT 0,
    reserved_cost_micros bigint NOT NULL DEFAULT 0,
    input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,
    cached_tokens bigint NOT NULL DEFAULT 0,
    error_code text,
    error_message text,
    lease_token uuid,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    UNIQUE (user_id, request_id)
);
CREATE INDEX import_session_queue ON sizylift.import_session(next_attempt_at, created_at)
    WHERE status IN ('queued','processing');
CREATE INDEX import_session_expiry ON sizylift.import_session(expires_at);
CREATE INDEX import_session_user_created ON sizylift.import_session(user_id, created_at);
--rollback DROP TABLE sizylift.import_session;
