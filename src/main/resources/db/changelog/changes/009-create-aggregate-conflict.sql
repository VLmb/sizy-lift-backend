--liquibase formatted sql

--changeset sizylift:009-create-aggregate-conflict
-- Проигравшие при last-writer-wins версии агрегатов (ADR-0007, раздел 3); хранятся 30 дней.
-- lost_payload пуст у tombstone и у вытесненной сохранённой версии, пока нет AggregateReader.
CREATE TABLE sizylift.aggregate_conflict (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    type            text        NOT NULL,
    aggregate_id    uuid        NOT NULL,
    lost_payload    jsonb,
    lost_changed_at timestamptz,
    lost_client_id  text,
    winner_revision bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX aggregate_conflict_aggregate_idx ON sizylift.aggregate_conflict (user_id, type, aggregate_id);
CREATE INDEX aggregate_conflict_created_idx ON sizylift.aggregate_conflict (created_at);

--rollback DROP TABLE sizylift.aggregate_conflict;
