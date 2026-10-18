# Публикация данных с телефона (E11-T06…T12)

Телефон — источник истины. Сервер хранит копию разрешённых данных для MCP и аналитики.
Схема сверена с SQLite приложения (миграции 1–15, каталоги `2026.09.1` и `2026.10.1`).

Статус: реализовано (сервер — feature `publication`, приложение — `src/application/publication.ts`,
`src/data/sqlite/publication-*.ts`, `src/features/publication/`).

## Принципы

- Публикуются **агрегаты целиком**: тренировка или программа со всеми вложенными
  строками, одним JSON. Сервер заменяет агрегат полностью.
- Идемпотентность — по версии агрегата (`version`): новее сохранённой → заменить,
  иначе no-op.
- Пользователь — только из токена (`user_account.id`). Все таблицы содержат `user_id`.
- Единицы как в приложении: граммы, миллисекунды, метры, RPE×10, даты `YYYY-MM-DD`, UTC.
- Ссылки между агрегатами (тренировка → упражнение, тренировка → план) — без FK:
  агрегаты приходят в любом порядке и могут быть вне области доступа. FK только внутри
  агрегата.
- Не публикуются: фото (`workout_media`, `exercise_media`, `avatar_path`), история
  ревизий программ (`program_revisions`), черновые и активные тренировки.

## Расхождения, найденные при сверке

1. **Ревизия тренировки не растёт при правке подходов.** `workouts.revision` меняется
   только при смене статуса или полей тренировки; подходы и блоки имеют свои ревизии.
   Поэтому версия агрегата — отдельный счётчик outbox (`publication_state.version`),
   а не `revision` корня. У программ `revision` растёт при любой правке, но используем
   тот же механизм для единообразия.
2. **Иерархия плана глубже**: `program → program_cycle → training_week →
   planned_workout → planned_block → planned_exercise → planned_set`.
3. **Блоки плана и факта различаются**: у факта есть раунды и интервалы
   (`planned_rounds`, `interval_work_ms`…), у плана — нет.
4. **В факте есть снимки**: имя и тип упражнения на момент тренировки, исходное
   упражнение при замене, связь с планом (`workout_plan_sources`) и целевые значения
   плана по подходам (`workout_plan_set_snapshots`). Они нужны для «план против факта» —
   публикуются в составе тренировки.
5. **Системные упражнения неизменяемы** (правка = копия с `source = user`), справочники
   категорий, оборудования и мышц заполняются только seed'ом. Значит, эталон на сервере
   — полный каталог, с телефона едут только `source = user`.
6. **Рекорды двух видов**: `record_events` — производные события по подходам
   (публикуются в составе тренировки), `actual_one_rep_maxes` — фактический 1ПМ,
   введённый пользователем, ключ `exercise_id`, без id и ревизии (отдельный агрегат).
7. Архивированные строки внутри агрегата (`archived_at`) не публикуются — для сервера
   это удаление. Архивированная тренировка или программа → tombstone.

## Агрегаты

| type | Источник в SQLite | Когда публикуется | Ключ |
| --- | --- | --- | --- |
| `workout` | `workouts` + blocks, exercises, `actual_sets`, plan sources/snapshots, `record_events` | `status = completed`; `archived` → tombstone | `workouts.id` |
| `program` | `programs` + cycles, weeks, planned workouts/blocks/exercises/sets | любой статус, кроме `archived` (→ tombstone) | `programs.id` |
| `exercise` | `exercises` (`source = user`) + muscles, equipment | всегда; архив → tombstone | `exercises.id` |
| `athlete_profile` | `athlete_profile` без `avatar_path` | всегда | синглтон |
| `one_rep_max` | `actual_one_rep_maxes` | всегда | `exercise_id` |
| `injury`, `contraindication` | одноимённые таблицы | только с согласием | id |

## Таблицы на сервере

- **Каталог** (общий, Liquibase-seed с UUID приложения): `exercise_catalog_version`,
  `movement_category`, `equipment`, `muscle_group`, `exercise` (`user_id NULL` =
  системное), `exercise_muscle`, `exercise_equipment`.
- **Факт**: `workout`, `workout_block`, `workout_exercise`, `workout_set`,
  `workout_plan_set_snapshot`, `workout_record_event`.
- **План**: `program`, `program_cycle`, `training_week`, `planned_workout`,
  `planned_block`, `planned_exercise`, `planned_set`.
- **Прочее**: `athlete_profile`, `one_rep_max`, `injury`, `contraindication`.
- **Служебное**: `published_aggregate` (`user_id`, `type`, `aggregate_id`, `version`,
  `deleted`, `received_at`) — версия каждого агрегата; `publication_scope` — что
  разрешено.

Колонки дочерних таблиц повторяют SQLite (кроме `created_at`/`updated_at`/`revision`
дочерних строк — не нужны), плюс `user_id`. Корни хранят `updated_at` из приложения.

## API

`POST /api/v1/publication/batch` — до 100 агрегатов и 1 МБ.

```json
{ "items": [ { "type": "workout", "id": "…", "version": 12, "schemaVersion": 1,
               "deleted": false, "payload": { … } } ] }
```

Ответ — по каждому элементу: `accepted` (сохранена версия N), `stale` (на сервере уже
такая или новее — тоже доставлено), `out_of_scope` (пользователь не разрешил; копии и версии
на сервере нет, поэтому после расширения области агрегат примут снова), `rejected` + код
(`invalid_payload`, `id_conflict`, `unsupported_type`, `unsupported_schema`). Ошибка одного
элемента не влияет на остальные.

Область доступа: `GET/PUT /api/v1/publication/scope` (`enabled`, `workouts`,
`workoutsSince`, `programs` = `none|active|all`, `profile`, `records`, `health`; по умолчанию
всё, кроме `health`). `PUT` сразу удаляет данные вне новой области.
`DELETE /api/v1/publication/data` удаляет всё и выключает приём (`enabled = false`).

Приём: Jackson → record `WorkoutPayloadV1` и т.п. → Bean Validation → проверка области
→ в одной транзакции на агрегат: `published_aggregate` (сравнение версии) → удалить
дочерние строки → вставить новые. Логи без содержимого payload.

## Outbox в приложении

- `publication_outbox(type, id, version, attempts, next_attempt_at, last_error)` —
  одна строка на агрегат; `publication_state(type, id, version)` — монотонный счётчик.
- SQLite-триггеры на всех таблицах тренировок, планов, своих упражнений, профиля, 1ПМ и
  здоровья в той же транзакции увеличивают `version` корня и upsert'ят строку outbox
  (подход → его тренировка, planned_set → его программа). Триггеры вместо кода в
  сервисах: новую запись нельзя «забыть» опубликовать.
- Отправитель: при запуске, возврате на экран, через ~3 с после изменений, при
  появлении сети; одна отправка за раз. Payload собирается из текущего состояния в
  момент отправки.
- Подтверждение `accepted`/`stale` с версией ≥ версии строки → удалить строку;
  `rejected` → пауза 1 мин…6 ч, остальные не блокируются.
- Первая публикация (новый аккаунт) и любое изменение области = поставить в outbox все
  подходящие агрегаты; неизменённые сервер вернёт как `stale`.
- Область кэшируется локально и проверяется до отправки: травмы без согласия не покидают
  телефон.

## Контракт и проверки

- Фикстуры payload v1: `src/test/resources/publication/*.v1.json` (копия — в приложении,
  `tests/fixtures/publication`). Сервер принимает их в `PublicationApiTest`, приложение строит
  из SQLite payload, равный им (`tests/publication.test.ts`).
- Каталог: `src/test/resources/catalog/exercise-catalog.json` — выгрузка из приложения
  (`tests/exercise-catalog-contract.test.ts`), `ExerciseCatalogContractTest` сверяет его с БД.
  Новая версия каталога в приложении → обновить фикстуру (`UPDATE_CATALOG_FIXTURE=1`) и
  добавить новый changeset; существующие не менять.

## Задачи (выполнены)

1. **Сервер: каталог, таблицы, приём.** Liquibase: каталог упражнений `2026.09.1`
   (UUID из seed приложения, тест на совпадение с JSON-выгрузкой каталога), таблицы
   данных и `published_aggregate`; `publication` feature: endpoint, payload-record'ы v1,
   валидация, замена агрегата по версии. Тесты: идемпотентность, старая версия,
   tombstone, изоляция пользователей, частичный отказ пачки.
2. **Приложение: outbox и отправка.** Миграция SQLite (`publication_outbox`,
   `publication_state`), хук в транзакциях сервисов, сборщики payload v1, отправитель с
   триггерами и backoff, статус «последняя публикация» в разделе аккаунта.
   Публикация включается вместе со входом в аккаунт (всё, кроме травм). Тесты: версия
   растёт при правке подхода, повтор после ошибки сети, контрактные JSON-фикстуры,
   общие с сервером.
3. **Область доступа и удаление.** `publication_scope` и `GET/PUT
   /api/v1/publication/scope`; экран выбора (категории, период тренировок, программы,
   согласие на травмы); сужение → сервер удаляет вышедшее, расширение → первичная
   выгрузка; «удалить данные с сервера» и выход из аккаунта с удалением.
