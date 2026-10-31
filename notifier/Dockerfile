# Build from the repository root: docker build -f notifier/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew :notifier:bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
ADD --chmod=644 --checksum=sha256:e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149 \
    https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.33.1/opentelemetry-javaagent-2.33.1.jar \
    opentelemetry-javaagent.jar
COPY --from=build /src/notifier/build/libs/notifier.jar app.jar
# Traces go to OTEL_EXPORTER_OTLP_ENDPOINT; metrics come from Micrometer/Prometheus instead.
ENV OTEL_SERVICE_NAME=notifier OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none
USER 10001
EXPOSE 8082
ENTRYPOINT ["java", "-javaagent:opentelemetry-javaagent.jar", "-Xmx256m", "-jar", "app.jar"]
