# SizyLift backend

- Use JDK 25 and the checked-in Gradle Wrapper. Read README.md and docs/architecture.md.
- Run `./gradlew build` before completing code changes. Tests require Docker and real PostgreSQL; do not skip them or substitute H2.
- Keep the modular monolith organized by feature, with layers inside each feature. Do not create empty future modules.
- Add immutable Liquibase SQL changesets for schema changes. Hibernate must only validate the schema.
- Never commit `.env`, credentials, tokens, or production data.
- Keycloak with email/password and Google/Facebook is an agreed architectural choice. Authentication is intentionally a separate task; this initialization does not implement it.
- Cloud tasks already have isolated checkouts. Do not create a Git worktree unless the user requests one.
