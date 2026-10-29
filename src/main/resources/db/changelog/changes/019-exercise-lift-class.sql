--liquibase formatted sql

--changeset sizylift:019-exercise-lift-class
-- Класс упражнения вместо is_sbd (docs/coach-workspace.md, раздел 1) и %ПМ в плановом подходе (раздел 2).
ALTER TABLE sizylift.exercise ADD COLUMN lift_class text NOT NULL DEFAULT 'other';

-- SBD и «Базовое» — только у «вес × повторения»; is_sbd у другого типа результата переносится как other.
UPDATE sizylift.exercise SET lift_class = 'sbd' WHERE is_sbd AND result_type = 'weight_reps';

-- Системный каталог: id из 004-seed-exercise-catalog и 006-seed-exercise-catalog-2026-10-2.
UPDATE sizylift.exercise SET lift_class = 'sbd'
WHERE user_id IS NULL AND id IN (
    '14000000-0000-4000-8000-000000000001', -- Присед со штангой
    '14000000-0000-4000-8000-000000000002', -- Жим штанги лёжа
    '14000000-0000-4000-8000-000000000003', -- Становая тяга классическая
    '14000000-0000-4000-8000-000000000039'  -- Становая тяга сумо
);

UPDATE sizylift.exercise SET lift_class = 'basic'
WHERE user_id IS NULL AND id IN (
    '14000000-0000-4000-8000-000000000040', -- Фронтальный присед
    '14000000-0000-4000-8000-000000000043', -- Гоблет-присед
    '14000000-0000-4000-8000-000000000011', -- Болгарские выпады
    '14000000-0000-4000-8000-000000000044', -- Выпады с гантелями
    '14000000-0000-4000-8000-000000000063', -- Жим лёжа узким хватом
    '14000000-0000-4000-8000-000000000022', -- Жим штанги на наклонной скамье
    '14000000-0000-4000-8000-000000000021', -- Жим гантелей лёжа
    '14000000-0000-4000-8000-000000000023', -- Жим гантелей на наклонной скамье
    '14000000-0000-4000-8000-000000000051', -- Армейский жим
    '14000000-0000-4000-8000-000000000052', -- Жим гантелей сидя
    '14000000-0000-4000-8000-000000000008', -- Жим гантелей стоя
    '14000000-0000-4000-8000-000000000012', -- Румынская тяга
    '14000000-0000-4000-8000-000000000015', -- Тяга штанги в наклоне
    '14000000-0000-4000-8000-000000000016', -- Т-тяга
    '14000000-0000-4000-8000-000000000031', -- Тяга гантели в наклоне
    '14000000-0000-4000-8000-000000000047'  -- Ягодичный мост со штангой
);

ALTER TABLE sizylift.exercise
    ADD CONSTRAINT exercise_lift_class_check CHECK (lift_class IN ('sbd', 'basic', 'other')),
    ADD CONSTRAINT exercise_lift_class_result_type_check CHECK (lift_class = 'other' OR result_type = 'weight_reps'),
    DROP COLUMN is_sbd;

ALTER TABLE sizylift.planned_set
    ADD COLUMN target_percent_one_rm_tenths integer CHECK (target_percent_one_rm_tenths BETWEEN 1 AND 1500);

--rollback ALTER TABLE sizylift.planned_set DROP COLUMN target_percent_one_rm_tenths;
--rollback ALTER TABLE sizylift.exercise ADD COLUMN is_sbd boolean NOT NULL DEFAULT false;
--rollback UPDATE sizylift.exercise SET is_sbd = (lift_class = 'sbd');
--rollback ALTER TABLE sizylift.exercise DROP COLUMN lift_class;
