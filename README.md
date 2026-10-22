# SizyLift Backend

Основа сервера SizyLift: Java 25, Spring Boot, Gradle, PostgreSQL и Liquibase.
Обычная слоистая архитектура: `controller → service → repository`, с группировкой по функциональности.
Аутентификация — Keycloak (email/пароль, Google/Facebook), бэкенд проверяет JWT access token.
[Архитектурные решения](docs/architecture.md), [аутентификация](docs/authentication.md),
[публикация данных](docs/publication.md), [синхронизация v2](docs/sync.md), [тренер и подопечный](docs/coaching.md), [push-уведомления](docs/notifications.md), [наблюдаемость](docs/observability.md),
[бэклог](docs/backlog.md).

## Требования

- JDK 25 (`JAVA_HOME` должен указывать на JDK).
- Docker Engine и Docker Compose v2; Docker нужен и интеграционным тестам.
- Gradle устанавливать отдельно не нужно — используйте `./gradlew`.

## Конфигурация

Настройки лежат в YAML и разделены по профилям Spring:

| Файл | Назначение |
| --- | --- |
| `application.yaml` | общее для всех сред: Liquibase, audience токена, телеметрия, actuator |
| `application-dev.yaml` | локальная разработка: адреса и учётные данные контейнеров из `compose.yaml` |
| `application-prod.yaml` | production: все адреса и секреты только из переменных окружения, без значений по умолчанию |

Профиль выбирается через `SPRING_PROFILES_ACTIVE`; `./gradlew bootRun` без него
запускается в `dev`. В production приложение не стартует, если не задана хотя бы
одна обязательная переменная (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
`KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI`, `EXPO_ACCESS_TOKEN`); пишет логи в JSON (ECS), учитывает
`X-Forwarded-*` от прокси, а actuator слушает отдельный порт 8081.
В `application-dev.yaml` и `compose.yaml` хранятся только одноразовые пароли
локальных контейнеров, доступных на `127.0.0.1`; production-секреты в Git не попадают.

## Локальный запуск

Из корня этого репозитория:

```bash
docker compose up -d --wait
./gradlew bootRun
```

`.env` не нужен. Он служит только для необязательных переопределений, например
ключей Google/Facebook (шаблон — `.env.example`).

Compose поднимает PostgreSQL приложения, Keycloak (http://localhost:8180, админка
`admin`/`admin`, отдельная БД, realm `sizylift` импортируется при первом старте) и
Mailpit для писем Keycloak (http://localhost:8025). Приложение слушает порт 8080:

```bash
curl --fail http://localhost:8080/actuator/health   # "status":"UP"
curl -i http://localhost:8080/api/v1/me             # 401 без токена
```

Для остановки: `docker compose down` (тома сохраняются; `--volumes` удаляет данные
и заставляет Keycloak заново импортировать realm).

## Production

Сборка образов, развёртывание на сервере с HTTPS, обновление, резервные копии и
откат описаны в [docs/deployment.md](docs/deployment.md).

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
Исполняемый JAR: `build/libs/sizy-lift-backend-<версия>.jar` (единственный файл в каталоге).
GitHub Actions запускает ту же сборку на Java 25.

Миграции добавляйте новыми SQL changeset-файлами в `src/main/resources/db/changelog`.
Не редактируйте уже применённые changeset-файлы. Версии зависимостей управляются Spring Boot BOM и Gradle lock-файлом.
