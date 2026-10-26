# Production Keycloak: build-time options are baked in once, so the container starts with --optimized.
# Build context is the repository root (see deploy/compose.prod.yaml).
FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true
COPY --chown=1000:0 --chmod=0644 keycloak-provider/build/libs/sizy-keycloak-provider.jar /opt/keycloak/providers/
COPY --chown=1000:0 keycloak/themes/sizy /opt/keycloak/themes/sizy
RUN touch -m --date=@1760000000 /opt/keycloak/providers/sizy-keycloak-provider.jar && /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244
COPY --from=builder --chown=1000:0 /opt/keycloak/ /opt/keycloak/
RUN mkdir -p /opt/keycloak/data/import
COPY --chown=1000:0 --chmod=0644 keycloak/realm-sizylift.json /opt/keycloak/data/import/realm-sizylift.json
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
