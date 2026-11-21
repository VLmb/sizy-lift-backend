--liquibase formatted sql

--changeset sizylift:055-planned-exercise-load-plan
-- План нагрузки строки табличного редактора (docs/load-plan.md): JSON клиента, по которому рассчитаны подходы
-- недель — группы подходов, прогрессия, разгрузка. Сервер его не разбирает. Нет плана — NULL.
ALTER TABLE sizylift.planned_exercise ADD COLUMN load_plan text;

--rollback ALTER TABLE sizylift.planned_exercise DROP COLUMN load_plan;
