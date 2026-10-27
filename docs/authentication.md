# Аутентификация

Keycloak — сервер авторизации (OpenID Provider): регистрация, пароли, подтверждение
почты, восстановление доступа, вход через Google/Facebook, выдача и отзыв токенов.
Бэкенд Sizy — OAuth 2.0 resource server: он не видит паролей и не выдаёт токенов,
а только проверяет access token в каждом запросе.

```text
мобильное приложение ──(системный браузер, Authorization Code + PKCE)──▶ Keycloak
        │                                                                   │
        │◀───────────── access token (JWT, 5 мин) + refresh token ─────────┘
        │
        └──(Authorization: Bearer <access token>)──▶ Sizy API ──(JWKS, кэш)──▶ Keycloak
```

## Realm `sizylift`

Конфигурация хранится в [`keycloak/realm-sizylift.json`](../keycloak/realm-sizylift.json)
и импортируется при первом старте Keycloak (`--import-realm`). Существующий realm при
повторном старте **не перезаписывается**: после изменения файла локально выполните
`docker compose down --volumes` (данные локальных БД будут удалены), в production —
внесите то же изменение через админку.

- Регистрация по email (email = username), повторяющиеся email запрещены.
- Регистрация в Keycloak 26.8: email и имя → письмо → переход по ссылке → установка
  пароля → возврат в приложение. Токены не выдаются до подтверждения почты и установки пароля.
  Ссылка из письма и сам сценарий входа действуют 30 минут
  (`actionTokenGeneratedByUserLifespan`, `accessCodeLifespanLogin`).
- Если ссылку открыть в другом браузере (без cookie окна входа), Keycloak завершает
  подтверждение и установку пароля, но не возвращает в приложение — пользователь входит
  заново. В том же браузере Keycloak перенаправляет на `sizylift://auth/callback`, и
  приложение завершает вход по сохранённому PKCE-verifier.
- Политика пароля: 10–128 символов, не совпадает с email.
- Защита от перебора: после 10 ошибок временная блокировка с нарастающей паузой до 15 минут.
- Интерфейс на русском и английском.
- Google и Facebook описаны в realm, но выключены, пока не заданы
  `GOOGLE_IDP_ENABLED`/`FACEBOOK_IDP_ENABLED` и ключи (переменные окружения Keycloak,
  подставляются в realm-файл при импорте). Google доверяет подтверждённому email
  провайдера; для Facebook Keycloak сам подтверждает почту. Если email уже
  зарегистрирован, Keycloak предлагает связать аккаунты после подтверждения владения им.
  Redirect URI для консолей Google/Facebook:
  `<KEYCLOAK_PUBLIC_URL>/realms/sizylift/broker/{google|facebook}/endpoint`.

## Клиент `sizylift-mobile`

Страницы входа, регистрации, проверки почты, установки/сброса пароля и ошибок
используют тему `sizy` (палитра «Бронза», DIN 2014). Тема наследует `keycloak.v2`
26.8.1 и сохраняет серверные URL действий и проверки Keycloak.
При регистрации используется flow `sizy-registration` со штатными созданием
пользователя и проверкой пароля, а также провайдером `sizy-registration-consent`.
Провайдер ничего не запрашивает, пока `sizy.legal.enabled=false` (по умолчанию).
Версионированное согласие, ограничения и включение — [keycloak-theme.md](keycloak-theme.md).

| Параметр | Значение |
| --- | --- |
| Тип | public client (без секрета) |
| Поток | Authorization Code + PKCE `S256`; password grant и implicit выключены |
| Redirect URI | `sizylift://auth/callback` |
| Post-logout redirect URI | `sizylift://auth/logout` |
| Scope по умолчанию | `openid profile email` |
| Дополнительно | `offline_access` — долгоживущий refresh token |

Endpoints (discovery: `<issuer>/.well-known/openid-configuration`):

- authorization: `<issuer>/protocol/openid-connect/auth`
- token: `<issuer>/protocol/openid-connect/token`
- revoke: `<issuer>/protocol/openid-connect/revoke`

### Время жизни токенов

| Токен | Время жизни |
| --- | --- |
| Access token | 5 минут |
| Refresh token (с `offline_access`) | 30 дней с последнего использования |
| Refresh token без `offline_access` | 30 минут простоя, максимум 10 часов (SSO-сессия браузера) |
| То же с «Запомнить меня» | 14 дней простоя, максимум 30 дней |

### «Запомнить меня»

Веб не хранит токены между перезагрузками и восстанавливает вход тихим запросом
`prompt=none` по SSO-cookie Keycloak. Поэтому срок, через который сайт снова попросит
войти, задаёт SSO-сессия Keycloak, а не бэкенд (он остаётся `STATELESS`).

В realm включён `rememberMe`. На странице входа темы `sizy` это заметный блок перед
кнопкой «Войти» с пометкой «Рекомендуем» и предупреждением про общие устройства.
При первом показе формы галочка отмечена; после неудачной попытки форма сохраняет выбор
пользователя. С галочкой Keycloak ставит постоянную cookie и держит сессию
`ssoSessionIdleTimeoutRememberMe` = 14 дней простоя, но не дольше
`ssoSessionMaxLifespanRememberMe` = 30 дней. Без галочки — прежние 30 минут / 10 часов,
cookie удаляется при закрытии браузера. Мобильного приложения настройка по сути не
касается: оно хранит offline refresh token.

Существующий realm эти настройки не получит при перезапуске. В production:
**Realm settings → Login → Remember me** — включить; **Realm settings → Sessions** —
*SSO Session Idle Remember Me* 14 дней, *SSO Session Max Remember Me* 30 дней.
Локально — `docker compose down --volumes`.

Refresh token одноразовый (rotation): каждое обновление возвращает новый refresh
token, повторное использование старого даёт `invalid_grant`. Клиент должен
сериализовать обновление токена (один запрос за раз) и сохранять новый refresh token
до использования access token.

### Что делает мобильное приложение (реализовано в `VLmb/sizy-lift-app`, `docs/authentication.md`)

1. Открывает системный браузер (`expo-auth-session` / `expo-web-browser`) с запросом
   `scope=openid offline_access`, `code_challenge_method=S256`, случайным `state`;
   `prompt=create` сразу открывает регистрацию, `prompt=login` требует ввода пароля даже
   при живой сессии браузера (нужно, чтобы после выхода войти другим аккаунтом).
2. Получает `sizylift://auth/callback?code=…&state=…`, сверяет `state`, обменивает код
   на токены вместе с `code_verifier`.
3. Хранит refresh token только в SecureStore; access token можно держать в памяти.
4. Отправляет `Authorization: Bearer <access token>`; при `401` один раз обновляет токен
   и повторяет запрос.
5. Выход: `POST /revoke` с refresh token, затем удаление токенов из SecureStore.
6. Аккаунт необязателен: без входа локальный дневник работает полностью.

## Изменение имени, почты и пароля из веба

Веб не отправляет пользователя в консоль аккаунта Keycloak. Кнопки профиля открывают
авторизацию с `kc_action` — Keycloak показывает одну страницу темы `sizy` и возвращает
на `/profile` с новыми токенами:

| Кнопка | `kc_action` | Что происходит |
| --- | --- | --- |
| Имя | `UPDATE_PROFILE` | имя и фамилия; почту на этой странице изменить нельзя |
| Почта | `UPDATE_EMAIL` | письмо на новый адрес, почта и логин меняются после перехода по ссылке |
| Пароль | `UPDATE_PASSWORD` | новый пароль, по желанию выход на других устройствах |

Required action `UPDATE_EMAIL` включён в realm-файле (фича `update-email` в 26.8 включена
по умолчанию). Для существующего realm: **Authentication → Required actions → Update Email** —
*Enabled*. Консоль аккаунта по прямому адресу остаётся доступной, но из интерфейса не открывается.

## Проверка токена на бэкенде

Spring Security (`spring-boot-starter-security-oauth2-resource-server`) проверяет:

- подпись RS256 по ключам из JWKS Keycloak (ключи кэшируются, при неизвестном `kid`
  перезапрашиваются — ротация ключей не требует перезапуска);
- `exp`/`nbf` с допуском 60 секунд;
- `iss` равен `KEYCLOAK_ISSUER_URI`;
- `aud` содержит `sizylift-api` (добавляется audience-mapper клиента). ID token
  (`aud=sizylift-mobile`) и refresh token отклоняются.

Ошибка проверки → `401` с `WWW-Authenticate: Bearer error="invalid_token"`.
Сессий и cookie на бэкенде нет (`STATELESS`), CSRF выключен, так как браузер не
отправляет Bearer-токен автоматически. Без токена доступны только GET `/actuator/health` и его probes. В production они слушают внутренний
порт 8081, не выставленный reverse proxy. Остальные actuator-пути запрещены и обычному пользователю.
Служебный ERROR-dispatch разрешён для формирования ошибок; прямой запрос `/error` закрыт.
Form login, HTTP Basic, Spring logout и request cache явно выключены.

`KEYCLOAK_ISSUER_URI` — публичный адрес, который видит телефон, он же попадает в `iss`.
`KEYCLOAK_JWK_SET_URI` может быть внутренним адресом (например, `http://keycloak:8080/…`
внутри Docker-сети). Ключи загружаются при первом запросе, поэтому бэкенд стартует
без доступного Keycloak.

## Аккаунт Sizy

Таблица `sizylift.user_account` связывает внешнюю идентичность (`issuer` + `sub`) с
собственным UUID аккаунта (UUIDv7). Аккаунт создаётся при первом обращении к API
(just-in-time provisioning) через `INSERT … ON CONFLICT DO NOTHING`, поэтому
параллельные первые запросы создают одну строку. Email и признак подтверждения
синхронизируются из токена при каждом обращении к `/api/v1/me`.

Другие функции должны ссылаться на `user_account.id`, а не на `sub`: так смена
провайдера или перенос пользователей не затронет предметные данные.

### `GET /api/v1/me`

```json
{
  "id": "01a14f07-921c-7a8e-ab41-ddd034c49296",
  "email": "lifter@example.com",
  "emailVerified": true,
  "createdAt": "2026-10-18T12:40:58.588962Z"
}
```

## Локальная разработка

`docker compose up -d --wait` поднимает PostgreSQL приложения, Keycloak с отдельным
PostgreSQL и Mailpit; `./gradlew bootRun` запускает бэкенд в профиле `dev`:

- админка Keycloak: http://localhost:8180/admin (`admin`/`admin`);
- письма (подтверждение, сброс пароля): http://localhost:8025.

Keycloak выставляет cookie с флагом `Secure`. Браузеры считают `http://localhost`
безопасным контекстом, поэтому в iOS Simulator и на компьютере вход работает по HTTP.
С физического телефона по адресу вида `http://192.168.x.x` браузер такие cookie не
отправит и вход сломается. Для проверки на устройстве откройте Keycloak и бэкенд через
HTTPS-туннель, задайте адрес туннеля в `KEYCLOAK_PUBLIC_URL` (`.env`) и передайте
бэкенду тот же issuer:
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://<туннель>/realms/sizylift`.

## Production

`deploy/compose.prod.yaml`: Keycloak собран с `start --optimized`, работает за Caddy
только по HTTPS (`sslRequired: all`), доверяет `X-Forwarded-*`, отправляет письма
через настоящий SMTP, админка закрыта снаружи и доступна через SSH-туннель.
Подробности — [deployment.md](deployment.md).

Ещё не сделано: управление конфигурацией realm без ручных правок после первого
импорта (например, keycloak-config-cli) и хранилище секретов вместо файла `.env`.

## Удаление аккаунта из веба

`DELETE /api/v1/me` требует access token с auth_time не старше пяти минут. Иначе 403; веб запрашивает
повторный вход с prompt=login и max_age=0, затем показывает отдельное подтверждение удаления.
Сервер всегда удаляет аккаунт вызывающего, не принимает ownerId.

Перед удалением проверяется доступ отдельного сервисного клиента к пользователю Keycloak. Без настройки
или при недоступном Keycloak — 503, данные Sizy остаются. В транзакции разрываются связи, программы
тренера передаются подопечным, удаляются собственные данные через каскадные FK и создаётся задача
удаления идентичности Keycloak. Ответ — 202. Программы и комментарии, уже находящиеся у подопечных,
остаются у них. Файлы фото сервер и раньше не получал.

Задание Keycloak повторяется каждые 30 секунд; 404 считается успешным удалением. После успеха задание
с issuer/sub удаляется. Остаётся только SHA-256 отпечаток прежней пары issuer/sub, чтобы старый access
или refresh token не создал аккаунт заново. Новый аккаунт с тем же email и новым sub допустим.
Задачи и ключи не попадают в логи. Метрики: sizylift.accounts.deletion.pending и
sizylift.accounts.deletion.failures, без идентификаторов и email.

Настройка сервисного клиента и обновление — [deployment.md](deployment.md#удаление-аккаунтов).

## Проверка границ веба (26 октября 2026)

- Все зарегистрированные `/api/**` проверяются без токена в SecurityBoundaryApiTest. Проверка выполняется
  до разбора параметров и JSON. JWT из Cookie, query, form и HttpSession не принимается; предыдущий
  успешный Bearer-запрос не авторизует следующий. API не выставляет Set-Cookie/JSESSIONID.
- Веб использует Authorization Code + PKCE S256. Access/refresh/ID tokens — InMemoryWebStorage,
  не localStorage/sessionStorage. В sessionStorage остаётся маршрут возврата; oidc-client-ts по умолчанию
  сохраняет краткоживущий state OIDC-транзакции (в том числе PKCE-verifier) в localStorage и удаляет
  после callback. State не является access/refresh token и не авторизует запросы API. Запросы данных используют
  `credentials: omit`. OIDC-запросы выполняет отдельная библиотека.
- У Keycloak есть собственная SSO-сессия и cookies. HttpOnly identity-cookie нужна серверу входа;
  session-status-cookie может быть доступна JS для штатного контроля SSO, она не является Bearer-токеном
  API. После перезагрузки веб получает новые токены через Keycloak, API-сессию не восстанавливает.
- CORS допускает только настроенные точные Origin, без credentials. Это ограничение браузера;
  права на данные проверяются сервером независимо от Origin, UI, ролей в JS или переданного ownerId.
- Прямые запросы из DevTools могут выполнять разрешённые действия своего аккаунта. Запретить DevTools
  как средство отправки HTTP нельзя. JWT-подпись, issuer/audience/срок, права на конкретную запись,
  авторство и разрешение на здоровье — граница защиты чужих данных. HTML комментариев выводится текстом.
- До разбора Jackson ограничивается тело: publication v1 — 1 MiB, остальные API — 16 MiB. Больше — 413;
  проверяется и Content-Length, и фактический поток при chunked. Фильтр стоит после авторизации:
  неавторизованный запрос получает 401 без чтения тела.

Ограничения модели: локальная проверка JWT не обращается в Keycloak при каждом запросе. Выход,
блокировка или отзыв refresh token не отменяют уже выданный access token мгновенно: он действует
до exp с допуском проверки времени (TTL 5 минут, допуск 60 секунд). Удалённая идентичность отдельно
блокируется в Sizy. Мгновенный отзыв потребовал бы introspection/denylist и отдельного решения.
API-лимит тела не заменяет ограничения частоты запросов, числа соединений и защиту от DDoS на входе.
Production проверен по коду конфигурации; запросы в production в рамках этой проверки не выполнялись.
