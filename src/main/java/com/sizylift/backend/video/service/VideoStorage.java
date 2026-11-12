package com.sizylift.backend.video.service;

import java.net.URI;
import java.time.Duration;
import java.util.OptionalLong;

/** Хранилище файлов видео: только подписанные ссылки и служебные операции, содержимое через сервер не идёт. */
public interface VideoStorage {

    /** Ссылка PUT; в подпись входят тип содержимого и точный размер. */
    URI uploadUrl(String key, String contentType, long sizeBytes, Duration ttl);

    URI viewUrl(String key, Duration ttl);

    /** Размер объекта; пусто — объекта нет. */
    OptionalLong size(String key);

    void delete(String key);
}
