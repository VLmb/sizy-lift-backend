# Отладка голосового ввода

Как поднять сервер с голосом локально, увидеть, во что Deepgram превратил речь и что вернул разбор, и
прослушать саму запись. Контракт и устройство пайплайна — [voice.md](voice.md).

## Запуск

Как в [README](../README.md#локальный-запуск), плюс ключи и флаги отладки. Ключи — только в переменных
окружения (или в `.env`, он в `.gitignore`), не в файлах репозитория:

```bash
docker compose up -d --wait
```

```bash
export SIZYLIFT_VOICE_ENABLED=true
export SIZYLIFT_VOICE_DEEPGRAM_APIKEY=<ключ Deepgram>
export SIZYLIFT_VOICE_FALLBACK_APIKEY=<ключ DeepSeek>      # без него запасная модель не вызывается
export SIZYLIFT_VOICE_DEBUG_ENABLED=true
export SIZYLIFT_VOICE_DEBUG_AUDIODIR=$HOME/sizy-voice-debug # необязательно: сохранять записи
```

```bash
./gradlew bootRun 2>&1 | tee backend.log
```

При старте — предупреждение `Отладка голосового ввода включена…`.

| Параметр | По умолчанию | |
|---|---|---|
| `sizylift.voice.debug.enabled` | false | текст речи и ответы — в лог `com.sizylift.backend.voice.debug` |
| `sizylift.voice.debug.audio-dir` | пусто | папка для записей и разбора по каждому запросу |

## Лог

Логгер `com.sizylift.backend.voice.debug`, уровень INFO, по каждому запросу (`<id>` — `requestId` клиента):

```
voice <id> audio: 30142 bytes, audio/webm
voice <id> audio saved: …/20261114-122545_<id>.webm
voice <id> transcript (820 ms): «Присед 3 по 5 на 100, отдых 2 минуты. Потом жим лежа 4 по 8 на 60.»
voice <id> parsed: {…}                    ← разбор текста без модели
voice <id> fallback input: …               ← только если остались дырки: что ушло в DeepSeek
voice <id> fallback answer: …              ←   и что он ответил
voice <id> response (fallback SKIPPED): {…} ← что получил клиент
```

Подсказки Deepgram (`keyterms`, названия каталога) — на DEBUG; в профиле `dev` он уже включён для
`com.sizylift`. Ответ пустой, пайплайн упал — строка `voice <id> failed: …`.

```bash
grep "voice\.debug" backend.log
```

Где искать проблему:

- **текст уже неправильный** (`transcript`) — распознавание: качество записи (прослушать), подсказки,
  произношение;
- **текст верный, `parsed` неверный** — разбор `voice.parser`: фразу — в корпус
  `src/test/resources/voice/corpus.txt`, проверка без сервера `./gradlew -q voiceParse --console=plain`;
- **`parsed` верный, `response` неверный** — запасная модель (строки `fallback`);
- **`response` верный, в приложении не то** — применение ответа на клиенте.

## Записи

С `audio-dir` на каждый запрос два файла `<yyyyMMdd-HHmmss>_<requestId>`:

- `.webm` (Chrome, Android PWA), `.m4a` (iOS, Safari), `.ogg`, `.wav` — то, что пришло от клиента, байт в байт;
- `.json` — запрос целиком: тип и размер, `transcript`, `keyterms`, `recognitionMs`, `parsed`,
  `fallbackInput`/`fallbackAnswer`, `fallback`, `response`, `error` (если упало).

Слушать: открыть файл в Chrome/Firefox (перетащить во вкладку) или

```bash
ffplay -autoexit -nodisp ~/sizy-voice-debug/<файл>.webm
```

```bash
ffprobe ~/sizy-voice-debug/<файл>.webm      # длительность, кодек, частота
```

Тот же файл можно прогнать через сервер ещё раз, не трогая телефон (нужен токен пользователя;
`requestId` — новый, иначе вернётся сохранённый ответ):

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -F "audio=@<файл>.webm;type=audio/webm" \
  -F "metadata={\"requestId\":\"$(uuidgen)\"};type=application/json" \
  http://localhost:8080/api/v2/voice/workout-parse
```

Папку чистить руками: в ней голос людей.

## На production-сервере

Отладка пишет **всех**, кто диктует: включать на время проверки, пока чужих людей на сервере нет.

1. Из корня репозитория на сервере (как в [deployment.md](deployment.md)): в `deploy/.env` —
   `VOICE_DEBUG_ENABLED=true`; папка для записей `deploy/voice-debug`, сервер в контейнере работает от uid 1000:

```bash
mkdir -p deploy/voice-debug && sudo chown 1000:1000 deploy/voice-debug
```

```bash
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml up -d --build --wait backend
```

2. Надиктовать с телефона и с сайта. Лог:

```bash
docker compose --env-file deploy/.env -f deploy/compose.prod.yaml logs backend | grep "voice\.debug"
```

3. Забрать записи к себе (с компьютера):

```bash
scp -r <пользователь>@<сервер>:<путь к репозиторию>/deploy/voice-debug ./voice-debug
```

4. Выключить: `VOICE_DEBUG_ENABLED=false`, снова та же команда `up`, папку на сервере очистить. Папка в
   `.gitignore` — записи в этот репозиторий не попадут.

## Клиенты

- PWA и приложение против этого сервера — `sizy-lift-app/docs/architecture/voice-debug.md`
  (`node scripts/dev-pwa-local.mjs`).
- Сайт — `sizy-lift-frontend/docs/voice-input.md`.
