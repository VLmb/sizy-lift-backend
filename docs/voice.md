# Голосовой ввод тренировки

Решение — [ADR-0013](adr/0013-voice-workout-input.md). Feature `voice`: тренер или спортсмен диктует тренировку,
сервер возвращает упражнения с подходами, приложение вставляет их в конец тренировки одной транзакцией.
Вопросов пользователю нет: сомнительное помечается и не блокирует работу. Функция выключена по умолчанию.

## Как это работает

```
телефон ──multipart: звук + metadata──▶ POST /api/v2/voice/workout-parse
  доступ к упражнениям владельца (AccessPolicy) → повтор того же requestId → суточный лимит
  → каталог (системные + свои упражнения владельца и вызывающего + личные синонимы + pendingExercises)
  → Deepgram nova-3 (ru, подсказки keyterm: свои названия, личные синонимы, короткие названия системных)
  → разбор текста (voice.parser, без модели, детерминирован)
  → если остались непонятые куски: DeepSeek одним коротким вызовом, ответ проверяется
  ◀── упражнения, подходы, уверенность, непонятые куски
```

Звук и текст речи не хранятся и не логируются: живут только внутри запроса. Ответ на повтор того же
`requestId` держится в памяти 10 минут (не в БД, потому что в нём есть слова речи).

## Разбор текста

Пакет `voice.parser` — чистая Java без Spring и базы:

1. **Нормализация** (`TextTokenizer`): нижний регистр, `ё → е`, числительные в цифры с падежами и
   разговорными формами («с восьмьюдесятью двумя с половиной» → 82.5, «восемьдесятю», «двадцатка»,
   «полторы минуты»), склейка разрядов («восемьдесят два» — 82, «пять три» — два числа).
2. **Названия** (`ExerciseDictionary`): дерево цепочек основ (стеммер Snowball), самое длинное совпадение,
   допуск на опечатки по буквам и по звучанию (оглушение, удвоенные буквы). Предлоги внутри названий пропускаются.
3. **Куски** между названиями — параметры первого; неизвестные слова перед параметрами после связки —
   упражнение не из каталога; слово вплотную за названием понижает уверенность.
4. **Шаблоны параметров** (`ParameterParser`): «N подходов по M», «N по M», «N х M», «A на B» (больше — вес,
   меньше — повторы), «5 на 5 по 100», вес, проценты («70 % от максимума на 3»), RPE, RIR словами,
   отдых («везде» — для всех упражнений), время, дистанция, «до отказа»/«по максимуму», диапазон повторов,
   разминка/рабочие, «последний/первый подход», исправление «нет, на 150», «на каждую сторону».
5. **Покрытие**: каждое слово объяснено шаблоном, названием или связкой; остальное — непонятые куски.

Корпус — `src/test/resources/voice/corpus.txt` (125 фраз, из них 36 по реальным записям). Ручная проверка
без сервера: `./gradlew -q voiceParse --console=plain`.

## Запасная модель

Вызывается, только если после разбора есть непонятые куски, и не больше одного раза на запрос.
Вход — текст (`GapMerger.render`): КАТАЛОГ (`c1<TAB>название | короткое`), ФРАЗА, РАЗБОР, ДЫРКИ
(`G1<TAB>название E2<TAB>жим арнольда<TAB>близкие: c52, c8`). Ответ — строгая схема
`voice/gap-resolution.v1.schema.json`: по каждой дырке `exercise` (alias каталога или `newName` + `resultType`),
`note`, `sets` (строки `n=3 r=10 kg=20`), `filler`, `unknown`.

Проверка: alias есть в каталоге; название и заметка — из слов дырки (с поправкой на ошибку в слове); каждое
число подходов сказано в дырке; повтор ответа по дырке не принимается. Не прошедший проверку ответ
отбрасывается. Таймаут, ошибка провайдера или отключённая модель — куски остаются непонятыми, остальное
возвращается.

## API

Все запросы — с токеном Keycloak, как остальные `/api/v2`.

### `POST /api/v2/voice/workout-parse`

`multipart/form-data`:

- `audio` — запись (до 5 МБ; `audio/*`, `video/mp4`, `video/webm` или `application/octet-stream`;
  m4a/AAC с телефона подходит без перекодирования);
- `metadata` — JSON:

```json
{
  "requestId": "7f3c9a2e-1b4d-4c8e-9a10-5e2f8d6b4a11",
  "ownerId": "4b1e…",
  "pendingExercises": [{"id": "…", "name": "Жим Арнольда", "resultType": "weight_reps"}]
}
```

`requestId` — один на запись: повтор после обрыва связи вернёт тот же ответ без второго распознавания.
`ownerId` — владелец тренировки (не задан — сам вызывающий). `pendingExercises` — свои упражнения
владельца, ещё не дошедшие до сервера синхронизацией (до 200).

Ответ `200`:

```json
{
  "requestId": "…", "ownerId": "…", "speechDetected": true,
  "items": [
    {
      "exerciseId": "14000000-0000-4000-8000-000000000001", "exerciseName": "Присед со штангой",
      "resultType": "weight_reps", "spoken": "присед", "confidence": "high", "restSeconds": 120,
      "lines": [{"count": 3, "reps": 5, "repsMax": null, "toFailure": false, "weightKg": 100.0, "percent": null,
                 "durationSeconds": null, "distanceMeters": null, "rpe": null, "rir": null,
                 "type": "working", "note": null}],
      "notes": [], "unrecognized": [], "issues": [], "candidates": [], "proposal": null
    },
    {
      "exerciseId": null, "exerciseName": null, "resultType": null, "spoken": "жим арнольда",
      "confidence": "low", "restSeconds": null,
      "lines": [{"count": 4, "reps": 10, "weightKg": 16.0, "…": "…"}],
      "notes": [], "unrecognized": [], "issues": [],
      "candidates": [{"exerciseId": "…052", "name": "Жим гантелей сидя"}],
      "proposal": {"name": "Жим Арнольда", "resultType": "weight_reps", "source": "model"}
    }
  ],
  "unrecognized": []
}
```

Правила для приложения:

- `lines[].count` — число одинаковых подходов; `null` — не названо: вставить один подход и выделить.
- Любое поле `null` — «не сказано»: оставить пустым и выделить, ничего не подставлять.
- `restSeconds` — отдых для всех подходов упражнения.
- `confidence`: `high` — как обычно; `medium` — пометка «проверьте»; `low` — упражнение не найдено.
- `exerciseId == null` — строка «Не распознано: {spoken}» с подходами, `candidates` для выбора и
  `proposal` для создания своего (`source: model` — название и тип предложила модель, `spoken` — слова
  речи, тип не известен). В библиотеку ничего не создаётся без нажатия пользователя.
- `notes` — заметки к упражнению; `lines[].note` — к подходам; `unrecognized` — непонятые куски, показать
  серой строкой; `issues` — названные значения, которые не подходят типу упражнения.
- `spoken` — как было сказано: нужно для запоминания синонима при замене.
- `speechDetected: false` — в записи нет речи; тренировка не меняется.

Ошибки (`{"code", "message"}`): `400 invalid_audio` / `invalid_voice_request`, `404 owner_not_found`,
`429 voice_daily_limit`, `502 recognition_failed` (запись можно отправить снова с тем же `requestId`),
`503 voice_disabled`. При любой ошибке тренировка не меняется.

### `POST /api/v2/voice/aliases`

```json
{"ownerId": "…", "exerciseId": "…", "spoken": "хакен"}
```

`204`. Пользователь заменил надиктованное упражнение — `spoken` становится его личным синонимом этого
упражнения (повтор того же названия перезаписывает выбор). Действует в его диктовках, если упражнение есть
в каталоге владельца.

## Данные

Миграция 037: `voice_exercise_alias` — синонимы системных упражнений (заполнены из
`voice/exercise-aliases.txt`; тест сверяет базу и файл; один синоним — одно упражнение),
`voice_user_alias` — личные синонимы, `voice_daily_usage` — суточный счётчик (UTC). Удаление аккаунта
каскадно удаляет личные синонимы и счётчик.

## Настройка

`sizylift.voice.*` (`VoiceProperties`):

| Параметр | По умолчанию |
|---|---|
| enabled | false |
| daily-limit | 100 распознаваний в сутки на пользователя |
| max-audio-bytes | 5 000 000 |
| idempotency-ttl / idempotency-entries | 10m / 10 000 |
| keyterm-chars | 900 (лимит Deepgram — 500 токенов на все подсказки) |
| deepgram.model / language / timeout | nova-3 / ru / 15s |
| fallback.enabled / model / timeout / max-output-tokens | true / deepseek-flash / 4s / 1024 |
| debug.enabled / audio-dir | false / пусто — локальная отладка, [voice-debug.md](voice-debug.md); в `prod` запрещена |

Production (`deploy/.env`): `VOICE_ENABLED=true`, `DEEPGRAM_API_KEY`, `DEEPGRAM_API_URL=https://api.deepgram.com`;
запасная модель берёт `DEEPSEEK_API_KEY` и `DEEPSEEK_API_URL` AI-импорта и без ключа не вызывается. Включённая
функция без ключа Deepgram — ошибка старта.

Перед выпуском интерфейса: уведомление о передаче звука внешнему сервису (Deepgram, США) и проверка
трансграничной передачи персональных данных; доступность Deepgram с production-сервера.

## Метрики

Без пользовательских тегов: `sizylift.voice.requests{outcome}` (ok, no_speech, limit, recognition_failed,
disabled), `sizylift.voice.duration{stage}` (recognition, parse, fallback, total),
`sizylift.voice.fallback{outcome}` (skipped, disabled, resolved, partial, unresolved, failed),
`sizylift.voice.tokens{direction}`, `sizylift.voice.audio.bytes`.

## Тесты

- `voice.parser.*` — корпус и словарь, без базы.
- `VoicePipelineTest` — пайплайн с подменёнными провайдерами: модель не вызывается без дырок, выдуманные
  числа и названия отклоняются, сбой модели не теряет разобранное.
- `VoiceApiTest` — API на PostgreSQL: идемпотентность, лимит, доступ тренера, личные синонимы, свои
  несинхронизированные упражнения, сверка миграции с черновиком синонимов.
- `DeepgramLiveTest`, `DeepSeekLiveTest` — настоящие провайдеры; запускаются только с `DEEPGRAM_API_KEY` /
  `DEEPSEEK_API_KEY` в окружении, иначе пропускаются:

```bash
DEEPGRAM_API_KEY=… DEEPSEEK_API_KEY=… ./gradlew test --tests 'com.sizylift.backend.voice.*LiveTest'
```
