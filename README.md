# SizyLift Backend

Основа сервера SizyLift: Java 25, Spring Boot, Gradle, PostgreSQL и Liquibase.
Обычная слоистая архитектура: `controller → service → repository`, с группировкой по функциональности.
Keycloak выбран для email/пароля и входа через Google/Facebook; аутентификация
будет реализована следующим этапом. [Архитектурные решения](docs/architecture.md).

## Требования

- JDK 25 (`JAVA_HOME` должен указывать на JDK).
- Docker Engine и Docker Compose v2; Docker нужен и интеграционным тестам.
- Gradle устанавливать отдельно не нужно — используйте `./gradlew`.

## Локальный запуск

Из корня этого репозитория:

```bash
cp .env.example .env
# Задайте в .env свой локальный DB_PASSWORD, затем:
docker compose up -d --wait postgres
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

JUnit/Testcontainers запускает отдельный PostgreSQL, проверяет настоящую миграцию
Liquibase, повторное применение миграций и HTTP health endpoint. Служебные таблицы
Liquibase закреплены в `public`, независимо от PostgreSQL `search_path`.
Тесты не используют локальную рабочую БД и
не пропускаются автоматически при отсутствии Docker. Отчёт: `build/reports/tests/test/index.html`.
Исполняемый JAR: `build/libs/sizy-lift-backend-0.1.0-SNAPSHOT.jar`.
GitHub Actions запускает ту же сборку на Java 25.

Миграции добавляйте новыми SQL changeset-файлами в `src/main/resources/db/changelog`.
Не редактируйте уже применённые changeset-файлы и не включайте автоматическое
создание схемы Hibernate. Версии зависимостей управляются Spring Boot BOM и Gradle lock-файлом.
