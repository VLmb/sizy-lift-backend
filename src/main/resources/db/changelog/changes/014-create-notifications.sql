--liquibase formatted sql

--changeset sizylift:014-create-notifications
-- Push-уведомления (ADR-0009): устройства и транзакционный outbox.
CREATE TABLE sizylift.device (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    platform     text        NOT NULL CHECK (platform IN ('ios', 'android')),
    push_token   text,
    last_seen_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX device_user_idx ON sizylift.device (user_id) WHERE push_token IS NOT NULL;

-- data хранит только тип и id, без данных пользователя. dedup_key склеивает неотправленные уведомления
-- одного вида по одному агрегату. Строка без sent_at и gave_up_at ещё в очереди.
CREATE TABLE sizylift.notification_outbox (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    kind                 text        NOT NULL,
    data                 jsonb       NOT NULL,
    dedup_key            text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    attempts             integer     NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz NOT NULL DEFAULT now(),
    sent_at              timestamptz,
    gave_up_at           timestamptz,
    ticket_ids           jsonb,
    receipts_checked_at  timestamptz
);

CREATE INDEX notification_outbox_due_idx ON sizylift.notification_outbox (next_attempt_at)
    WHERE sent_at IS NULL AND gave_up_at IS NULL;
CREATE UNIQUE INDEX notification_outbox_pending_key ON sizylift.notification_outbox (user_id, dedup_key)
    WHERE sent_at IS NULL AND gave_up_at IS NULL;
CREATE INDEX notification_outbox_receipts_idx ON sizylift.notification_outbox (sent_at)
    WHERE receipts_checked_at IS NULL AND ticket_ids IS NOT NULL;

--rollback DROP TABLE sizylift.notification_outbox;
--rollback DROP TABLE sizylift.device;
