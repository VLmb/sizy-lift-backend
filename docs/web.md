# Веб-версия: чтение и вход

Контракт `/api/v2/web` — чтение данных для веб-версии (`VLmb/sizy-lift-frontend`). Пишет веб тем же
`POST /api/v2/sync/push`, что и телефон ([sync.md](sync.md)). Экраны веба —
`sizy-lift-frontend/docs/design/web-screens.md`.

Статус: реализовано то, что нужно списку программ и редактору. Календарь, тренировки, неделя подопечного —
следующими задачами.

## Чьи данные

У каждого запроса необязательный `ownerId`:
- без него — данные вызывающего;
- с id подопечного — данные подопечного, если тренер их видит (`AccessPolicy.canRead`, [coaching.md](coaching.md)).

Без права чтения ответ `404`, как для несуществующих данных: посторонний не узнаёт, есть ли данные.

## API

Все запросы требуют токен.

### `GET /api/v2/web/catalog?ownerId=`

Справочники и упражнения для редактора и библиотеки.

```json
{ "categories": [ { "id": "…", "code": "squat", "name": "Присед" } ],
  "equipment": [ … ], "muscles": [ … ],
  "exercises": [ { "id": "…", "ownerId": null, "name": "Присед со штангой", "shortName": "Присед",
                   "movementCategoryId": "…", "resultType": "weight_reps", "isSbd": true, "note": null,
                   "muscles": [ { "muscleGroupId": "…", "role": "primary" } ], "equipmentIds": [ "…" ],
                   "revision": null } ] }
```

Упражнения: системные (`ownerId: null`), свои упражнения вызывающего и, при `ownerId`, подопечного. У своих
`revision` — ревизия агрегата `exercise` для `baseRevision` при правке.

### `GET /api/v2/web/programs?ownerId=`

```json
{ "ownerId": "…",
  "programs": [ { "id": "…", "name": "Силовой блок", "description": "SBD, 4 недели", "startDate": "2026-10-12",
                  "endDate": "2026-10-18", "status": "active", "weeks": 1, "workouts": 1,
                  "updatedAt": "…", "revision": 1017, "authorId": null, "changedAt": "…" } ] }
```

Удалённые программы не попадают. `authorId` — тренер, если программу пишет он. Порядок — по `updatedAt`, новые сначала.

### `GET /api/v2/web/programs/{id}?ownerId=`

```json
{ "ownerId": "…", "id": "…", "revision": 1017, "authorId": null, "changedAt": "…", "program": { … payload program v1 … } }
```

Программа и ревизия читаются из одного снимка (`REPEATABLE READ`). Клиент правит `program` и отправляет его в
`sync/push` с `baseRevision = revision` (и `ownerId`, если пишет тренер).

### `GET /api/v2/web/one-rep-maxes?ownerId=`

`{ "ownerId": "…", "oneRepMaxes": [ { "exerciseId": "…", "weightGrams": 160000, "achievedOn": "…", "updatedAt": "…", "revision": … } ] }`

### `GET /api/v2/web/exercises/{exerciseId}/history?ownerId=&limit=5`

Последние тренировки с упражнением (новые сначала, `limit` 1–20) и их выполненные подходы:

```json
{ "ownerId": "…", "exerciseId": "…",
  "entries": [ { "workoutId": "…", "workoutDate": "2026-10-14", "title": "Присед и жим",
                 "sets": [ { "setType": "working", "weightGrams": 120000, "repetitions": 5, "durationMs": null,
                             "distanceM": null, "rpeTenths": 80 } ] } ] }
```

## Вход: клиент Keycloak `sizylift-web`

| Параметр | Значение |
| --- | --- |
| Тип | public client, Authorization Code + PKCE `S256` |
| Redirect URI | `${WEB_PUBLIC_URL}/auth/callback`, `${WEB_PUBLIC_URL}/auth/silent` (тихое восстановление сессии) |
| Web origins | `${WEB_PUBLIC_URL}` |
| Audience | `sizylift-api`, как у мобильного клиента |

`WEB_PUBLIC_URL` по умолчанию `http://localhost:5173` (Vite dev server). Refresh token веб держит только
в памяти, после перезагрузки страницы сессия восстанавливается через cookie Keycloak (`prompt=none`).

Realm импортируется только при первом старте Keycloak: локально — `docker compose down --volumes`, в production
клиент добавляется через админку с адресом веба.

## CORS

`sizylift.web.allowed-origins` — адреса веба на другом origin. В `dev` — `http://localhost:5173`, в
`application.yaml` и production — пусто: веб в production отдаётся с того же домена, что и API, CORS не нужен.
Публикация веба в production (Caddy, статика) — отдельная задача.

## Проверки

`WebApiTest`: свои программы и ревизия, запись через `sync/push` с этой ревизией, удалённые программы,
каталог (системные + свои, чужие не видны), тренер читает подопечного до разрыва связи, посторонний получает
`404`, 1ПМ и история упражнения, CORS для разрешённого и чужого origin.
