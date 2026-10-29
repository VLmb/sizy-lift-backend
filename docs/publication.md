# Публикация данных с телефона (E11-T06…T12)

Телефон — источник истины. Сервер хранит копию данных пользователя для MCP и аналитики.

**Продуктовое решение (18.10.2026):** пользователь ничего не настраивает и не видит. Сразу
после входа в аккаунт на сервер копируется всё перечисленное ниже, включая травмы и
противопоказания. Экрана выбора, статуса публикации и отдельного согласия на медицинские
сведения нет. Это отступление от AI-002 и раздела «Приватность» спецификации; если
согласие понадобится (например, по требованиям к медицинским данным), его можно вернуть
отдельным шагом.
Схема сверена с SQLite приложения (миграции 1–15, каталоги `2026.09.1`, `2026.10.1` и `2026.10.2`).

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
  агрегаты приходят в любом порядке. FK только внутри агрегата.
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
   1ПМ можно указать у любого упражнения с `weight_reps` или `added_weight_reps` (для
   доп. веса это доп. вес). e1RM считает только приложение (подход без RPE — как RPE 10),
   сервер хранит присланные события и их не пересчитывает.
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
| `injury`, `contraindication` | одноимённые таблицы | всегда; архив → tombstone | id |

## Таблицы на сервере

- **Каталог** (общий, Liquibase-seed с UUID приложения): `exercise_catalog_version`,
  `movement_category`, `equipment`, `muscle_group`, `exercise` (`user_id NULL` =
  системное), `exercise_muscle`, `exercise_equipment`.
- **Факт**: `workout`, `workout_block`, `workout_exercise`, `workout_set`,
  `workout_plan_set_snapshot`, `workout_record_event`.
- **План**: `program`, `program_cycle`, `training_week`, `planned_workout`,
  `planned_block`, `planned_exercise`, `planned_set`.
- **Прочее**: `athlete_profile`, `one_rep_max`, `injury`, `contraindication`; `one_rep_max_history` — история
  значений 1ПМ, пополняется каждой принятой записью с новым значением, удаляется вместе с агрегатом
  ([web.md](web.md)).
- **Служебное**: `published_aggregate` (`user_id`, `type`, `aggregate_id`, `version`,
  `deleted`, `received_at`, а также `revision`, `changed_at`, `author_user_id`, `source_client_id`,
  `source_version` для sync v2) — состояние каждого агрегата; `aggregate_conflict` — журнал
  проигравших версий.

Колонки дочерних таблиц повторяют SQLite (кроме `created_at`/`updated_at`/`revision`
дочерних строк — не нужны), плюс `user_id`. Корни хранят `updated_at` из приложения.

## API

`POST /api/v1/publication/batch` — до 100 агрегатов и 1 MiB HTTP-тела. Превышение — 413 до разбора JSON, в том числе без Content-Length.

```json
{ "items": [ { "type": "workout", "id": "…", "version": 12, "schemaVersion": 1,
               "deleted": false, "payload": { … } } ] }
```

Ответ — по каждому элементу: `accepted` (сохранена версия N), `stale` (на сервере уже
такая или новее — тоже доставлено), `rejected` + код
(`invalid_payload`, `id_conflict`, `unsupported_type`, `unsupported_schema`, `forbidden` — агрегат
пишет другой автор, программа тренера; см. [coaching.md](coaching.md)). Ошибка одного
элемента не влияет на остальные.

Приём: Jackson → record `WorkoutPayloadV1` и т.п. → Bean Validation → в одной транзакции на агрегат: `published_aggregate` (сравнение версии) → удалить
дочерние строки → вставить новые. Логи без содержимого payload.

## Класс упражнения и %ПМ (changeset `019`)

Контракт — [coach-workspace.md](coach-workspace.md), разделы 1–2.

- `exercise`: необязательное `liftClass` (`sbd`, `basic`, `other`) рядом с `isSbd`. В ответах (лента sync,
  каталог веба) `liftClass` есть всегда, `isSbd` = `liftClass == "sbd"`. При записи `liftClass` важнее `isSbd`;
  без него `isSbd: true` даёт `sbd`, `isSbd: false` оставляет прежний класс (прежний `sbd` → `other`), новое
  упражнение — `other`. `sbd`/`basic` у не `weight_reps` → `rejected: invalid_payload`; `isSbd: true` без
  `liftClass` у такого упражнения не ошибка (старый телефон), класс — `other`. В БД — `exercise.lift_class`,
  `is_sbd` удалена.
- `program`: необязательное `targetPercentOneRmTenths` в подходе (`1..1500`, `725` = 72,5 %), колонка
  `planned_set.target_percent_one_rm_tenths`. Вес по проценту сервер не пересчитывает.
- Общие фикстуры `*.v1.json` новых полей пока не содержат: приложение их ещё не отправляет. `SyncChangesApiTest`
  сверяет ленту с фикстурами с учётом этих необязательных полей; фикстуры обновляются вместе с приложением.

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
- Первая публикация (новый аккаунт на устройстве) = поставить в outbox все существующие
  агрегаты; неизменённые сервер вернёт как `stale`.

## Совместимость с sync v2

Переносы (`workout_reschedule`) и комментарии (`workout_comment`) v1 не принимает
(`unsupported_type`): их пишут только через sync v2. Удаление тренировки через v1 тоже удаляет её
комментарии, а запись программы — устаревшие переносы ([coaching.md](coaching.md)).


Публикация v1 продолжает работать. Каждая запись получает серверную ревизию и `changed_at`
(`updatedAt` корня, у tombstone — время получения; не позже серверного «сейчас»). Если агрегат
последним записан через `POST /api/v2/sync/push`, побеждает правка с более поздним `updatedAt`:
проигравшая v1-запись получает `stale` и попадает в журнал конфликтов. Принятые записи v1 попадают в
ленту изменений владельца (`GET /api/v2/sync/changes`). Подробности —
[sync.md](sync.md).

## Контракт и проверки

- Фикстуры payload v1: `src/test/resources/publication/*.v1.json` (копия — в приложении,
  `tests/fixtures/publication`). Сервер принимает их в `PublicationApiTest`, приложение строит
  из SQLite payload, равный им (`tests/publication.test.ts`).
- Каталог: `src/test/resources/catalog/exercise-catalog.json` — выгрузка из приложения
  (`tests/exercise-catalog-contract.test.ts`), `ExerciseCatalogContractTest` сверяет его с БД.
  Новая версия каталога в приложении → обновить фикстуру (`UPDATE_CATALOG_FIXTURE=1`) и
  добавить новый changeset; существующие не менять.

## Не сделано

- Удаление аккаунта и собственных опубликованных данных реализовано через `DELETE /api/v1/me`; контракт — [authentication.md](authentication.md).
- Rate limits на приём.
