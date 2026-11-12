package com.sizylift.backend.video.config;

import java.net.URI;
import java.time.Duration;
import java.util.OptionalLong;

import com.sizylift.backend.video.service.VideoStorage;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Хранилище с S3 API (Garage, позже внешнее): смена — адрес и ключи в настройках. */
class S3VideoStorage implements VideoStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    S3VideoStorage(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public URI uploadUrl(String key, String contentType, long sizeBytes, Duration ttl) {
        var presigned = presigner.presignPutObject(request -> request.signatureDuration(ttl)
                .putObjectRequest(put -> put.bucket(bucket).key(key).contentType(contentType)
                        .contentLength(sizeBytes)));
        return URI.create(presigned.url().toString());
    }

    @Override
    public URI viewUrl(String key, Duration ttl) {
        var presigned = presigner.presignGetObject(request -> request.signatureDuration(ttl)
                .getObjectRequest(get -> get.bucket(bucket).key(key)));
        return URI.create(presigned.url().toString());
    }

    @Override
    public OptionalLong size(String key) {
        try {
            return OptionalLong.of(client.headObject(head -> head.bucket(bucket).key(key)).contentLength());
        } catch (NoSuchKeyException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(delete -> delete.bucket(bucket).key(key));
    }
}
