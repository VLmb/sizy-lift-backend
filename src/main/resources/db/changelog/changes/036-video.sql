--liquibase formatted sql

--changeset sizylift:036-video
-- Переделка тренерского режима, этап 3: комментарий и видео к упражнению (ADR-0012, docs/video.md).
-- Запись о ролике; файлы лежат в S3-совместимом хранилище, ключи производные: video/{владелец}/{id}.mp4 и .jpg.
-- id придумывает приложение: комментарий со ссылкой на видео пишется раньше, чем появляется сеть.
CREATE TABLE sizylift.video (
    id                 uuid PRIMARY KEY,
    owner_user_id      uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    kind               text        NOT NULL CHECK (kind IN ('set', 'technique')),
    status             text        NOT NULL CHECK (status IN ('pending', 'ready')),
    size_bytes         bigint      NOT NULL CHECK (size_bytes > 0),
    preview_size_bytes bigint      NOT NULL CHECK (preview_size_bytes > 0),
    duration_ms        integer     NOT NULL CHECK (duration_ms > 0),
    title              text,
    exercise_id        uuid,
    exercise_name      text,
    created_at         timestamptz NOT NULL,
    uploaded_at        timestamptz,
    CHECK ((status = 'ready') = (uploaded_at IS NOT NULL))
);

CREATE INDEX video_owner_idx ON sizylift.video (owner_user_id, kind, created_at DESC, id);
CREATE INDEX video_pending_idx ON sizylift.video (created_at) WHERE status = 'pending';

-- Объекты удалённых роликов: строку пишет транзакция удаления (ролика, аккаунта, незавершённой загрузки),
-- файлы из хранилища убирает фоновая задача. Без FK: аккаунта владельца уже может не быть.
CREATE TABLE sizylift.video_removal (
    video_id      uuid PRIMARY KEY,
    owner_user_id uuid        NOT NULL,
    queued_at     timestamptz NOT NULL
);

-- Комментарий к упражнению тренировки: упражнение, необязательно номер подхода и видео. Без FK, как остальные
-- ссылки агрегатов: комментарий приходит раньше тренировки и раньше загрузки ролика.
ALTER TABLE sizylift.workout_comment
    ADD COLUMN workout_exercise_id uuid,
    ADD COLUMN set_number          integer CHECK (set_number >= 1),
    ADD COLUMN video_id            uuid,
    ADD COLUMN video_duration_ms   integer CHECK (video_duration_ms > 0),
    ADD CONSTRAINT workout_comment_exercise_check
        CHECK (workout_exercise_id IS NOT NULL OR set_number IS NULL AND video_id IS NULL),
    ADD CONSTRAINT workout_comment_video_duration_check
        CHECK (video_id IS NOT NULL OR video_duration_ms IS NULL);

CREATE INDEX workout_comment_video_idx ON sizylift.workout_comment (video_id) WHERE video_id IS NOT NULL;

-- Видео техники тренера у упражнения программы.
ALTER TABLE sizylift.planned_exercise ADD COLUMN technique_video_id uuid;

CREATE INDEX planned_exercise_video_idx ON sizylift.planned_exercise (technique_video_id)
    WHERE technique_video_id IS NOT NULL;

--rollback DROP INDEX sizylift.planned_exercise_video_idx;
--rollback ALTER TABLE sizylift.planned_exercise DROP COLUMN technique_video_id;
--rollback DROP INDEX sizylift.workout_comment_video_idx;
--rollback ALTER TABLE sizylift.workout_comment DROP CONSTRAINT workout_comment_video_duration_check, DROP CONSTRAINT workout_comment_exercise_check, DROP COLUMN video_duration_ms, DROP COLUMN video_id, DROP COLUMN set_number, DROP COLUMN workout_exercise_id;
--rollback DROP TABLE sizylift.video_removal;
--rollback DROP TABLE sizylift.video;
