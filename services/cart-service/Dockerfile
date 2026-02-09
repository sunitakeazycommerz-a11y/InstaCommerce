FROM gradle:8.7-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle clean build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -g 1001 app && adduser -u 1001 -G app -D app
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown -R app:app /app

USER app
ENV SERVER_PORT=8088
EXPOSE 8088
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8088/actuator/health/liveness || exit 1

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseZGC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
