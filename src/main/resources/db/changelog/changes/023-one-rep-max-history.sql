--liquibase formatted sql

--changeset sizylift:023-one-rep-max-history
-- История фактического 1ПМ (docs/web.md): агрегат one_rep_max хранит только последнее значение, история
-- пополняется каждой принятой записью (v1 и v2), если значение изменилось. Удаление агрегата или аккаунта
-- удаляет историю.
CREATE TABLE sizylift.one_rep_max_history (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    exercise_id  uuid        NOT NULL,
    weight_grams integer     NOT NULL CHECK (weight_grams > 0),
    achieved_on  date        NOT NULL,
    recorded_at  timestamptz NOT NULL
);

CREATE INDEX one_rep_max_history_idx
    ON sizylift.one_rep_max_history (user_id, exercise_id, recorded_at DESC, id DESC);

-- Текущие значения — первая точка истории; время — последняя запись агрегата на сервере.
INSERT INTO sizylift.one_rep_max_history (user_id, exercise_id, weight_grams, achieved_on, recorded_at)
SELECT o.user_id, o.exercise_id, o.weight_grams, o.achieved_on, coalesce(a.received_at, o.updated_at)
FROM sizylift.one_rep_max o
LEFT JOIN sizylift.published_aggregate a
    ON a.user_id = o.user_id AND a.type = 'one_rep_max' AND a.aggregate_id = o.exercise_id;

--rollback DROP TABLE sizylift.one_rep_max_history;
