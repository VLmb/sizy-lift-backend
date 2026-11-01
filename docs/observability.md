# Наблюдаемость

Бэкенд отправляет телеметрию по OpenTelemetry (OTLP/HTTP) в коллектор:

| Сигнал | Источник | Куда |
| --- | --- | --- |
| Метрики | Micrometer → `OtlpMeterRegistry`, каждые 30 с | Prometheus |
| Трейсы | Micrometer Tracing → OpenTelemetry SDK (bridge) | Tempo |
| Логи | Logback → `OpenTelemetryAppender` (`logback-spring.xml`) | Loki |

Зависимости: `spring-boot-starter-opentelemetry` (Micrometer OTLP registry, tracing bridge, OTLP
exporter) и `opentelemetry-logback-appender-1.0` (версия собрана на том же OTel SDK, что в BOM
Spring Boot). Ресурсные атрибуты: `service.name=sizy-lift-backend`, `service.namespace=sizylift`,
`deployment.environment.name=dev|prod`. В консоль логи пишутся как раньше (в prod — ECS JSON),
OTLP-экспорт идёт параллельно. В логах и трейсах один `trace_id`: из строки лога в Grafana можно
перейти к трейсу запроса.

## Что измеряется

- Стандартное: HTTP-запросы (`http.server.requests`, с гистограммой для перцентилей), JVM
  (память, GC, потоки), HikariCP, Spring Security, Tomcat.
- `sizylift.accounts.created` — аккаунты, созданные при первом запросе.
- `sizylift.publication.items{type, status, error}` — результат по каждому агрегату
  (`accepted`/`stale`/`rejected`, код ошибки).
- `sizylift.publication.batch.size` — агрегатов в пачке.
- `sizylift.publication.batch` — наблюдение (таймер + span `publication batch`) на обработку пачки.
- `sizylift.sync.push.items{type, status}` — результат записи v2 по каждому агрегату
  (`accepted`/`superseded`/`stale`/`rejected`).
- `sizylift.sync.conflicts{type, outcome}` — конфликты записи: `accepted` (входящая правка выиграла),
  `superseded` (проиграла); считаются и для проигрышей публикации v1.
- `sizylift.sync.push.base.ahead{type}` — клиент прислал `baseRevision` новее серверной ревизии
  (восстановление базы из бэкапа или ошибка клиента); такая правка решается как конфликт.
- `sizylift.sync.push` — наблюдение (таймер + span `sync push`) на обработку пачки v2.
- `sizylift.sync.changes.page.size` — записей ленты на странице `GET /api/v2/sync/changes`.
- `sizylift.notifications.enqueued{kind, coalesced}`, `sizylift.notifications.sent{kind, outcome}`,
  `sizylift.notifications.receipts{outcome}`, `sizylift.notifications.queue.size`,
  `sizylift.notifications.queue.oldest.age` — push-уведомления ([notifications.md](notifications.md)).
- `sizylift.coaching.events{event}` — приглашения и связи: `invite_created`, `link_created`,
  `link_ended`, `health_granted`, `health_revoked`.
- `sizylift.coach.inbox.daily.events{kind}` — события «Входящих», созданные ежедневной задачей
  (`workout_missed`, `program_ending`); `sizylift.coach.inbox.daily.run{outcome}` — таймер запуска
  (`completed`, `skipped` — работает другой экземпляр, `failed`) ([coach-workspace.md](coach-workspace.md)).

SQL-запросы отдельными span'ами пока не трассируются.

## Конфигурация

| Профиль | Куда | Свойства |
| --- | --- | --- |
| `dev` | `http://localhost:4318` (`otel-lgtm` из `compose.yaml`) | `application-dev.yaml` |
| `prod` | `${OTLP_ENDPOINT}` без значения по умолчанию (`http://otel-lgtm:4318` в `deploy/compose.prod.yaml`) | `application-prod.yaml` |
| `test` | экспорт выключен, метрики пишутся в память | `src/test/resources/application-test.yaml` |

Доля трассируемых запросов: 100% (`TRACING_SAMPLING_PROBABILITY` в prod).

## Как посмотреть

**Локально.** `docker compose up -d --wait` поднимает `grafana/otel-lgtm` (OTel Collector,
Prometheus, Tempo, Loki, Grafana в одном контейнере). Grafana: http://localhost:3000 — анонимный
вход, только на 127.0.0.1. Explore → Prometheus / Tempo / Loki.

**Production.** Тот же контейнер в `deploy/compose.prod.yaml`, Grafana без анонимного доступа
(`admin` / `GRAFANA_ADMIN_PASSWORD`), не опубликована наружу:

```bash
ssh -L 3000:127.0.0.1:3000 <сервер>   # затем http://localhost:3000
```

Данные хранятся в томе `otel-lgtm-data`. Production-политика хранения описана ниже.

## Хранение в production

Политика — **24 часа для метрик, логов и трассировок**, независимо от числа пользователей.
Используются нативные механизмы хранилищ закреплённого образа `grafana/otel-lgtm:0.39.0`
с digest из Compose. Никаких cron-задач с удалением файлов работающих баз.
Локальный `compose.yaml` остаётся отдельной конфигурацией разработки.

| Хранилище | Настройка | Как освобождается место |
| --- | --- | --- |
| Prometheus | `PROMETHEUS_EXTRA_ARGS=--storage.tsdb.retention.time=24h` | Удаление старых TSDB-блоков; WAL и текущая head-область живут отдельно |
| Loki | `deploy/telemetry/loki.yaml`: `retention_period: 24h`, `retention_enabled: true` | Compactor обрабатывает индексы каждые 10 минут; sweeper удаляет chunks с задержкой 2 часа |
| Tempo 3.1 | `TEMPO_EXTRA_ARGS`: `backend-scheduler.provider.work.compaction.block-retention=24h` и `backend-worker.compaction.block-retention=24h` | Backend scheduler/worker выполняют retention блоков; проверяются оба эффективных значения |
| Pyroscope 2.4.2 | `PYROSCOPE_EXTRA_ARGS=-retention-period=24h` | Политика для профилей, если они поступают в встроенный компонент образа |

Loki сохраняет прежние TSDB v13, начало схемы, период индекса 24h и пути chunks.
Каталог `/data/loki/compactor` находится в существующем постоянном томе: маркеры
ожидающих удаления chunks не теряются при пересоздании контейнера.
Настроен `delete_request_store: filesystem`. `max_query_lookback: 24h` ограничивает
запросы; записи старше суток отклоняются на приёме, чтобы повторный экспорт не
заполнял хранилище просроченной историей. Один только lookback не удаляет данные —
удаление обеспечивает compactor.

24 часа — срок хранения, **не точный момент физического удаления и не квота диска**.
Удаление происходит целыми блоками/chunks по расписанию, с задержками compaction,
WAL и очереди удаления. На диске временно остаётся больше суток данных. После простоя
очистка продолжится при запуске. При повышении нагрузки даже суточная история может
занять много места; нужно следить за свободным SSD в `/admin` и за ростом тома.
Размер самого образа и Docker build cache этой политикой не уменьшается.

### Отдельные stdout/stderr контейнеров

Для **всех** production-сервисов задан `json-file`, `max-size: 10m`, `max-file: 3`.
Это около 30 МиБ на контейнер плюс служебные накладные расходы (9 сервисов — около
270 МиБ); ротация происходит по размеру. Логи остаются совместимы с `docker logs`
и просмотром в админке. У Docker здесь нет обещания хранения ровно 24 часов:
при высокой частоте записей или пересоздании контейнера история будет короче.
Логи приложения в Loki независимо следуют суточной политике.

Вывод каждого компонента LGTM включён (`ENABLE_LOGS_ALL=true`) и попадает под ту же
ротацию: ошибки compactor не скрываются в `/dev/null`. Healthcheck проверяет все
шесть компонентов и считает закрытый порт ошибкой. На каждый ответ даётся до 5 секунд,
на всю проверку Docker — 40 секунд. Проверяются все адреса; имя компонента, URL,
код curl и тело ответа об ошибке сохраняются в Docker health log. Штатный healthcheck образа
пропускает закрытые порты. Изменённая проверка отражает сбой в Docker и админке,
но `restart: unless-stopped` сам по себе **не перезапускает unhealthy** контейнер.
Для внешнего оповещения и автоматического восстановления требуется отдельная
система контроля; этот PR её не вводит. Остановка даёт компонентам до 30 секунд
на корректное завершение, Docker — 45 секунд до принудительного завершения.

### Проверка

`./gradlew build` запускает `TelemetryRetentionIntegrationTest`: прежний и новый закреплённые
образы на одном тестовом томе с environment и bind-конфигурациями из развёрнутой production Compose-модели.
Проверяются эффективные настройки всех хранилищ, ротация каждого сервиса, доставка
OTLP метрик/логов/трассировок и их чтение через Prometheus/Loki/Tempo, отказ Loki
при приёме записи старше 24 часов и строгий healthcheck. После обновления проверяется чтение прежней истории. Проверка не ждёт сутки
и не заявляет измеренный срок физического освобождения места.

После развёртывания, из корня backend:

```bash
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml ps otel-lgtm
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml exec -T otel-lgtm \
  curl -fsS http://127.0.0.1:9090/api/v1/status/flags
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml exec -T otel-lgtm \
  curl -fsS http://127.0.0.1:3100/config
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml exec -T otel-lgtm \
  curl -fsS http://127.0.0.1:3200/status/config
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml logs --since=10m --tail=200 otel-lgtm
```

В Prometheus проверьте `storage.tsdb.retention.time=1d` (нормализованные 24 часа), в Loki —
`retention_period`, `retention_enabled`, `working_directory`, в Tempo — оба
`block_retention`. Повторите замер `docker system df -v` через сутки и после
очередных циклов очистки; ищите в логах ошибки retention/compactor и нехватку места.
Проверку приёма данных можно сделать запросами приложения и просмотром свежей
телеметрии в админке/Grafana. Ни том, ни его отдельные файлы вручную не удаляются.

## Восстановление Pyroscope после зацикливания Raft

Признаки: `non-monotonic log entries: tried to append index N after N`, повторяющиеся
выборы лидера, `node is not the leader`, `/ready` Pyroscope (порт 4040) возвращает
`Metastore not ready`. Это ошибка журнала метаданных профилей; такие строки не
доказывают повреждение PostgreSQL, метрик Prometheus или логов Loki.
Таймаут всей проверки сам по себе не определяет неисправный компонент.

В прежнем LGTM 0.36.0 используется Pyroscope 2.3.1. Upstream исправил повторную
запись Raft-индекса после позднего завершения дисковой операции в
[PR #5307](https://github.com/grafana/pyroscope/pull/5307); исправление входит в
[Pyroscope 2.4](https://grafana.com/docs/pyroscope/latest/release-notes/v2-4/).
В production и dev LGTM обновлён до 0.39.0 с Pyroscope 2.4.2 и закреплён по digest. Пути данных
метаданных/журнала и schema Loki сохранены. Наличие ошибки не устанавливает причину
задержки диска на конкретном сервере — нагрузку SSD, память и место проверяют отдельно.

Временно можно корректно перезапустить только стек мониторинга:

```bash
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml restart -t 45 otel-lgtm
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml ps otel-lgtm
docker inspect sizylift-otel-lgtm-1 --format '{{json .State.Health}}'
```

Том сохраняется; приём телеметрии прерывается на время запуска. Перезапуск старой
версии не устраняет сам дефект и не гарантирует восстановления любого состояния WAL.
Постоянное исправление — обновление образа по [инструкции](deployment.md#обновление-lgtm-с-исправлением-raft).
Не удаляйте `/data/pyroscope/metastore/raft` или общий том: это постоянные данные.
Если ошибка сохраняется на исправленной версии, остановите цикл повторных попыток,
сохраните логи и остановленную копию тома для отдельного разбора/восстановления.
Возврат к прежней версии требует сохранённой до обновления копии; совместимость
нового формата данных с прежним бинарным файлом не обещается.

Интеграционная проверка создаёт историю в старом образе, затем запускает новый
на том же Docker volume и читает метрики, логи и трассировки до новой отправки.
Это проверка обновления существующих данных, а не воспроизведение всех дисковых
сбоев production и не гарантия восстановления уже повреждённого WAL.

## Дальше

Развитие наблюдаемости (дашборды, вход через Keycloak, метрики инфраструктуры, алерты)
ведётся в [бэклоге](backlog.md#наблюдаемость-развитие-observabilitymd).

## Админка сайта

Основные графики SSD/RAM/CPU, HTTP и отклонений синхронизации доступны в `/admin`
через защищённый backend API. `node-exporter` добавляет метрики хоста; Prometheus
использует `deploy/prometheus.yaml`. Состояние и ограниченные stdout/stderr контейнеров
читает изолированный `admin-runtime`. Подробности и настройка — [administration.md](administration.md).
