---
applyTo: "services/**/*.java,services/**/build.gradle.kts,services/**/src/main/resources/application.yml"
---

- Java services follow a repeatable Spring Boot structure: `build.gradle.kts`, `application.yml`, and Flyway migrations under `src/main/resources/db/migration/`. Prefer extending that structure instead of introducing side-channel configuration or ad hoc schema changes.
- Reuse the platform patterns already present in these services before adding new infrastructure: Flyway for schema changes, outbox tables/events for async side effects, ShedLock for distributed scheduled jobs, and Actuator plus OTEL/Micrometer for health and telemetry.
- When a Java change affects another service, check the relevant service README and `contracts/` before changing payload shapes, URLs, or event semantics.
- Prefer focused validation like `./gradlew :services:<service-name>:test` or `bootRun`; many services already use JUnit Platform and representative services use Testcontainers with PostgreSQL for integration coverage.
- Keep runtime wiring in environment-backed `application.yml` properties rather than hardcoded constants, and preserve the standard readiness/prometheus endpoints.
