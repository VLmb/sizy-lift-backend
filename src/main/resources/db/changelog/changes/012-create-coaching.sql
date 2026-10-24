--liquibase formatted sql

--changeset sizylift:012-create-coaching
-- Тренер и подопечный (ADR-0007, раздел 5).
ALTER TABLE sizylift.user_account ADD COLUMN coach_enabled boolean NOT NULL DEFAULT false;

-- Одноразовое приглашение; хранится только SHA-256 токена, утечка базы не даёт рабочих ссылок.
CREATE TABLE sizylift.coaching_invite (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_user_id uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    token_hash    bytea       NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    accepted_at   timestamptz,
    accepted_by   uuid        REFERENCES sizylift.user_account (id) ON DELETE SET NULL
);

CREATE INDEX coaching_invite_coach_idx ON sizylift.coaching_invite (coach_user_id);

CREATE TABLE sizylift.coaching_link (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    coach_user_id   uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    athlete_user_id uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    health_access   boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    ended_at        timestamptz,
    CHECK (coach_user_id <> athlete_user_id)
);

-- У подопечного один активный тренер.
CREATE UNIQUE INDEX coaching_link_active_athlete ON sizylift.coaching_link (athlete_user_id) WHERE ended_at IS NULL;
-- Список подопечных и фан-аут тренеру.
CREATE INDEX coaching_link_active_coach ON sizylift.coaching_link (coach_user_id) WHERE ended_at IS NULL;

--rollback DROP TABLE sizylift.coaching_link;
--rollback DROP TABLE sizylift.coaching_invite;
--rollback ALTER TABLE sizylift.user_account DROP COLUMN coach_enabled;
