--liquibase formatted sql

--changeset sizylift:008-add-aggregate-revisions
-- Серверная ревизия и метаданные последней записи агрегата (ADR-0007, разделы 2–3).
-- Ревизия берётся из общей последовательности: нужна только монотонность внутри агрегата.
CREATE SEQUENCE sizylift.aggregate_revision_seq;

ALTER TABLE sizylift.published_aggregate
    ADD COLUMN revision         bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD COLUMN changed_at       timestamptz,
    ADD COLUMN author_user_id   uuid,
    ADD COLUMN source_client_id text,
    ADD COLUMN source_version   bigint;

--rollback ALTER TABLE sizylift.published_aggregate DROP COLUMN source_version, DROP COLUMN source_client_id, DROP COLUMN author_user_id, DROP COLUMN changed_at, DROP COLUMN revision;
--rollback DROP SEQUENCE sizylift.aggregate_revision_seq;

--changeset sizylift:008-backfill-aggregate-revisions
-- Уже опубликованным агрегатам: ревизия из последовательности, время правки из updated_at корня
-- (для tombstone и пустых значений — время получения), автор — владелец (NULL).
UPDATE sizylift.published_aggregate pa
SET revision   = nextval('sizylift.aggregate_revision_seq'),
    changed_at = COALESCE(
        CASE pa.type
            WHEN 'workout' THEN (SELECT w.updated_at FROM sizylift.workout w
                                 WHERE w.user_id = pa.user_id AND w.id = pa.aggregate_id)
            WHEN 'program' THEN (SELECT p.updated_at FROM sizylift.program p
                                 WHERE p.user_id = pa.user_id AND p.id = pa.aggregate_id)
            WHEN 'exercise' THEN (SELECT e.updated_at FROM sizylift.exercise e
                                  WHERE e.user_id = pa.user_id AND e.id = pa.aggregate_id)
            WHEN 'athlete_profile' THEN (SELECT a.updated_at FROM sizylift.athlete_profile a
                                         WHERE a.user_id = pa.user_id)
            WHEN 'one_rep_max' THEN (SELECT o.updated_at FROM sizylift.one_rep_max o
                                     WHERE o.user_id = pa.user_id AND o.exercise_id = pa.aggregate_id)
            WHEN 'injury' THEN (SELECT i.updated_at FROM sizylift.injury i
                                WHERE i.user_id = pa.user_id AND i.id = pa.aggregate_id)
            WHEN 'contraindication' THEN (SELECT c.updated_at FROM sizylift.contraindication c
                                          WHERE c.user_id = pa.user_id AND c.id = pa.aggregate_id)
        END,
        pa.received_at)
WHERE pa.revision = 0;

--rollback UPDATE sizylift.published_aggregate SET revision = 0, changed_at = NULL;
