--liquibase formatted sql

--changeset sizylift:002-create-user-account
CREATE TABLE sizylift.user_account (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    issuer         text        NOT NULL,
    subject        text        NOT NULL,
    email          text,
    email_verified boolean     NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT user_account_issuer_subject_key UNIQUE (issuer, subject)
);

COMMENT ON TABLE sizylift.user_account IS 'SizyLift account linked to an external identity (OIDC iss + sub)';

--rollback DROP TABLE sizylift.user_account;
