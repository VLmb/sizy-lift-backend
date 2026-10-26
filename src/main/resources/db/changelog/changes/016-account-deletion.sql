--liquibase formatted sql

--changeset sizylift:016-account-deletion
-- Очередь живёт после удаления аккаунта; адрес сервера и секреты в ней не хранятся.
CREATE TABLE sizylift.account_deletion (
    id uuid PRIMARY KEY,
    issuer text NOT NULL,
    subject text NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sizylift.retired_identity (
    fingerprint text PRIMARY KEY
);

--rollback DROP TABLE sizylift.retired_identity;
--rollback DROP TABLE sizylift.account_deletion;
