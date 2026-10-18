# Развёртывание в production

Один сервер с Docker: всё описано в [`deploy/compose.prod.yaml`](../deploy/compose.prod.yaml).

```text
интернет ──443──▶ Caddy ──▶ api.<домен>  → backend:8080      (Spring Boot, профиль prod)
                        └─▶ auth.<домен> → keycloak:8080     (start --optimized, /admin закрыт)
backend ──▶ postgres (данные SizyLift)
backend ──▶ keycloak:8080 (JWKS по внутренней сети)
keycloak ──▶ keycloak-postgres (данные Keycloak), SMTP
```

- **Caddy** сам получает и продлевает сертификаты Let's Encrypt, перенаправляет HTTP на HTTPS.
- **Бэкенд**: образ из корневого `Dockerfile`, работает не от root, actuator слушает только
  внутренний порт 8081 (Caddy его не проксирует), логи в JSON.
- **Keycloak**: образ из `deploy/keycloak/Dockerfile` с заранее выполненной сборкой
  (`kc.sh build`), поэтому стартует с `--optimized`. Realm импортируется при первом
  старте с `sslRequired: all` и настоящим SMTP. Админка снаружи отдаёт 404.
- Наружу открыты только 80 и 443. Базы данных в интернет не смотрят.

Минимальный сервер: 2 vCPU, 4 ГБ RAM (приложение ~1,3 ГБ, телеметрия `otel-lgtm` ещё ~1–1,5 ГБ),
Docker Engine и Compose v2.

Телеметрия (метрики, трейсы, логи) уходит в контейнер `otel-lgtm`; Grafana открывается через
SSH-туннель `ssh -L 3000:127.0.0.1:3000 <сервер>`, вход `admin` / `GRAFANA_ADMIN_PASSWORD`.
Подробности — [observability.md](observability.md).

## Первое развёртывание

1. DNS: записи A/AAAA для `api.<домен>` и `auth.<домен>` указывают на сервер.
2. На сервере:
   ```bash
   git clone <репозиторий> sizylift && cd sizylift
   cp deploy/.env.example deploy/.env && chmod 600 deploy/.env
   # заполните deploy/.env; пароли: openssl rand -base64 32
   ```
3. Сборка и запуск (JDK 25 на сервере или собранный в CI jar):
   ```bash
   ./gradlew bootJar
   docker compose --env-file deploy/.env -f deploy/compose.prod.yaml up -d --build --wait
   ```
4. Проверка:
   ```bash
   curl -i https://api.<домен>/api/v1/me      # 401
   curl https://auth.<домен>/realms/sizylift/.well-known/openid-configuration
   ```
5. Админка Keycloak — через SSH-туннель:
   ```bash
   ssh -L 8180:127.0.0.1:8180 <сервер>
   # http://localhost:8180/admin, вход под KEYCLOAK_ADMIN_USERNAME
   ```
   Создайте в realm `master` личного администратора, затем удалите временного
   `bootstrap-admin`.
6. Google/Facebook: в консолях провайдеров укажите redirect URI
   `https://auth.<домен>/realms/sizylift/broker/{google|facebook}/endpoint`.

## Обновление

```bash
git pull
./gradlew bootJar
BACKEND_VERSION=$(git rev-parse --short HEAD) \
  docker compose --env-file deploy/.env -f deploy/compose.prod.yaml up -d --build --wait
```

Liquibase применяет новые changeset-файлы при старте бэкенда. Образы помечаются
коммитом, поэтому откат — запуск с прежним `BACKEND_VERSION`, если миграции
обратно совместимы.

Изменения `keycloak/realm-sizylift.json` **не применяются** к уже созданному realm:
их нужно повторить в админке.

## Резервные копии

```bash
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml exec -T postgres pg_dump -U sizylift -Fc sizylift > sizylift.dump
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml exec -T keycloak-postgres pg_dump -U keycloak -Fc keycloak > keycloak.dump
```

Обе базы нужны вместе: аккаунты SizyLift ссылаются на пользователей Keycloak через `sub`.
Копии храните вне сервера.

## Локальная проверка production-стека

Стек можно поднять на своей машине с доменами `api.localhost` и `auth.localhost`:
для них Caddy выпускает сертификаты своим локальным CA, без Let's Encrypt.
В `deploy/.env` укажите эти домены и SMTP Mailpit (добавив его отдельным compose-файлом)
и не забудьте `curl -k`. Так проверялись образы и полный сценарий регистрации.
