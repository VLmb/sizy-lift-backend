package com.sizylift.backend.video.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

import com.sizylift.backend.video.service.VideoStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Хранилище с S3 API (Garage, позже внешнее): смена — адрес и ключи в настройках. */
class S3VideoStorage implements VideoStorage {

    private static final List<String> BROWSER_METHODS = List.of("PUT", "GET", "HEAD");
    private static final String ANY_HEADER = "*";
    private static final List<String> EXPOSED_HEADERS = List.of("ETag", "Content-Length", "Content-Range");
    private static final int PREFLIGHT_CACHE_SECONDS = 3600;
    private static final Duration PHOTO_UPLOAD_TIMEOUT = Duration.ofSeconds(45);

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
    public byte[] read(String key, long offset, int length) {
        var range = "bytes=" + offset + "-" + (offset + length - 1);
        return client.getObjectAsBytes(get -> get.bucket(bucket).key(key).range(range)).asByteArray();
    }

    @Override
    public void delete(String key) {
        client.deleteObject(delete -> delete.bucket(bucket).key(key));
    }

    @Override
    public void download(String key, Path target) {
        client.getObject(get -> get.bucket(bucket).key(key), ResponseTransformer.toFile(target));
    }

    @Override
    public void upload(String key, String contentType, Path source) {
        client.putObject(put -> put.bucket(bucket).key(key).contentType(contentType), RequestBody.fromFile(source));
    }

    @Override
    public void uploadPhoto(String key, String contentType, byte[] bytes) {
        client.putObject(put -> put.bucket(bucket).key(key).contentType(contentType).cacheControl("no-store")
                        .overrideConfiguration(config -> config.apiCallTimeout(PHOTO_UPLOAD_TIMEOUT)),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public void allowBrowsers(List<String> origins) {
        var rule = CORSRule.builder().allowedOrigins(origins).allowedMethods(BROWSER_METHODS)
                .allowedHeaders(ANY_HEADER).exposeHeaders(EXPOSED_HEADERS).maxAgeSeconds(PREFLIGHT_CACHE_SECONDS)
                .build();
        client.putBucketCors(cors -> cors.bucket(bucket).corsConfiguration(config -> config.corsRules(rule)));
    }
}
