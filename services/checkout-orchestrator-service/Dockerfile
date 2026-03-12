FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk upgrade --no-cache && addgroup -g 1001 app && adduser -u 1001 -G app -D app
WORKDIR /app
COPY build/libs/*.jar app.jar
RUN chown -R app:app /app

USER app
ENV SERVER_PORT=8089
EXPOSE 8089
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8089/actuator/health/liveness || exit 1

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseZGC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
