# Production image of the backend. Build the jar first, then the image:
#   ./gradlew bootJar && docker build -t sizylift-backend .
# The jar is split into layers so that dependency layers are cached between releases.
FROM eclipse-temurin:25.0.4.1_1-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f AS extract
WORKDIR /build
COPY build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25.0.4.1_1-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER 1000:1000
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
# The image has no curl; ask the readiness probe through bash (503 until ready).
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=5 CMD ["bash", "-c", \
    "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health/readiness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
