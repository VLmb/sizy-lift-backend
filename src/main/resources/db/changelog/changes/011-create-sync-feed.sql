--liquibase formatted sql

--changeset sizylift:011-create-sync-feed
-- Персональная лента изменений (ADR-0007, раздел 4). Позиция seq выдаётся под блокировкой строки
-- sync_cursor получателя, поэтому порядок seq совпадает с порядком коммитов. Одна строка на агрегат:
-- новая запись переносит строку на новый seq.
CREATE TABLE sizylift.sync_cursor (
    user_id  uuid   PRIMARY KEY REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    last_seq bigint NOT NULL DEFAULT 0 CHECK (last_seq >= 0)
);

CREATE TABLE sizylift.sync_feed (
    user_id       uuid   NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    seq           bigint NOT NULL CHECK (seq > 0),
    owner_user_id uuid   NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    type          text   NOT NULL,
    aggregate_id  uuid   NOT NULL,
    revision      bigint NOT NULL,
    kind          text   NOT NULL CHECK (kind IN ('upsert', 'delete', 'revoke')),
    PRIMARY KEY (user_id, seq),
    CONSTRAINT sync_feed_aggregate_key UNIQUE (user_id, owner_user_id, type, aggregate_id)
);

CREATE INDEX sync_feed_owner_idx ON sizylift.sync_feed (owner_user_id);

--rollback DROP TABLE sizylift.sync_feed;
--rollback DROP TABLE sizylift.sync_cursor;

--changeset sizylift:011-seed-owner-feeds
-- Засев лент владельцев всеми их агрегатами, чтобы первое чтение с after=0 вернуло всё.
INSERT INTO sizylift.sync_feed (user_id, seq, owner_user_id, type, aggregate_id, revision, kind)
SELECT user_id,
       row_number() OVER (PARTITION BY user_id ORDER BY revision),
       user_id, type, aggregate_id, revision,
       CASE WHEN deleted THEN 'delete' ELSE 'upsert' END
FROM sizylift.published_aggregate
WHERE revision > 0;

INSERT INTO sizylift.sync_cursor (user_id, last_seq)
SELECT user_id, max(seq) FROM sizylift.sync_feed GROUP BY user_id;

--rollback DELETE FROM sizylift.sync_cursor;
--rollback DELETE FROM sizylift.sync_feed;
