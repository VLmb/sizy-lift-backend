-- liquibase formatted sql
-- changeset sizylift:048-product-feedback
CREATE TABLE sizylift.product_feedback (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    account_id     uuid        REFERENCES sizylift.user_account(id) ON DELETE SET NULL,
    email          text,
    username       text,
    name           text,
    source         text        NOT NULL CHECK (source IN ('WEB', 'APP', 'PWA')),
    message        text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    mail_status    text        NOT NULL DEFAULT 'PENDING' CHECK (mail_status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX product_feedback_account_idx ON sizylift.product_feedback (account_id) WHERE account_id IS NOT NULL;

--rollback DROP TABLE sizylift.product_feedback;
