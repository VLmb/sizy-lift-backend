--liquibase formatted sql

--changeset sizylift:015-web-workflows
ALTER TABLE sizylift.coaching_invite ADD COLUMN revoked_at timestamptz;

CREATE TABLE sizylift.web_program_archive (
    user_id uuid NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    program_id uuid NOT NULL,
    author_user_id uuid REFERENCES sizylift.user_account (id) ON DELETE SET NULL,
    payload jsonb NOT NULL,
    deleted_revision bigint NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, program_id)
);

--rollback DROP TABLE sizylift.web_program_archive;
--rollback ALTER TABLE sizylift.coaching_invite DROP COLUMN revoked_at;
