package com.sizylift.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SizyLiftBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SizyLiftBackendApplication.class, args);
    }
}
