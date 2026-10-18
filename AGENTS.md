# SizyLift backend

- Use JDK 25 and the checked-in Gradle Wrapper. Read README.md and docs/architecture.md.
- Run `./gradlew build` before completing code changes. Tests require Docker and real PostgreSQL; do not skip them or substitute H2.
- Use a conventional layered architecture: controller → service → repository. Controllers handle HTTP and DTOs; services own business logic and transaction boundaries; repositories access data. Group classes by feature, with controller, service, repository, dto, and entity packages inside each feature as needed. Do not create empty future modules.
- Add immutable Liquibase SQL changesets for schema changes. Hibernate must only validate the schema.
- Never commit `.env`, production credentials, tokens, or production data. Throwaway passwords of local containers bound to 127.0.0.1 may live in `compose.yaml` and `application-dev.yaml`.
- Configuration: shared settings in `application.yaml`, local ones in `application-dev.yaml`, production in `application-prod.yaml` where every address and secret is a `${ENV_VAR}` without a default. Read docs/deployment.md before changing deployment files.
- Authentication: Keycloak issues tokens, the backend is a stateless OAuth 2.0 resource server. Read docs/authentication.md before touching security, the realm file or user accounts. Reference users by `user_account.id`, never by token `sub`.
- The realm lives in `keycloak/realm-sizylift.json`; keep secrets out of it (use `${ENV_VAR}` placeholders).
- Cloud tasks already have isolated checkouts. Do not create a Git worktree unless the user requests one.
- Data publication contract: docs/publication.md. Payload fixtures in `src/test/resources/publication` and the catalog fixture are shared with the app; change them on both sides together.
- Telemetry: OpenTelemetry over OTLP (docs/observability.md). Name custom meters `sizylift.*` with low-cardinality tags; never put user ids, e-mails or payload data into tags or log messages.
