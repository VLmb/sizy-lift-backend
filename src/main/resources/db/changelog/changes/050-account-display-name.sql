--liquibase formatted sql

--changeset sizylift:050-account-display-name
ALTER TABLE sizylift.user_account ADD COLUMN display_name text;
--rollback ALTER TABLE sizylift.user_account DROP COLUMN display_name;
