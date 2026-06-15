FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY services/api/ ./
RUN chmod +x gradlew && ./gradlew clean test buildFatJar --no-daemon

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/* \
  && useradd --system --uid 10001 voicehall
WORKDIR /app
COPY --from=build /src/build/libs/voice-hall-ops-api-all.jar /app/api.jar
USER voicehall
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/api.jar"]
