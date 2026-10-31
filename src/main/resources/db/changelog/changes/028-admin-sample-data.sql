--liquibase formatted sql

--changeset sizy:028-admin-sample-data
CREATE TABLE sizylift.admin_sample_data (
    user_id uuid PRIMARY KEY REFERENCES sizylift.user_account(id) ON DELETE CASCADE,
    scenario varchar(100) NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    created_by_subject varchar(100) NOT NULL,
    summary jsonb NOT NULL
);
COMMENT ON TABLE sizylift.admin_sample_data IS 'Однократное административное заполнение тестовой историей';

--rollback DROP TABLE sizylift.admin_sample_data;
