--liquibase formatted sql

--changeset sizylift:016-add-invite-revoked-at
-- Тренер отзывает неиспользованное приглашение (docs/coaching.md); отозванное отвечает 410, как истёкшее.
ALTER TABLE sizylift.coaching_invite ADD COLUMN revoked_at timestamptz;

--rollback ALTER TABLE sizylift.coaching_invite DROP COLUMN revoked_at;

--changeset sizylift:016-keep-revoke-after-owner-deletion
-- Удаление аккаунта (docs/authentication.md) сначала разрывает связи: тренер получает revoke по данным
-- подопечного. Строки revoke ссылаются на удалённого владельца и должны дожить до чтения ленты тренером,
-- поэтому каскад по owner_user_id убран; лента получателя по-прежнему удаляется вместе с ним (user_id).
ALTER TABLE sizylift.sync_feed DROP CONSTRAINT sync_feed_owner_user_id_fkey;

--rollback ALTER TABLE sizylift.sync_feed ADD CONSTRAINT sync_feed_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES sizylift.user_account (id) ON DELETE CASCADE;
