--liquibase formatted sql

--changeset sizylift:001-create-application-schema
CREATE SCHEMA sizylift;

--rollback DROP SCHEMA sizylift;
