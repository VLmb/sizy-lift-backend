plugins { java }

group = "com.sizylift"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

tasks.withType<JavaCompile> { options.release = 21 }
tasks.jar { archiveFileName = "sizy-keycloak-provider.jar" }

repositories { mavenCentral() }

dependencies {
    compileOnly("org.keycloak:keycloak-services:26.8.1")
}

dependencyLocking { lockAllConfigurations() }
