# Синхронизация v2

Контракт и поведение записи `POST /api/v2/sync/push`. Решения и обоснования — в
[ADR-0007](adr/0007-two-way-sync-and-coaching.md); публикация v1 осталась и описана в
[publication.md](publication.md).

Статус: реализована запись (этап 1). Лента изменений (`GET /api/v2/sync/changes`), права тренера,
переносы и комментарии — следующие этапы, см. ADR-0007.

## Запись: `POST /api/v2/sync/push`

До 100 элементов в пачке, как в v1. Каждый элемент обрабатывается в своей транзакции; ошибка одного
не влияет на остальные.

```json
{ "items": [ { "type": "program", "id": "…", "ownerId": null,
               "baseRevision": 41, "changedAt": "2026-10-23T09:14:02Z",
               "clientId": "device-7f3a", "clientVersion": 18,
               "schemaVersion": 1, "deleted": false, "payload": { … } } ] }
```

| Поле | Смысл |
| --- | --- |
| `type`, `id`, `schemaVersion`, `deleted`, `payload` | как в v1: те же типы агрегатов и те же payload v1 |
| `ownerId` | только при записи чужих данных; сейчас допустим только свой id, иначе `rejected: forbidden` |
| `baseRevision` | ревизия, от которой клиент правил; `0` — новый агрегат |
| `changedAt` | время последней локальной правки по часам клиента |
| `clientId`, `clientVersion` | устройство или веб-сессия и её счётчик правок агрегата; делает повтор идемпотентным |

Ответ — по элементу: `{type, id, clientVersion, status, revision, error}`.

| `status` | Смысл |
| --- | --- |
| `accepted` | правка записана, `revision` — новая ревизия агрегата |
| `superseded` | правка проиграла более поздней и записана только в журнал конфликтов; `revision` — текущая серверная, клиент перечитает агрегат |
| `stale` | повтор уже применённой правки (тот же `clientId`, `clientVersion` не больше сохранённого); `revision` — текущая |
| `rejected` | не принят, `error`: `unsupported_type`, `unsupported_schema`, `invalid_payload`, `id_conflict`, `forbidden` |

### Порядок обработки элемента

1. Проверки: тип, версия схемы, `ownerId`, payload (Bean Validation).
2. Блокировка строки `published_aggregate` (`INSERT … ON CONFLICT DO NOTHING` + `SELECT … FOR UPDATE`).
3. Повтор того же клиента → `stale`.
4. `changedAt` обрезается до `min(changedAt, время сервера)`, с точностью до микросекунды.
5. `baseRevision` не меньше текущей ревизии — запись без конфликта.
6. Иначе конфликт: побеждает более поздний `changedAt`, при равенстве — сохранённая версия.
   Выигравшая входящая правка → `accepted`, сохранённая версия уходит в журнал.
   Проигравшая → `superseded`, она уходит в журнал.
7. Запись данных (замена агрегата целиком), новая ревизия из `aggregate_revision_seq`, `changed_at`,
   `source_client_id`, `source_version`, `author_user_id = NULL` (пишет владелец).

Удаление — запись с `deleted = true` по тем же правилам: tombstone с ревизией, конкурирует с правкой
по `changedAt`.

### Журнал конфликтов

`aggregate_conflict(user_id, type, aggregate_id, lost_payload, lost_changed_at, lost_client_id,
winner_revision, created_at)`, хранится 30 дней (`ConflictRetention`, ежедневно ночью). Данные пишутся
только сервером; в API журнала пока нет.

`lost_payload` заполнен у проигравшей **входящей** правки. У вытесненной **сохранённой** версии и у
tombstone он пуст: собирать payload из таблиц умеет `AggregateReader`, который появится вместе с
лентой изменений (этап 2); тогда же вытесненные версии начнут записываться с содержимым.

## Совместимость с публикацией v1

`POST /api/v1/publication/batch` работает как раньше: версия агрегата — счётчик телефона, не новее
сохранённой → `stale`. Дополнительно, если агрегат последним записан через sync v2, побеждает правка
с более поздним `updatedAt` корня payload (у tombstone — время получения). Проигравшая v1-запись
получает `stale` (в v1 нет `superseded`) и попадает в журнал; выигравшая вытесняет версию v2, тоже
с записью в журнал. Любая запись v1 тоже получает серверную ревизию и `changed_at`.

## Что должен соблюдать клиент

- Сохранять `revision` из ответа и отправлять её как `baseRevision` при следующей правке.
- Ставить `clientVersion` из собственного возрастающего счётчика правок агрегата.
- На `superseded` перечитать агрегат (из ленты, этап 2) и показать пользователю, что правка заменена.

## Метрики

- `sizylift.sync.push.items{type,status}` — итог по элементу;
- `sizylift.sync.conflicts{type,outcome}` — конфликты: `accepted` (входящая выиграла), `superseded`
  (входящая проиграла); считаются и для проигрышей v1;
- `sizylift.sync.push` — наблюдение (таймер и span `sync push`) на пачку.

Подробнее — [observability.md](observability.md).
