--liquibase formatted sql

--changeset sizylift:038-reps-and-drops
-- Тип результата «только повторы» (reps) и дропсет как ступени под подходом (docs/publication.md).
-- Приложение делает то же самое миграцией SQLite 29 и ревизией каталога 2026.11.1.
ALTER TABLE sizylift.exercise DROP CONSTRAINT exercise_result_type_check,
    ADD CONSTRAINT exercise_result_type_check
        CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'reps', 'duration', 'distance_time', 'free'));
ALTER TABLE sizylift.workout_exercise DROP CONSTRAINT workout_exercise_result_type_check,
    ADD CONSTRAINT workout_exercise_result_type_check
        CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'reps', 'duration', 'distance_time', 'free'));
ALTER TABLE sizylift.workout_set DROP CONSTRAINT workout_set_result_type_check,
    ADD CONSTRAINT workout_set_result_type_check
        CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'reps', 'duration', 'distance_time', 'free'));
ALTER TABLE sizylift.workout_plan_set_snapshot DROP CONSTRAINT workout_plan_set_snapshot_result_type_check,
    ADD CONSTRAINT workout_plan_set_snapshot_result_type_check
        CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'reps', 'duration', 'distance_time', 'free'));
ALTER TABLE sizylift.planned_exercise DROP CONSTRAINT planned_exercise_result_type_check,
    ADD CONSTRAINT planned_exercise_result_type_check
        CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'reps', 'duration', 'distance_time', 'free'));
ALTER TABLE sizylift.planned_set DROP CONSTRAINT planned_set_result_type_check,
    ADD CONSTRAINT planned_set_result_type_check
        CHECK (result_type IN ('weight_reps', 'added_weight_reps', 'reps', 'duration', 'distance_time', 'free'));

-- Ступени дропсета: [{"weightGrams": 60000, "repetitions": 8}, …]; NULL — подход без дропсета.
ALTER TABLE sizylift.workout_set ADD COLUMN drops jsonb
    CONSTRAINT workout_set_drops_check CHECK (drops IS NULL OR jsonb_typeof(drops) = 'array');

-- Подъём ног в висе, скручивания и отжимания от пола — только повторы.
CREATE TEMP TABLE reps_only_exercise (id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO reps_only_exercise (id) VALUES
    ('14000000-0000-4000-8000-000000000066'),
    ('14000000-0000-4000-8000-000000000064'),
    ('14000000-0000-4000-8000-000000000030');
UPDATE sizylift.exercise SET result_type = 'reps'
WHERE user_id IS NULL AND result_type = 'added_weight_reps' AND id IN (SELECT id FROM reps_only_exercise);
INSERT INTO sizylift.exercise_catalog_version (version) VALUES ('2026.11.1');

-- История этих упражнений без веса становится «только повторы»; записи с доп. весом остаются как были.
CREATE TEMP TABLE reps_workout_exercise ON COMMIT DROP AS
SELECT we.user_id, we.id FROM sizylift.workout_exercise we
WHERE we.exercise_id IN (SELECT id FROM reps_only_exercise) AND we.result_type = 'added_weight_reps'
  AND NOT EXISTS (SELECT 1 FROM sizylift.workout_set s
                  WHERE s.user_id = we.user_id AND s.workout_exercise_id = we.id AND coalesce(s.weight_grams, 0) > 0);
UPDATE sizylift.workout_set s SET result_type = 'reps', weight_grams = NULL
FROM reps_workout_exercise r WHERE s.user_id = r.user_id AND s.workout_exercise_id = r.id;
UPDATE sizylift.workout_exercise we SET result_type = 'reps'
FROM reps_workout_exercise r WHERE we.user_id = r.user_id AND we.id = r.id;

CREATE TEMP TABLE reps_planned_exercise ON COMMIT DROP AS
SELECT pe.user_id, pe.id FROM sizylift.planned_exercise pe
WHERE pe.exercise_id IN (SELECT id FROM reps_only_exercise) AND pe.result_type = 'added_weight_reps'
  AND NOT EXISTS (SELECT 1 FROM sizylift.planned_set s
                  WHERE s.user_id = pe.user_id AND s.planned_exercise_id = pe.id
                    AND (coalesce(s.target_weight_grams, 0) > 0 OR s.target_percent_one_rm_tenths IS NOT NULL));
UPDATE sizylift.planned_set s SET result_type = 'reps', target_weight_grams = NULL
FROM reps_planned_exercise r WHERE s.user_id = r.user_id AND s.planned_exercise_id = r.id;
UPDATE sizylift.planned_exercise pe SET result_type = 'reps'
FROM reps_planned_exercise r WHERE pe.user_id = r.user_id AND pe.id = r.id;
UPDATE sizylift.workout_plan_set_snapshot s SET result_type = 'reps', target_weight_grams = NULL
FROM reps_planned_exercise r
WHERE s.user_id = r.user_id AND s.source_planned_exercise_id = r.id AND s.result_type = 'added_weight_reps';

-- Прежний подход типа «дроп» становится ступенью ближайшего подхода выше, который не дроп.
-- Без такого подхода (или у него другой тип результата) — рабочий подход.
CREATE TEMP TABLE legacy_drop ON COMMIT DROP AS
SELECT s.user_id, s.id, s.workout_exercise_id, s.position, s.weight_grams, s.repetitions,
       (SELECT p.id FROM sizylift.workout_set p
        WHERE p.user_id = s.user_id AND p.workout_exercise_id = s.workout_exercise_id
          AND p.set_type <> 'drop' AND p.position < s.position
          AND p.result_type = s.result_type AND p.result_type IN ('weight_reps', 'added_weight_reps')
          AND NOT EXISTS (SELECT 1 FROM sizylift.workout_set x
                          WHERE x.user_id = s.user_id AND x.workout_exercise_id = s.workout_exercise_id
                            AND x.set_type <> 'drop' AND x.position > p.position AND x.position < s.position)
        ORDER BY p.position DESC LIMIT 1) AS parent_id
FROM sizylift.workout_set s
WHERE s.set_type = 'drop';

UPDATE sizylift.workout_set s SET set_type = 'working'
FROM legacy_drop d WHERE s.user_id = d.user_id AND s.id = d.id AND d.parent_id IS NULL;

UPDATE sizylift.workout_set s SET drops = steps.drops
FROM (SELECT user_id, parent_id,
             jsonb_agg(jsonb_build_object('weightGrams', weight_grams, 'repetitions', repetitions)
                       ORDER BY position) AS drops
      FROM legacy_drop
      WHERE parent_id IS NOT NULL AND (weight_grams IS NOT NULL OR repetitions IS NOT NULL)
      GROUP BY user_id, parent_id) steps
WHERE s.user_id = steps.user_id AND s.id = steps.parent_id;

UPDATE sizylift.workout_plan_set_snapshot s SET actual_set_id = NULL
FROM legacy_drop d WHERE s.user_id = d.user_id AND s.actual_set_id = d.id AND d.parent_id IS NOT NULL;
DELETE FROM sizylift.workout_record_event e
USING legacy_drop d WHERE e.user_id = d.user_id AND e.actual_set_id = d.id AND d.parent_id IS NOT NULL;
DELETE FROM sizylift.workout_set s
USING legacy_drop d WHERE s.user_id = d.user_id AND s.id = d.id AND d.parent_id IS NOT NULL;

-- Позиции подходов смыкаются, как в приложении.
UPDATE sizylift.workout_set s SET position = ordered.position
FROM (SELECT user_id, id, (row_number() OVER (PARTITION BY user_id, workout_exercise_id
                                              ORDER BY position, id) - 1)::integer AS position
      FROM sizylift.workout_set
      WHERE (user_id, workout_exercise_id) IN (SELECT user_id, workout_exercise_id FROM legacy_drop
                                               WHERE parent_id IS NOT NULL)) ordered
WHERE s.user_id = ordered.user_id AND s.id = ordered.id AND s.position <> ordered.position;
--rollback ALTER TABLE sizylift.workout_set DROP COLUMN drops; DELETE FROM sizylift.exercise_catalog_version WHERE version = '2026.11.1';
