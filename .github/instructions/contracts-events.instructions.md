---
applyTo: "contracts/**/*.proto,contracts/**/*.json,contracts/**/schemas/**/*.json"
---

- `contracts/` is the canonical source for cross-service Protobuf definitions and event schemas. Keep topic naming, envelope fields, and schema ownership aligned with `contracts/README.md`.
- Additive schema changes stay within the current version; breaking changes require a new `vN` schema file and a compatibility window instead of rewriting the existing schema in place.
- Rebuild contracts with `./gradlew :contracts:build` after schema or proto edits.
- Check downstream Java services, Go consumers, and data-platform/ML consumers whenever you change a shared contract.
