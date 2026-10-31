--liquibase formatted sql

--changeset sizylift:027-standalone-workout-sync
-- ADR-0011: разовая тренировка — агрегат sync v2 standalone_workout. Ревизия, автор и tombstone теперь в
-- published_aggregate, как у остальных типов; своя ревизия таблицы и пометка удаления больше не нужны.
-- original_date остаётся: веб показывает «перенесена», когда владелец сдвинул дату разовой.
ALTER TABLE sizylift.published_aggregate DROP CONSTRAINT published_aggregate_type_check;
ALTER TABLE sizylift.published_aggregate ADD CONSTRAINT published_aggregate_type_check
    CHECK (type IN ('workout', 'program', 'exercise', 'athlete_profile', 'one_rep_max', 'injury',
                    'contraindication', 'workout_reschedule', 'workout_comment', 'standalone_workout'));

-- Перенос разовой тренировки тренера подопечным: программы нет.
ALTER TABLE sizylift.workout_reschedule ALTER COLUMN program_id DROP NOT NULL;

-- Уже созданные через веб разовые: агрегаты владельца с новой ревизией. Удалённые исчезают совсем —
-- в ленты они не попадали, удалять на клиентах нечего.
DELETE FROM sizylift.standalone_workout WHERE deleted;

INSERT INTO sizylift.published_aggregate (user_id, type, aggregate_id, version, deleted, received_at, revision,
                                          changed_at)
SELECT user_id, 'standalone_workout', id, 0, false, updated_at, nextval('sizylift.aggregate_revision_seq'),
       updated_at
FROM sizylift.standalone_workout
ON CONFLICT (user_id, type, aggregate_id) DO NOTHING;

ALTER TABLE sizylift.standalone_workout DROP CONSTRAINT standalone_workout_check;
DROP INDEX sizylift.standalone_workout_date_idx;
ALTER TABLE sizylift.standalone_workout DROP COLUMN revision, DROP COLUMN deleted;
ALTER TABLE sizylift.standalone_workout ALTER COLUMN payload SET NOT NULL;
CREATE INDEX standalone_workout_date_idx ON sizylift.standalone_workout (user_id, workout_date);

--rollback DROP INDEX sizylift.standalone_workout_date_idx;
--rollback ALTER TABLE sizylift.standalone_workout ADD COLUMN revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0), ADD COLUMN deleted boolean NOT NULL DEFAULT false;
--rollback ALTER TABLE sizylift.standalone_workout ALTER COLUMN payload DROP NOT NULL;
--rollback ALTER TABLE sizylift.standalone_workout ADD CONSTRAINT standalone_workout_check CHECK (deleted OR payload IS NOT NULL);
--rollback CREATE INDEX standalone_workout_date_idx ON sizylift.standalone_workout (user_id, workout_date) WHERE NOT deleted;
--rollback DELETE FROM sizylift.workout_reschedule WHERE program_id IS NULL;
--rollback ALTER TABLE sizylift.workout_reschedule ALTER COLUMN program_id SET NOT NULL;
--rollback DELETE FROM sizylift.published_aggregate WHERE type = 'standalone_workout';
--rollback ALTER TABLE sizylift.published_aggregate DROP CONSTRAINT published_aggregate_type_check;
--rollback ALTER TABLE sizylift.published_aggregate ADD CONSTRAINT published_aggregate_type_check CHECK (type IN ('workout', 'program', 'exercise', 'athlete_profile', 'one_rep_max', 'injury', 'contraindication', 'workout_reschedule', 'workout_comment'));

--changeset sizylift:027-seed-standalone-feeds
-- В ленты владельцев и их активных тренеров, позицией после последней: первое чтение после обновления
-- приложения вернёт разовые.
INSERT INTO sizylift.sync_cursor (user_id, last_seq)
SELECT DISTINCT r.user_id, 0
FROM (SELECT user_id FROM sizylift.standalone_workout
      UNION SELECT l.coach_user_id FROM sizylift.standalone_workout s
            JOIN sizylift.coaching_link l ON l.athlete_user_id = s.user_id AND l.ended_at IS NULL) r
ON CONFLICT (user_id) DO NOTHING;

WITH recipients AS (
    SELECT s.user_id AS recipient, s.user_id AS owner, s.id, pa.revision
    FROM sizylift.standalone_workout s
    JOIN sizylift.published_aggregate pa
        ON pa.user_id = s.user_id AND pa.type = 'standalone_workout' AND pa.aggregate_id = s.id
    UNION ALL
    SELECT l.coach_user_id, s.user_id, s.id, pa.revision
    FROM sizylift.standalone_workout s
    JOIN sizylift.coaching_link l ON l.athlete_user_id = s.user_id AND l.ended_at IS NULL
    JOIN sizylift.published_aggregate pa
        ON pa.user_id = s.user_id AND pa.type = 'standalone_workout' AND pa.aggregate_id = s.id
), numbered AS (
    SELECT r.*, c.last_seq + row_number() OVER (PARTITION BY r.recipient ORDER BY r.revision) AS seq
    FROM recipients r JOIN sizylift.sync_cursor c ON c.user_id = r.recipient
)
INSERT INTO sizylift.sync_feed (user_id, seq, owner_user_id, type, aggregate_id, revision, kind)
SELECT recipient, seq, owner, 'standalone_workout', id, revision, 'upsert' FROM numbered;

UPDATE sizylift.sync_cursor c SET last_seq = f.max_seq
FROM (SELECT user_id, max(seq) AS max_seq FROM sizylift.sync_feed GROUP BY user_id) f
WHERE f.user_id = c.user_id AND f.max_seq > c.last_seq;

--rollback DELETE FROM sizylift.sync_feed WHERE type = 'standalone_workout';
