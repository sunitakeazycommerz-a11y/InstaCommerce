# InstaCommerce Monorepo Verification Surfaces

When running `verify-this` in InstaCommerce, use these canonical local surfaces for falsifiable evidence:

### 1. Java / Spring Boot 4 Services
- **Single Test Method**:
  ```bash
  ./gradlew :services:<service-name>:test --tests "com.instacommerce.<package>.<TestClass>.<methodName>"
  ```
- **Targeted Class**:
  ```bash
  ./gradlew :services:<service-name>:test --tests "com.instacommerce.<package>.<TestClass>"
  ```
- **Contracts / Stubs Compilation**:
  ```bash
  ./gradlew :contracts:build
  ```

### 2. Go Services
- **Targeted Test with Race Detector**:
  ```bash
  cd services/<service-name> && go test -race -v ./pkg/<package> -run '^TestSpecificName$'
  ```
- **Package Tests**:
  ```bash
  cd services/<service-name> && go test -race ./...
  ```
- **Go Binary Compilation**:
  ```bash
  cd services/<service-name> && go build ./...
  ```

### 3. Python / FastAPI Services
- **Targeted Pytest Function**:
  ```bash
  cd services/<service-name> && pytest tests/test_file.py::test_fn -v
  ```
- **Full Suite**:
  ```bash
  cd services/<service-name> && pytest -v
  ```

### 4. Schema & Architectural Integrity
- **Monorepo Drift & Consistency**:
  ```bash
  ./scripts/check-monorepo-consistency.sh
  ```
- **Database Migrations Check**:
  Inspect `services/<service-name>/src/main/resources/db/migration/` for version continuity and outbox table creation.
