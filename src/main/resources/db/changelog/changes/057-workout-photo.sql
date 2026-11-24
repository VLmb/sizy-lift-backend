--liquibase formatted sql

--changeset sizylift:057-workout-photo
CREATE TABLE sizylift.workout_photo (
    user_id uuid NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    workout_id uuid NOT NULL,
    version uuid NOT NULL,
    object_key text UNIQUE,
    width integer CHECK (width > 0),
    height integer CHECK (height > 0),
    byte_size integer CHECK (byte_size > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, workout_id),
    -- Публикация заменяет тренировку удалением и вставкой в одной транзакции.
    FOREIGN KEY (user_id, workout_id) REFERENCES sizylift.workout (user_id, id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (num_nonnulls(object_key, width, height, byte_size) IN (0, 4))
);

CREATE TABLE sizylift.workout_photo_operation (
    user_id uuid NOT NULL,
    workout_id uuid NOT NULL,
    version uuid NOT NULL,
    PRIMARY KEY (user_id, workout_id, version),
    FOREIGN KEY (user_id, workout_id) REFERENCES sizylift.workout_photo (user_id, workout_id) ON DELETE CASCADE
);

CREATE TABLE sizylift.workout_photo_removal (
    object_key text PRIMARY KEY,
    due_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX workout_photo_removal_due_idx ON sizylift.workout_photo_removal (due_at);

--rollback DROP TABLE sizylift.workout_photo_removal;
--rollback DROP TABLE sizylift.workout_photo_operation;
--rollback DROP TABLE sizylift.workout_photo;

--changeset sizylift:057-queue-workout-photo-removal splitStatements:false
CREATE FUNCTION sizylift.queue_workout_photo_removal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.object_key IS NOT NULL AND (TG_OP = 'DELETE' OR OLD.object_key IS DISTINCT FROM NEW.object_key) THEN
        INSERT INTO sizylift.workout_photo_removal (object_key) VALUES (OLD.object_key) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER workout_photo_removal AFTER UPDATE OR DELETE ON sizylift.workout_photo
    FOR EACH ROW EXECUTE FUNCTION sizylift.queue_workout_photo_removal();
--rollback DROP TRIGGER workout_photo_removal ON sizylift.workout_photo;
--rollback DROP FUNCTION sizylift.queue_workout_photo_removal();
