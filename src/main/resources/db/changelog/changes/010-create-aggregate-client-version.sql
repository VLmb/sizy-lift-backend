--liquibase formatted sql

--changeset sizylift:010-create-aggregate-client-version
-- Последняя обработанная правка каждого клиента по агрегату: повтор распознаётся, даже если после него
-- агрегат записал другой клиент (ADR-0007, раздел 3).
CREATE TABLE sizylift.aggregate_client_version (
    user_id      uuid   NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    type         text   NOT NULL,
    aggregate_id uuid   NOT NULL,
    client_id    text   NOT NULL,
    version      bigint NOT NULL CHECK (version >= 1),
    PRIMARY KEY (user_id, type, aggregate_id, client_id)
);

--rollback DROP TABLE sizylift.aggregate_client_version;
