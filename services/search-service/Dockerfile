FROM eclipse-temurin:25-jre-alpine AS runtime
RUN apk upgrade --no-cache && addgroup -g 1001 appuser && adduser -u 1001 -G appuser -D appuser
WORKDIR /app
COPY build/libs/*.jar app.jar
RUN chown -R appuser:appuser /app

USER appuser
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseZGC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
