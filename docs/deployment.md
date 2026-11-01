# Развёртывание в production

Один сервер с Docker: всё описано в [`deploy/compose.prod.yaml`](../deploy/compose.prod.yaml).

```text
интернет ──443──▶ Caddy ──▶ <домен>      → web:80            (веб-версия, статика)
                        ├─▶ api.<домен>  → backend:8080      (Spring Boot, профиль prod)
                        └─▶ auth.<домен> → keycloak:8080     (start --optimized, /admin закрыт)
backend ──▶ postgres (данные Sizy)
backend ──▶ keycloak:8080 (JWKS по внутренней сети)
backend ──▶ exp.host (Expo Push, HTTPS)
keycloak ──▶ keycloak-postgres (данные Keycloak), SMTP
```

- **Caddy** сам получает и продлевает сертификаты Let's Encrypt, перенаправляет HTTP на HTTPS.
- **Веб**: образ собирается из клона `sizy-lift-frontend` (путь — `WEB_SOURCE`), адреса API и
  Keycloak зашиваются при сборке. `www.<домен>` перенаправляется на `<домен>`. Бэкенд разрешает CORS
  с `https://<домен>`, клиент Keycloak `sizylift-web` принимает возврат на него.
- **Бэкенд**: образ из корневого `Dockerfile`, работает не от root, actuator слушает только
  внутренний порт 8081 (Caddy его не проксирует), логи в JSON.
- **Keycloak**: образ из `deploy/keycloak/Dockerfile` с заранее выполненной сборкой
  (`kc.sh build`), поэтому стартует с `--optimized`. Realm импортируется при первом
  старте с `sslRequired: all` и настоящим SMTP. Админка снаружи отдаёт 404.
- Наружу открыты только 80 и 443. Базы данных в интернет не смотрят.
- **Push-уведомления** идут через Expo (`https://exp.host`) с токеном `EXPO_ACCESS_TOKEN` из
  `deploy/.env` (Expo → Access tokens, включена «enhanced push security»). Без него приложение не
  стартует. Подробности — [notifications.md](notifications.md).

Минимальный сервер: 2 vCPU, 4 ГБ RAM (приложение ~1,3 ГБ, телеметрия `otel-lgtm` ещё ~1–1,5 ГБ),
Docker Engine и Compose v2.

Телеметрия (метрики, трейсы, логи) уходит в контейнер `otel-lgtm`; Grafana открывается через
SSH-туннель `ssh -L 3000:127.0.0.1:3000 <сервер>`, вход `admin` / `GRAFANA_ADMIN_PASSWORD`.
Подробности — [observability.md](observability.md).

## Первое развёртывание

1. DNS: записи A/AAAA для `<домен>`, `www.<домен>`, `api.<домен>` и `auth.<домен>` указывают на сервер.
2. На сервере:
   ```bash
   git clone <репозиторий бэкенда> sizylift && cd sizylift
   git clone <репозиторий веба> /opt/sizy-lift-frontend   # путь — WEB_SOURCE в deploy/.env
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
   curl -I https://<домен>/                    # 200
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
git pull && git -C /opt/sizy-lift-frontend pull
./gradlew bootJar
BACKEND_VERSION=$(git rev-parse --short HEAD) WEB_VERSION=$(git -C /opt/sizy-lift-frontend rev-parse --short HEAD) \
  docker compose --env-file deploy/.env -f deploy/compose.prod.yaml up -d --build --wait
```

Liquibase применяет новые changeset-файлы при старте бэкенда. Образы помечаются
коммитом, поэтому откат — запуск с прежним `BACKEND_VERSION`, если миграции
обратно совместимы.

Изменения `keycloak/realm-sizylift.json` **не применяются** к уже созданному realm:
их нужно повторить в админке.

Для темы Sizy после обновления образа выберите в существующем realm
**Realm settings → Themes → Login theme → sizy**. В новом realm это уже задано
импортом. `bootJar` также собирает `keycloak-provider` для образа авторизации.
Согласие выключено по умолчанию; порядок отдельного включения после оформления
документов — [keycloak-theme.md](keycloak-theme.md).

## Резервные копии

```bash
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml exec -T postgres pg_dump -U sizylift -Fc sizylift > sizylift.dump
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml exec -T keycloak-postgres pg_dump -U keycloak -Fc keycloak > keycloak.dump
```

Обе базы нужны вместе: аккаунты Sizy ссылаются на пользователей Keycloak через `sub`.
Копии храните вне сервера.

## Локальная проверка production-стека

Стек можно поднять на своей машине с доменами `api.localhost` и `auth.localhost`:
для них Caddy выпускает сертификаты своим локальным CA, без Let's Encrypt.
В `deploy/.env` укажите эти домены и SMTP Mailpit (добавив его отдельным compose-файлом)
и не забудьте `curl -k`. Так проверялись образы и полный сценарий регистрации.

## Удаление аккаунтов

До включения функции в существующем realm через админку Keycloak создайте confidential-клиент
`sizylift-account-deletion`: Client authentication включено, Service accounts включено, браузерные
потоки и Direct access grants выключены. Его service account назначьте client role
`realm-management/manage-users`. Секрет хранится только на сервере. Этот клиент не выдаёт токены вебу.
Существующие клиент sizylift-web, PKCE, темы и юридические согласия не меняются.

В `deploy/.env` добавьте:

```dotenv
ACCOUNT_DELETION_ENABLED=true
KEYCLOAK_DELETION_CLIENT_ID=sizylift-account-deletion
KEYCLOAK_DELETION_CLIENT_SECRET=<секрет из Keycloak>
```

Если функция пока выключена, новые переменные можно не добавлять в существующий `deploy/.env`:
Compose передаст `ACCOUNT_DELETION_ENABLED=false`, client id и секрет — пустыми. Явно пустое значение
флага тоже означает false. Остальные обязательные секреты по-прежнему проверяются при запуске.
Для включения заполните все три значения, как выше. Адрес Keycloak для backend берётся из Compose (`http://keycloak:8080`), наружный issuer
остаётся прежним. Для запуска вне Compose дополнительно задайте KEYCLOAK_ADMIN_URL в prod-профиле.
В dev настройка передаётся через SIZYLIFT_ACCOUNT_DELETION_ENABLED, SIZYLIFT_ACCOUNT_DELETION_ADMIN_URL,
SIZYLIFT_ACCOUNT_DELETION_CLIENT_ID и SIZYLIFT_ACCOUNT_DELETION_CLIENT_SECRET.

Порядок обновления для оставшихся экранов веба: сначала PR backend, затем PR frontend.
После получения изменений и настройки переменных выполните обычное обновление:

```bash
./gradlew build
./gradlew bootJar
BACKEND_VERSION=$(git rev-parse --short HEAD) WEB_VERSION=$(git -C /opt/sizy-lift-frontend rev-parse --short HEAD) \
  docker compose --env-file deploy/.env -f deploy/compose.prod.yaml up -d --build --wait
```

Liquibase применит 015–017: снимки архива, очередь удаления/отпечатки удалённых идентичностей,
отметки просмотра подопечных и отзыв приглашений. Пересоздавать БД и realm не нужно.
Контролируйте метрику deletion.pending: длительный остаток означает проблему доступа к Keycloak.
Перед откатом на версию без обработчика удаления дождитесь пустой очереди.
Эти команды — инструкция обновления; при разработке продакшен не обновлялся.

## Восстановление после перезапуска из-за пустого флага удаления

В версии до исправления пустой `ACCOUNT_DELETION_ENABLED` вызывал `Invalid boolean value []`
и цикл перезапусков backend. Предупреждения Java об Unsafe/SLF4J не являются причиной этого сбоя.
Временно передайте значение прямо в команду (оно имеет приоритет над env-файлом):

```bash
ACCOUNT_DELETION_ENABLED=false docker compose --env-file deploy/.env -f deploy/compose.prod.yaml up -d --force-recreate --wait backend
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml ps backend
```

Это использует уже собранный образ и выключает только удаление аккаунтов. Можно также добавить
`ACCOUNT_DELETION_ENABLED=false` в `deploy/.env`, чтобы последующие запуски старого Compose-файла
сохраняли значение. Предупреждения о пустом client id/секрете в старом файле безвредны при выключенной
функции; новый Compose больше их не выдаёт.
После получения исправления достаточно пересоздать backend с обновлённым Compose. Пересборка jar
для этой правки, изменение БД и переимпорт Keycloak не нужны. Проверка ProductionComposeStartupTest
разворачивает настоящий Compose с прежним env-файлом и загружает его значения в Spring prod context.

## Отдельные планы тренировок в вебе

Обновление с миграцией `018-standalone-workouts`: сначала backend, затем frontend.
Таблица добавляется без изменения существующих программ; Liquibase применяет её при запуске.
Новых переменных окружения, настройки Keycloak и ручного SQL не требуется.

```bash
git pull --ff-only
./gradlew bootJar
BACKEND_VERSION=$(git rev-parse --short HEAD) \
  docker compose --env-file deploy/.env -f deploy/compose.prod.yaml up -d --build --wait backend
```

После успешного healthcheck backend обновите frontend обычной командой выше. Эти команды
приведены для оператора; production при разработке не обновлялся. Ограничение мобильной
синхронизации нового ресурса описано в [web.md](web.md).

## Административная панель

Веб-раздел `/admin`: просмотр пользователей, метрик и логов контейнеров без туннеля.
Нужны отдельный клиент Keycloak с обязательным OTP, личная роль `sizy-admin` и
сервисный клиент только для чтения каталога. Новые переменные и порядок настройки
существующего realm описаны в [administration.md](administration.md#настройка-существующего-production).
Импорт realm при перезапуске не обновляет уже созданные клиенты.

## Переход на суточное хранение телеметрии

Параметры хранения, ограничения и проверки —
[observability.md](observability.md#хранение-в-production).
Новые секреты или переменные в существующем `deploy/.env` не требуются.
Схемы PostgreSQL и Keycloak не меняются.

После получения изменений можно сначала применить retention только к мониторингу:

```bash
git pull --ff-only
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml \
  up -d --no-deps --wait --wait-timeout 180 otel-lgtm
```

Контейнер мониторинга пересоздастся с прежним `otel-lgtm-data`, новыми настройками
и ротацией stdout/stderr. Приём и чтение телеметрии кратковременно прервутся;
очереди экспортера ограничены, поэтому возможны пропуски в истории. Приложение
и базы этой командой не пересоздаются. Старые данные старше суток будут удаляться
автоматически и **без возможности восстановления**, если отдельно не сохранена
резервная копия телеметрии. Возврат старой конфигурации не вернёт удалённую историю.
Не используйте `down -v` и не очищайте содержимое тома.

Затем выполните обычное обновление backend и frontend: backend/runtime принимают
периоды не длиннее суток, frontend убирает недельный период и показывает ограничение
истории. Ротация остальных контейнеров применяется при их пересоздании с новым
Compose-файлом. Для уже работающих контейнеров одного `restart` недостаточно.
При отдельном применении ротации используйте `up -d --no-build --wait` с теми же
`BACKEND_VERSION` и `WEB_VERSION`, что у текущего выпуска: не подменяйте версии
образов случайным `latest`. Пересоздание меняет доступную историю `docker logs`.
Планируйте обновление с учётом перезапуска backend, Keycloak и баз.

## Обновление LGTM с исправлением Raft

Обновление 0.36.0 → 0.39.0 исправляет известный сбой Pyroscope 2.3.1 при записи
Raft-журнала. Диагностика и ограничения —
[observability.md](observability.md#восстановление-pyroscope-после-зацикливания-raft).
Backend, frontend и Keycloak для этого изменения собирать не нужно.

1. Сохраните текущий вывод healthcheck и логи. Проверьте свободное место (`df -h /`):
   при загрузке нового образа прежний остаётся на диске. Перед обновлением сделайте
   резервную копию **остановленного** тома `otel-lgtm-data` во внешнем хранилище,
   если нужно сохранить возможность отката вместе с данными. Имя фактического тома:
   `docker inspect sizylift-otel-lgtm-1 --format '{{json .Mounts}}'`.
2. Получите изменения и обновите только мониторинг:

   ```bash
   git pull --ff-only
   docker compose --env-file deploy/.env -f deploy/compose.prod.yaml pull otel-lgtm
   docker compose --env-file deploy/.env -f deploy/compose.prod.yaml \
     up -d --no-deps --wait --wait-timeout 300 otel-lgtm
   ```

3. Проверьте `ps otel-lgtm`, Docker health log и свежие данные в Grafana/админке.
   Появление `non-monotonic log entries` или повторяющегося `node is not the leader`
   после запуска означает, что требуется дальнейший разбор; увеличение таймаутов
   не является восстановлением Raft.

Пересоздаётся только контейнер мониторинга. Существующий том используется повторно,
суточный retention и ротация журналов сохраняются. Не выполняйте `down -v`.
Изменение backend/frontend образов и пересборка JAR в этот порядок не входят.
