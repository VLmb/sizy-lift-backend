--liquibase formatted sql

--changeset sizylift:048-exercise-video
-- Видео техники у упражнения (docs/video.md, «Видео упражнения»): тренер прикрепляет одно видео к упражнению,
-- и его видят все действующие подопечные там, где встречается это упражнение. Видео упражнения конкретной
-- программы (planned_exercise.technique_video_id) остаётся уточнением и перекрывает это.
-- coach_owned — упражнение из библиотеки тренера: у подопечного оно лежит копией под производным id.
CREATE TABLE sizylift.exercise_video (
    coach_user_id uuid        NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    exercise_id   uuid        NOT NULL,
    video_id      uuid        NOT NULL REFERENCES sizylift.video (id) ON DELETE CASCADE,
    exercise_name text,
    coach_owned   boolean     NOT NULL,
    attached_at   timestamptz NOT NULL,
    PRIMARY KEY (coach_user_id, exercise_id)
);

CREATE INDEX exercise_video_video_idx ON sizylift.exercise_video (video_id);

--rollback DROP TABLE sizylift.exercise_video;
