# Build from the repository root: docker build -f ledger-service/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew :ledger-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/ledger-service/build/libs/ledger-service.jar app.jar
USER 10001
EXPOSE 8081 9090
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
