---
applyTo: "services/**/*.go,services/**/go.mod"
---

- Reuse `services/go-shared` packages for auth, config, health, observability, Kafka, and resilient HTTP behavior before adding local duplicates.
- Keep the standard Go operational endpoints and conventions intact: `/health`, `/health/live`, `/health/ready`, `/metrics`, structured logs, and OTEL/Prometheus instrumentation.
- Validate each Go module from its own directory with `go test -race ./...` locally and `go build ./...`; if `services/go-shared` changes, expect every Go module to need revalidation.
- When a Go module name differs from its deploy key, update `.github/workflows/ci.yml` and Helm values mappings together.
- Treat Kafka topics, webhook payloads, and CDC/outbox flows as integration contracts; check `contracts/`, service READMEs, and downstream consumers before changing message shapes.
