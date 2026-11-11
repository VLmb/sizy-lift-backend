--liquibase formatted sql

--changeset sizylift:035-coach-rework-stage2
-- Переделка тренерского режима, этап 2 (docs/coach-workspace.md, раздел 8).
-- CR-02: необязательная программа тренера, которую увидит приглашённый до входа и получит черновиком после принятия.
ALTER TABLE sizylift.coaching_invite ADD COLUMN program_id uuid;

-- CR-18: заметки, старт и целевые упражнения — данные связи; видит и правит только тренер, после разрыва их нет.
-- target_exercise_ids NULL — тренер ещё не выбирал (клиент берёт присед, жим лёжа и становую), пустой массив — выбрал «ничего».
ALTER TABLE sizylift.coaching_link
    ADD COLUMN coach_notes         text,
    ADD COLUMN start_date          date,
    ADD COLUMN start_name          text,
    ADD COLUMN target_exercise_ids uuid[],
    ADD COLUMN card_updated_at     timestamptz;

-- CR-13: программа тренера, из которой сделана копия у подопечного («эта программа у него уже есть»).
ALTER TABLE sizylift.program ADD COLUMN source_program_id uuid;

--rollback ALTER TABLE sizylift.program DROP COLUMN source_program_id;
--rollback ALTER TABLE sizylift.coaching_link DROP COLUMN card_updated_at, DROP COLUMN target_exercise_ids, DROP COLUMN start_name, DROP COLUMN start_date, DROP COLUMN coach_notes;
--rollback ALTER TABLE sizylift.coaching_invite DROP COLUMN program_id;
