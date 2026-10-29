# Веб-версия: чтение и вход

Контракт `/api/v2/web` — чтение данных для веб-версии (`VLmb/sizy-lift-frontend`). Пишет веб тем же
`POST /api/v2/sync/push`, что и телефон ([sync.md](sync.md)). Экраны веба —
`sizy-lift-frontend/docs/design/web-screens.md`.

Статус: реализованы чтение программ, календаря, тренировок, профиля, истории упражнений и списка подопечных; архив с восстановлением и получение публичной Google Таблицы.

Класс упражнения, %ПМ в плановом подходе и кандидаты для ПМ реализованы; «Входящие» и задачи тренера в работе —
[coach-workspace.md](coach-workspace.md).

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
                   "movementCategoryId": "…", "resultType": "weight_reps", "isSbd": true, "liftClass": "sbd",
                   "note": null,
                   "muscles": [ { "muscleGroupId": "…", "role": "primary" } ], "equipmentIds": [ "…" ],
                   "revision": null } ] }
```

Упражнения: системные (`ownerId: null`), свои упражнения вызывающего и, при `ownerId`, подопечного. У своих
`revision` — ревизия агрегата `exercise` для `baseRevision` при правке. `liftClass` — класс упражнения
(`sbd`, `basic`, `other`; [coach-workspace.md](coach-workspace.md), раздел 1), `isSbd` — производное
`liftClass == "sbd"`, остаётся до перехода телефона.

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
`sync/push` с `baseRevision = revision` (и `ownerId`, если пишет тренер). Плановый подход может нести
`targetPercentOneRmTenths` (% от ПМ в десятых, `1..1500`) вместе с `targetWeightGrams` или вместо него; вес по
проценту пересчитывает веб, сервер хранит оба поля как есть.

### `GET /api/v2/web/one-rep-maxes?ownerId=`

`{ "ownerId": "…", "oneRepMaxes": [ { "exerciseId": "…", "weightGrams": 160000, "achievedOn": "…", "updatedAt": "…", "revision": … } ] }`

### `GET /api/v2/web/exercises/{exerciseId}/history?ownerId=&limit=5&offset=0`

Последние тренировки с упражнением (новые сначала, `limit` 1–20, `offset` 0–100000) и их выполненные подходы:

```json
{ "ownerId": "…", "exerciseId": "…",
  "entries": [ { "workoutId": "…", "workoutDate": "2026-10-14", "title": "Присед и жим",
                 "sets": [ { "setType": "working", "weightGrams": 120000, "repetitions": 5, "durationMs": null,
                             "distanceM": null, "rpeTenths": 80 } ] } ] }
```

### `GET /api/v2/web/max-candidates?ownerId=&from=YYYY-MM-DD`

Выполненные подходы для расчётного ПМ ([coach-workspace.md](coach-workspace.md), раздел 3): упражнения классов
`sbd`/`basic`, тренировки с `workoutDate >= from`, `weightGrams > 0`, `1 ≤ repetitions ≤ 12`, тип результата подхода
`weight_reps`. Порядок — `workoutDate` по убыванию, внутри тренировки — как в ней. `from` обязателен (иначе `400`);
права — как у чтения тренировок, без права `404`. ПМ сервер не считает и не хранит.

```json
{ "from": "2026-09-28",
  "sets": [ { "exerciseId": "…", "workoutId": "…", "workoutDate": "2026-10-20",
              "weightGrams": 140000, "repetitions": 5, "rpeTenths": 85 } ] }
```

## Вход: клиент Keycloak `sizylift-web`

| Параметр | Значение |
| --- | --- |
| Тип | public client, Authorization Code + PKCE `S256` |
| Redirect URI | `${WEB_PUBLIC_URL}/auth/callback`, `${WEB_PUBLIC_URL}/auth/silent` (тихое восстановление сессии) |
| Web origins | `${WEB_PUBLIC_URL}` |
| Audience | `sizylift-api`, как у мобильного клиента |

`WEB_PUBLIC_URL` по умолчанию `http://localhost:5173` (Vite dev server), в production — `https://<домен>`
из `deploy/compose.prod.yaml`. Refresh token веб держит только
в памяти, после перезагрузки страницы сессия восстанавливается через cookie Keycloak (`prompt=none`).
Сколько живёт эта cookie, зависит от галочки «Запомнить меня» на странице входа: с ней — до 14 дней простоя
(максимум 30), без неё — 30 минут простоя и до закрытия браузера
([authentication.md](authentication.md#запомнить-меня)).

Realm импортируется только при первом старте Keycloak: локально — `docker compose down --volumes`, в production
клиент добавляется через админку с адресом веба.

## CORS

`sizylift.web.allowed-origins` — адреса веба (Origin), с которых браузер вызывает API. В `dev` —
`http://localhost:5173`, в `application.yaml` — пусто (CORS выключен), в production — `WEB_ALLOWED_ORIGINS`
без значения по умолчанию: веб живёт на `<домен>`, API — на `api.<домен>`. Публикация веба —
[deployment.md](deployment.md).

## Проверки

`WebApiTest`: свои программы и ревизия, запись через `sync/push` с этой ревизией, удалённые программы,
каталог (системные + свои, чужие не видны), тренер читает подопечного до разрыва связи, посторонний получает
`404`, 1ПМ и история упражнения, CORS для разрешённого и чужого origin. `OneRepMaxPlanningApiTest`: класс в каталоге,
%ПМ в программе и отдельном плане, отбор, порядок и права `max-candidates`.

## Дневник и профиль

- `GET /api/v2/web/timeline?from=YYYY-MM-DD&to=YYYY-MM-DD&ownerId=` — период включительно,
  до 366 дней разницы между границами. `planned`: id тренировки, programId, title, originalDate,
  scheduledDate, rescheduledAt, workoutId и linkedWorkoutDate выполненной тренировки. Дата связанного
  факта доступна и за пределами запрошенного периода. Только активные программы;
  перенос заменяет исходную дату. `workouts`: id, workoutDate, title, startedAt, completedAt,
  quality, plannedWorkoutId, recordCount (число событий рекорда). Порядок факта — новые сначала.
- `GET /api/v2/web/workouts/{id}?ownerId=` — ownerId, id, revision, workout (полный payload v1),
  comments. Комментарий: id, revision, authorId и payload. Снимки плана не берутся из текущей программы:
  они сохранены в тренировке. Запись комментария — обычный sync/push с типом workout_comment.
- `GET /api/v2/web/profile?ownerId=` — ownerId, profile (id, revision, authorId, payload либо null),
  healthAccess, injuries и contraindications (такие же конверты). Без разрешения на здоровье сервер
  возвращает пустые массивы, не имена и не идентификаторы. Профиль и комментарии читаются с ревизиями
  в одном снимке REPEATABLE READ. Если старые клиенты создали несколько идентификаторов синглтона,
  для редактирования возвращается наиболее поздняя ревизия.
- `GET /api/v2/web/athletes` — только активные подопечные вызывающего тренера: linkId, athleteId,
  name, workoutDate, workoutTitle, activityAt, hasNew. Порядок — по последней активности.
- `POST /api/v2/web/athletes/{linkId}/seen` → 204 — отметить просмотр, только действующему тренеру.
  hasNew относится к новым комментариям спортсмена и переносам после последнего просмотра.
  Локальные неотправленные программы серверу неизвестны.

Каждый запрос с ownerId проверяет AccessPolicy; после разрыва связи — 404. Профиль, упражнения,
1ПМ и тренировки подопечного тренер не записывает. Запись остаётся в sync/push с теми же проверками.

## Архив программ

- `GET /api/v2/web/programs/archive?ownerId=` — массив `{id, authorId, program, deletedRevision}`.
- `POST /api/v2/web/programs/{id}/archive?ownerId=`, тело `{revision}` → 204.
- `POST /api/v2/web/programs/{id}/restore?ownerId=` → 204.

Архивация требует точного совпадения прочитанной ревизии (иначе 409) и права записи (иначе 403).
В одной транзакции сохраняется снимок и записывается tombstone через sync/push. Активный план и переносы
убираются, выполненные тренировки не меняются. Восстановление использует тот же id и новую ревизию,
поэтому доходит и до телефона. После более позднего изменения/удаления старый снимок не восстанавливается
(409). Архивированная программа тренера тоже передаётся спортсмену при разрыве связи.

Архив содержит программы, архивированные этим API. Прежние удаления и tombstone с телефона без снимка
не превращаются в восстановимый архив: контракт удаления телефона не менялся.

## Источник импорта

`POST /api/v2/web/import/sheet`, тело `{url}` → `{text}` с CSV публичного листа Google Таблицы.
Поддерживается gid из ссылки, иначе первый лист. При закрытой таблице/ошибке Google — 502,
некорректной ссылке — 400. Только HTTPS docs.google.com и проверенные адреса экспорта
`doc-…-sheets.googleusercontent.com`, максимум три перенаправления, 1 МБ ответа, 15 секунд на запрос.
URL с userinfo, портом и произвольным адресом запрещены. Ссылки и содержимое не сохраняются.
Разбор, вопросы и проверка — в веб-клиенте; сохранение программы и новых упражнений — sync/push.
Это структурированный импорт, без вызова внешней языковой модели и выдуманного дневного лимита.

## Проверки новых сценариев

WebApiTest дополнительно проверяет период и переносы, неизменяемые снимки плана, комментарии,
скрытые травмы и отзыв разрешения, чужие данные, права архива и восстановления, передачу архивов при
разрыве, более позднее удаление, отзыв приглашения, атомарную смену тренера, список подопечных,
SSRF-ограничения и удаление аккаунта. Все интеграционные тесты используют PostgreSQL.

Календарь веба создаёт и правит план через обычный `sync/push` программы, со всеми подходами и её
серверными проверками авторства. Перед явной записью форма сверяет прочитанную ревизию.
Перенос спортсмена — `workout_reschedule` (id плановой тренировки, programId, scheduledDate, updatedAt).
Тренеру этот тип недоступен для записи; он может изменить дату в созданной им программе.
Завершённый факт и его снимки плана от этих действий не меняются.


## Отдельные планы тренировок без программы

`GET /api/v2/web/standalone-workouts/{id}?ownerId=…` возвращает `{ownerId, id, revision, workout}`.
`workout` — вложенная `ProgramPayloadV1.PlannedWorkout` со всеми блоками, упражнениями и подходами (включая
`targetPercentOneRmTenths`).
В `timeline.planned` у такого плана `programId: null`. Программа и выполненная тренировка не создаются.
Связанный факт, если он существует, определяется по `workout.plannedWorkoutId` у того же владельца.

`PUT` того же ресурса принимает `{revision, workout}`. При создании `revision: 0`, далее — текущая ревизия из GET.
ID в URL и payload должны совпадать; вложенные ID уникальны, ограничения вложенных полей совпадают с программой.
Ответ — полный ресурс с увеличенной ревизией. Устаревшая запись — 409. Повтор точного PUT после потери ответа
возвращает уже сохранённый результат, не увеличивая ревизию ещё раз. Удалённый план нельзя воскресить старой записью.

`DELETE /api/v2/web/standalone-workouts/{id}?revision=N` удаляет план с текущей ревизией, ответ 204;
при конфликте 409, при отсутствии 404. Tombstone сохраняет ревизию, payload очищается.
Данные удаляются каскадно при удалении аккаунта. Индекс ограничивает чтение дневника владельцем и датами.

Все методы требуют Bearer JWT. Создание, изменение даты, правка и удаление — только самим владельцем,
проверка через `AccessPolicy` для тренировок, включая переданный `ownerId`. Тренер читает планы активного
подопечного без доступа к записи. Чужим и бывшему тренеру чтение возвращает 404; запрещённая запись — 403.
Новые cookie, серверные HTTP-сессии и согласия не появляются.

Хранилище — `standalone_workout`, миграция 018. Это новый веб-ресурс, пока без выдачи в sync v2:
существующий мобильный клиент отдельные планы ещё не получает. Записи программ, переносы программ и факты
сохраняют прежний контракт синхронизации. Новых настроек окружения и изменений Keycloak нет.
