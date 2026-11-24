package com.sizylift.backend.video.service;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

/** Закрытое хранилище файлов видео и фото тренировок. */
public interface VideoStorage {

    /** Ссылка PUT; в подпись входят тип содержимого и точный размер. */
    URI uploadUrl(String key, String contentType, long sizeBytes, Duration ttl);

    URI viewUrl(String key, Duration ttl);

    /** Размер объекта; пусто — объекта нет. */
    OptionalLong size(String key);

    /** Часть объекта: {@code length} байт начиная с {@code offset}; для проверки содержимого, не для выдачи. */
    byte[] read(String key, long offset, int length);

    void delete(String key);

    /** Скачивает объект в файл: исходник из браузера для перекодирования. */
    void download(String key, Path target);

    /** Кладёт файл, сделанный сервером: перекодированный ролик и превью. */
    void upload(String key, String contentType, Path source);

    /** Сохраняет фото по ключу, выделенному сервером. */
    void uploadPhoto(String key, String contentType, byte[] bytes);

    /** Разрешает браузерам с этих адресов класть файлы по подписанным ссылкам (CORS бакета). */
    void allowBrowsers(List<String> origins);
}
