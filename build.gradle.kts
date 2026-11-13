import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "com.sizylift"
version = "0.1.0-SNAPSHOT"
description = "Sizy backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    // Sends Logback events through the OpenTelemetry SDK; built against the same OTel version as the Boot BOM.
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.1-alpha")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.ai:spring-ai-deepseek:2.0.2")
    implementation("org.apache.poi:poi-ooxml:6.0.0")
    implementation("org.apache.commons:commons-csv:1.14.1")
    implementation("com.networknt:json-schema-validator:3.0.8")
    // Хранилище видео (ADR-0012): только S3 API, синхронный клиент без Netty и Apache HttpClient.
    implementation(platform("software.amazon.awssdk:bom:2.46.7"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockAllConfigurations()
}

// Only the executable jar is needed; the Dockerfile copies the single file in build/libs.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named("bootJar") {
    dependsOn(":keycloak-provider:jar")
}

// `./gradlew bootRun` uses the dev profile unless SPRING_PROFILES_ACTIVE says otherwise.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    if (System.getenv("SPRING_PROFILES_ACTIVE").isNullOrBlank()) {
        systemProperty("spring.profiles.active", "dev")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // KeycloakAuthenticationIntegrationTest imports the realm file into a real Keycloak.
    inputs.file("deploy/compose.prod.yaml")
    inputs.file("deploy/prometheus.yaml")
    inputs.dir("deploy/telemetry")
    inputs.dir("deploy/garage")
    inputs.file("keycloak/realm-sizylift.json")
    inputs.dir("keycloak/themes")
    inputs.file("keycloak-provider/build/libs/sizy-keycloak-provider.jar")
    dependsOn(":keycloak-provider:jar")
    maxParallelForks = 1
}

// Ручная проверка разбора голосовой диктовки, без Spring, базы и Docker:
// ./gradlew -q voiceParse --console=plain   (--args=--json — полный ответ, --jsonl — строка JSON на фразу, --norm — нормализованные токены)
tasks.register<JavaExec>("voiceParse") {
    group = "verification"
    description = "Разбор продиктованной тренировки из текста в подходы"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.sizylift.backend.voice.parser.VoiceParserCli"
    standardInput = System.`in`
}
