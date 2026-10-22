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
- `sizylift.import.requests{source, outcome}`, `sizylift.import.tokens{direction}`,
  `sizylift.import.duration`, `sizylift.import.rounds` — ИИ-импорт ([imports.md](imports.md)).
- `sizylift.coaching.events{event}` — приглашения и связи: `invite_created`, `link_created`,
  `link_ended`, `health_granted`, `health_revoked`.

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

Данные хранятся в томе `otel-lgtm-data`.

## Дальше

Развитие наблюдаемости (дашборды, вход через Keycloak, метрики инфраструктуры, алерты)
ведётся в [бэклоге](backlog.md#наблюдаемость-развитие-observabilitymd).
