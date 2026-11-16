--liquibase formatted sql

--changeset sizylift:044-athlete-card-goals
-- Цели в карточке подопечного (веб, макеты minimal-2026-11): своими словами и целевой вес по упражнению.
-- Целевой вес — две параллельные колонки: упражнение и вес в граммах, порядок как у тренера.
ALTER TABLE sizylift.coaching_link
    ADD COLUMN goal_texts             text[],
    ADD COLUMN goal_exercise_ids      uuid[],
    ADD COLUMN goal_weight_grams      integer[];

--rollback ALTER TABLE sizylift.coaching_link DROP COLUMN goal_weight_grams, DROP COLUMN goal_exercise_ids, DROP COLUMN goal_texts;
