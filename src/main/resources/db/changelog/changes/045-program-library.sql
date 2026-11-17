--liquibase formatted sql

--changeset sizylift:045-program-library
-- Библиотека шаблонов тренера на сайте (раздел «Программы»): папки и свой порядок карточек. Только оформление
-- списка — сами программы и их синхронизация не меняются; запись о программе, которой уже нет, клиент не показывает.
CREATE TABLE sizylift.program_library_folder (
    user_id  uuid    NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    name     text    NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    position integer NOT NULL CHECK (position >= 0),
    PRIMARY KEY (user_id, name)
);

CREATE TABLE sizylift.program_library_entry (
    user_id    uuid    NOT NULL REFERENCES sizylift.user_account (id) ON DELETE CASCADE,
    program_id uuid    NOT NULL,
    folder     text    CHECK (folder IS NULL OR char_length(folder) BETWEEN 1 AND 80),
    position   integer NOT NULL CHECK (position >= 0),
    PRIMARY KEY (user_id, program_id)
);

--rollback DROP TABLE sizylift.program_library_entry;
--rollback DROP TABLE sizylift.program_library_folder;
