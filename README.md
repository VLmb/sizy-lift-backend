# SizyLift Backend

Основа сервера SizyLift: Java 25, Spring Boot, Gradle, PostgreSQL и Liquibase.
Обычная слоистая архитектура: `controller → service → repository`, с группировкой по функциональности.
Аутентификация — Keycloak (email/пароль, Google/Facebook), бэкенд проверяет JWT access token.
[Архитектурные решения](docs/architecture.md), [аутентификация](docs/authentication.md).

## Требования

- JDK 25 (`JAVA_HOME` должен указывать на JDK).
- Docker Engine и Docker Compose v2; Docker нужен и интеграционным тестам.
- Gradle устанавливать отдельно не нужно — используйте `./gradlew`.

## Локальный запуск

Из корня этого репозитория:

```bash
cp .env.example .env
# Задайте в .env DB_PASSWORD, KEYCLOAK_DB_PASSWORD и KEYCLOAK_ADMIN_PASSWORD, затем:
docker compose up -d --wait
set -a
. ./.env
set +a
./gradlew bootRun
```

Приложение слушает порт 8080. Проверка готовности:

```bash
curl --fail http://localhost:8080/actuator/health
# Ответ содержит "status":"UP".
```

Compose поднимает PostgreSQL приложения, Keycloak (http://localhost:8180, отдельная БД,
realm `sizylift` импортируется при первом старте) и Mailpit для писем Keycloak
(http://localhost:8025). Все API, кроме health, требуют Bearer-токен Keycloak:
`curl http://localhost:8080/api/v1/me` без токена вернёт `401`.

При старте Liquibase создаёт схему `sizylift`. Учётные данные берутся из
`DB_USERNAME` и `DB_PASSWORD`, адрес БД — из `DB_URL`. Файл `.env` читает Docker
Compose; для Java его нужно экспортировать, как показано выше. Значения в `.env`
должны быть совместимы с shell; специальные символы заключайте в одинарные кавычки.
Не добавляйте реальные пароли в Git. Для остановки БД: `docker compose down`
(именованный том сохраняется; не добавляйте `--volumes`, если данные нужны).

## Проверка и сборка

```bash
./gradlew build
```

JUnit/Testcontainers запускает отдельный PostgreSQL и Keycloak с realm из репозитория,
проверяет миграции Liquibase, health endpoint, создание аккаунта и проверку настоящих
токенов Keycloak (чужой issuer, ID token и изменённый токен отклоняются). Служебные таблицы
Liquibase закреплены в `public`, независимо от PostgreSQL `search_path`.
Тесты не используют локальную рабочую БД и
не пропускаются автоматически при отсутствии Docker. Отчёт: `build/reports/tests/test/index.html`.
Исполняемый JAR: `build/libs/sizy-lift-backend-0.1.0-SNAPSHOT.jar`.
GitHub Actions запускает ту же сборку на Java 25.

Миграции добавляйте новыми SQL changeset-файлами в `src/main/resources/db/changelog`.
Не редактируйте уже применённые changeset-файлы и не включайте автоматическое
создание схемы Hibernate. Версии зависимостей управляются Spring Boot BOM и Gradle lock-файлом.
