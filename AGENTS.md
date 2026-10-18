# SizyLift backend

- Use JDK 25 and the checked-in Gradle Wrapper. Read README.md and docs/architecture.md.
- Run `./gradlew build` before completing code changes. Tests require Docker and real PostgreSQL; do not skip them or substitute H2.
- Use a conventional layered architecture: controller → service → repository. Controllers handle HTTP and DTOs; services own business logic and transaction boundaries; repositories access data. Group classes by feature, with controller, service, repository, dto, and entity packages inside each feature as needed. Do not create empty future modules.
- Add immutable Liquibase SQL changesets for schema changes. Hibernate must only validate the schema.
- Never commit `.env`, credentials, tokens, or production data.
- Authentication: Keycloak issues tokens, the backend is a stateless OAuth 2.0 resource server. Read docs/authentication.md before touching security, the realm file or user accounts. Reference users by `user_account.id`, never by token `sub`.
- The realm lives in `keycloak/realm-sizylift.json`; keep secrets out of it (use `${ENV_VAR}` placeholders).
- Cloud tasks already have isolated checkouts. Do not create a Git worktree unless the user requests one.
