# Production Keycloak: build-time options are baked in once, so the container starts with --optimized.
# Build context is the repository root (see deploy/compose.prod.yaml).
FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY keycloak/realm-sizylift.json /opt/keycloak/data/import/realm-sizylift.json
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
