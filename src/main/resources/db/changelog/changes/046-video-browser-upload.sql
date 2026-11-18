--liquibase formatted sql

--changeset sizylift:046-video-browser-upload
-- Загрузка видео с сайта и из PWA (ADR-0012, дополнение 18.11.2026): браузер кладёт исходный файл как есть,
-- сервер перекодирует его в MP4 720p и делает превью. Исходник лежит в video/{владелец}/{id}.source, пока ролик
-- в статусе processing; после перекодирования удаляется.
ALTER TABLE sizylift.video DROP CONSTRAINT video_status_check;
ALTER TABLE sizylift.video
    ADD CONSTRAINT video_status_check CHECK (status IN ('pending', 'processing', 'ready')),
    ADD COLUMN source_content_type   text,
    ADD COLUMN source_size_bytes     bigint CHECK (source_size_bytes > 0),
    ADD COLUMN processing_started_at timestamptz,
    ADD COLUMN processing_attempts   integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT video_source_check CHECK (source_size_bytes IS NULL OR source_content_type IS NOT NULL);

CREATE INDEX video_processing_idx ON sizylift.video (created_at) WHERE status = 'processing';

--rollback DROP INDEX sizylift.video_processing_idx;
--rollback DELETE FROM sizylift.video WHERE status = 'processing';
--rollback ALTER TABLE sizylift.video DROP CONSTRAINT video_source_check, DROP COLUMN processing_attempts, DROP COLUMN processing_started_at, DROP COLUMN source_size_bytes, DROP COLUMN source_content_type, DROP CONSTRAINT video_status_check;
--rollback ALTER TABLE sizylift.video ADD CONSTRAINT video_status_check CHECK (status IN ('pending', 'ready'));
