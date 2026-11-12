package com.sizylift.backend.video.config;

import com.sizylift.backend.video.service.VideoStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Клиент хранилища видео. Соединение устанавливается при первом обращении, запуск от хранилища не зависит. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VideoProperties.class)
class VideoConfiguration {

    @Bean
    VideoStorage videoStorage(VideoProperties properties) {
        var storage = properties.storage();
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
        var region = Region.of(storage.region());
        // Адресация путём: имя бакета не становится поддоменом хранилища.
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        var client = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .endpointOverride(storage.endpoint())
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        var presigner = S3Presigner.builder()
                .endpointOverride(storage.publicEndpoint())
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        return new S3VideoStorage(client, presigner, storage.bucket());
    }
}
