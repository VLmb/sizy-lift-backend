--liquibase formatted sql

--changeset sizylift:041-coaching-invite-name
-- Имя, которое тренер дал приглашению: видно в списке ожидающих в любой сессии, а не только в браузере.
ALTER TABLE sizylift.coaching_invite
    ADD COLUMN name text CHECK (name IS NULL OR char_length(name) BETWEEN 1 AND 200);
--rollback ALTER TABLE sizylift.coaching_invite DROP COLUMN name;
