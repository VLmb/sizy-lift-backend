# Аутентификация

Keycloak — сервер авторизации (OpenID Provider): регистрация, пароли, подтверждение
почты, восстановление доступа, вход через Google/Facebook, выдача и отзыв токенов.
Бэкенд SizyLift — OAuth 2.0 resource server: он не видит паролей и не выдаёт токенов,
а только проверяет access token в каждом запросе.

```text
мобильное приложение ──(системный браузер, Authorization Code + PKCE)──▶ Keycloak
        │                                                                   │
        │◀───────────── access token (JWT, 5 мин) + refresh token ─────────┘
        │
        └──(Authorization: Bearer <access token>)──▶ SizyLift API ──(JWKS, кэш)──▶ Keycloak
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
отправляет Bearer-токен автоматически. Без токена доступен только `/actuator/health`.

`KEYCLOAK_ISSUER_URI` — публичный адрес, который видит телефон, он же попадает в `iss`.
`KEYCLOAK_JWK_SET_URI` может быть внутренним адресом (например, `http://keycloak:8080/…`
внутри Docker-сети). Ключи загружаются при первом запросе, поэтому бэкенд стартует
без доступного Keycloak.

## Аккаунт SizyLift

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
