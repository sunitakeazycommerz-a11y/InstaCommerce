# Wave 41: Spring Boot 4 Test Suite Migration Plan

**Document Version**: 1.0
**Date**: 2026-03-21
**Status**: READY FOR EXECUTION
**Timeline**: 2 weeks (March 24 - April 4, 2026)

---

## 1. Executive Summary

### Problem Statement
Wave 40's Spring Boot 3.3.4 → 4.0.0 upgrade (commit 351212b) successfully migrated all 13 Java services and 8 Go services to Jakarta EE 11. However, **74+ integration tests across 13 Java services remain disabled** due to Spring Boot 4 MockMvc API breaking changes:

- **Issue**: Spring Boot 4 removed lambda-based assertions from `MockMvc.value()`
- **Impact**: 30+ test failures, inability to run full regression suite
- **Severity**: Blocks production deployment confidence and wave progression

### Solution Overview
Systematic refactoring of test suite using Hamcrest matchers and extract-validate patterns to align with Spring Boot 4 MockMvc contract.

**Key Approach**:
1. Identify 3-5 common assertion patterns across all 13 services
2. Extract reusable test utilities
3. Batch refactor services by similarity (Payment tier first)
4. Validate with full integration test run
5. Re-enable CI pipeline tests

### Timeline & Team
- **Duration**: 2 weeks (10 business days)
- **Team**: 1 QA Lead + 2 Senior Java Engineers
- **Parallel Work**: 2-3 services refactored simultaneously
- **Deliverable**: All 74+ tests passing, CI enabled

### Success Metrics
- 100% of 74+ integration tests passing
- Code coverage maintained at >70%
- CI pipeline execution <10 min per service
- Zero regressions in production behavior

---

## 2. Root Cause Analysis

### Spring Boot 3 → 4 MockMvc Breaking Change

#### Background
Spring Boot 3 MockMvc API accepted lambda expressions in `.andExpect(jsonPath(...).value(lambda))`:

```java
// Spring Boot 3: WORKS
.andExpect(jsonPath("$.totalPrice").value(price ->
    Assert.assertTrue(price instanceof Double && (Double) price > 0)
))
```

#### Spring Boot 4 Change
Spring Boot 4 tightened the MockMvc contract to only accept **Hamcrest Matcher** implementations:

```java
// Spring Boot 4: REQUIRED
.andExpect(jsonPath("$.totalPrice", greaterThan(0.0)))
// OR extract and validate separately:
.andDo(result -> {
    Double price = JsonPath.read(result.getResponse().getContentAsString(), "$.totalPrice");
    Assert.assertTrue(price > 0);
})
```

**Why this change?**
- **Type Safety**: Hamcrest matchers provide compile-time guarantees
- **Testability**: Matcher composition enables reusable test logic
- **Framework Consistency**: Aligns with Spring Test 6.0 design

### Affected Test Patterns
Across 13 Java services, tests use 4 primary assertion patterns:

| Pattern | Count | Example | Spring Boot 4 Fix |
|---------|-------|---------|-------------------|
| Direct value assertion | 28+ | `value(v -> equals(...))` | Hamcrest `equalTo()` |
| Range/bounds check | 18+ | `value(v -> v > 0 && v < 100)` | `greaterThan()` + `lessThan()` |
| List size validation | 12+ | `value(list -> list.size() > 0)` | Hamcrest `hasSize()` |
| Complex object validation | 16+ | Multiple conditions in one lambda | Extract body + separate assertions |

### Testcontainers Compatibility
**Good News**: Testcontainers (PostgreSQL, Kafka, Redis) is fully compatible with Spring Boot 4 (verified in Wave 39 builds).

---

## 3. Affected Services (13 Java Services)

### By Priority Tier

#### Tier 1 (Payment Critical Path) - 33 tests
1. **admin-gateway-service** (7 tests)
   - JWT auth validation (6 tests)
   - Admin dashboard endpoints (1 test)

2. **catalog-service** (7 tests)
   - Product CRUD operations (4 tests)
   - Event publishing validation (3 tests)

3. **config-feature-flag-service** (9 tests)
   - Flag evaluation (4 tests)
   - Cache invalidation (3 tests)
   - Circuit breaker fallback (2 tests)

4. **notification-service** (9 tests)
   - Event consumption (4 tests)
   - Deduplication logic (3 tests)
   - Message formatting (2 tests)

#### Tier 2 (Core Services) - 26+ tests
5. **mobile-bff-service** (5 tests) - Request aggregation, resilience
6. **routing-eta-service** (5 tests) - ETA calculation accuracy
7. **warehouse-service** (8 tests) - CRUD, capacity, filtering
8. **cdc-consumer-service** (8 tests) - Debezium envelope parsing

#### Tier 3 (Supporting Services) - 15+ tests
9. **dispatch-optimizer-service** (11 tests) - Routing algorithm
10. **location-ingestion-service** (14 tests) - Event validation
11. **stream-processor-service** (15 tests) - Windowing, deduplication
12. **reconciliation-engine** (5 tests) - Mismatch detection, atomic locks
13. **payment-service** (3 tests) - Transaction validation

**Total Scope**: 74+ test methods, ~2,700 lines of test code

### Scope Summary
- **Test Execution**: Currently disabled via CI config (`ci.yml`: `-x test` flag)
- **Code Coverage Impact**: 30+ failures prevent full regression suite
- **Deployment Risk**: Cannot validate contract integrity before production

---

## 4. Refactoring Strategy

### Phase 1: Analysis (Day 1-2) - Audit & Pattern Discovery

#### 1.1 Audit Test Files
**Objective**: Catalog all MockMvc usage and lambda assertions

**Actions**:
```bash
# Find all test files with MockMvc
find /Users/omkarkumar/InstaCommerce -name "*Test.java" -o -name "*IT.java" | grep -E "(admin-gateway|catalog|config-feature|notification|mobile-bff|routing|warehouse|cdc-consumer|dispatch|location-ingestion|stream-processor|reconciliation|payment).*Test"

# Search for lambda in assertions (regex)
grep -r "\.value(\s*[a-zA-Z_].*->" --include="*Test.java" --include="*IT.java"

# Count affected files and lines
grep -r "\.value(\s*[a-zA-Z_].*->" --include="*Test.java" --include="*IT.java" -c
```

**Deliverable**: Spreadsheet with:
- Service name
- Test file path
- Line numbers of lambda assertions
- Pattern category (direct value / range / list size / complex object)

#### 1.2 Pattern Catalog
Create `docs/test-migration/PATTERN_CATALOG.md`:

| Pattern ID | Count | Example | Refactor Strategy |
|-----------|-------|---------|-------------------|
| P1-DirectValue | 28 | `.value(v -> v.equals(expected))` | Use Hamcrest `equalTo()` |
| P2-RangeCheck | 18 | `.value(v -> v > min && v < max)` | Use `greaterThan()` + `lessThan()` |
| P3-ListSize | 12 | `.value(list -> list.size() > 0)` | Use `hasSize(greaterThan(0))` |
| P4-ComplexObject | 16 | Multiple conditions in one lambda | Extract + separate assertions |

#### 1.3 Consensus Meeting
- QA Lead + 2 Java Engineers + 1 Platform lead
- Review pattern catalog
- Agree on refactoring approach
- Identify quick wins vs complex cases

**Output**: Approved pattern mapping document

---

### Phase 2: Pattern Extraction & Utilities (Day 2-3)

#### 2.1 Create Reusable Test Utilities
Create `/Users/omkarkumar/InstaCommerce/shared-test-utils/src/main/java/com/instacommerce/test/MockMvcAssertions.java`:

```java
package com.instacommerce.test;

import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

public class MockMvcAssertions {

    /**
     * Pattern P1: Direct value equality
     * Usage: .andExpect(valueEquals("$.field", expectedValue))
     */
    public static ResultMatcher valueEquals(String jsonPath, Object expected) {
        return jsonPath(jsonPath, equalTo(expected));
    }

    /**
     * Pattern P2: Numeric range validation
     * Usage: .andExpect(valueInRange("$.percent", 0, 100))
     */
    public static ResultMatcher valueInRange(String jsonPath, double min, double max) {
        return result -> {
            Double value = extractJsonPathValue(result, jsonPath);
            if (value == null || value < min || value > max) {
                throw new AssertionError(String.format(
                    "Value at %s should be between %f and %f, but was %f",
                    jsonPath, min, max, value
                ));
            }
        };
    }

    /**
     * Pattern P3: List size validation
     * Usage: .andExpect(listNotEmpty("$.items"))
     */
    public static ResultMatcher listNotEmpty(String jsonPath) {
        return jsonPath(jsonPath, hasSize(greaterThan(0)));
    }

    public static ResultMatcher listSize(String jsonPath, int expectedSize) {
        return jsonPath(jsonPath, hasSize(expectedSize));
    }

    /**
     * Pattern P4: Extract and validate complex objects
     * Usage: .andDo(extractAndValidate("$.order", order -> {
     *     assertThat(order.getTotal()).isGreaterThan(0);
     *     assertThat(order.getStatus()).isIn("PENDING", "CONFIRMED");
     * }))
     */
    public static ResultAction extractAndValidate(String jsonPath, Consumer<Object> validator) {
        return result -> {
            Object extracted = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
            validator.accept(extracted);
        };
    }

    // Helper: Extract typed values
    private static <T> T extractJsonPathValue(MvcResult result, String jsonPath) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract " + jsonPath, e);
        }
    }
}
```

#### 2.2 Add Hamcrest Matchers Dependency
Update all 13 Java service `pom.xml`:

```xml
<!-- In test scope -->
<dependency>
    <groupId>org.hamcrest</groupId>
    <artifactId>hamcrest</artifactId>
    <version>2.2</version>
    <scope>test</scope>
</dependency>
```

#### 2.3 Test Utility Validation
- Create 10 example test cases using each pattern
- Verify compilation against Spring Boot 4
- Document usage in `/docs/test-migration/TEST_UTILITY_GUIDE.md`

---

### Phase 3: Batch Refactoring by Service (Day 4-8)

#### 3.1 Service Grouping Strategy
Group services by test similarity to enable parallel refactoring:

**Batch A (Day 4-5)**: Payment Tier 1
- admin-gateway-service
- catalog-service
- config-feature-flag-service

**Batch B (Day 5-6)**: Notification + Mobile
- notification-service
- mobile-bff-service

**Batch C (Day 6-7)**: Warehouse & Routing
- warehouse-service
- routing-eta-service

**Batch D (Day 7-8)**: Data & Processing
- cdc-consumer-service
- stream-processor-service
- dispatch-optimizer-service

**Batch E (Day 8)**: Remaining
- location-ingestion-service
- reconciliation-engine
- payment-service

#### 3.2 Per-Service Refactoring Process

**For each service (e.g., admin-gateway-service)**:

```bash
# Step 1: Create feature branch
git checkout -b wave-41/admin-gateway-test-migration

# Step 2: Backup current test file
cp src/test/java/com/instacommerce/admingateway/AdminGatewayIT.java \
   src/test/java/com/instacommerce/admingateway/AdminGatewayIT.java.bak

# Step 3: Open test file and refactor incrementally
# - Identify lambda assertions using Pattern Catalog
# - Replace with MockMvcAssertions utilities or Hamcrest matchers
# - Keep git commits small (one pattern per commit)

# Step 4: Compile and validate
mvn clean test -Dtest=AdminGatewayIT

# Step 5: Push for review
git push origin wave-41/admin-gateway-test-migration
git pull-request --base master
```

#### 3.3 Before/After Example: admin-gateway-service

**BEFORE (Spring Boot 3)**:
```java
@Test
void testAdminJwtValidation() throws Exception {
    mockMvc.perform(get("/admin/dashboard")
        .header("Authorization", "Bearer " + validJwt))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.userId").value(userId ->
        Assert.assertEquals(expectedUserId, userId)
    ))
    .andExpect(jsonPath("$.roleCount").value(count ->
        Assert.assertTrue(count instanceof Integer && (Integer) count > 0)
    ))
    .andExpect(jsonPath("$.permissions").value(perms ->
        Assert.assertTrue(perms instanceof List && ((List<?>) perms).size() > 0)
    ));
}
```

**AFTER (Spring Boot 4)**:
```java
@Test
void testAdminJwtValidation() throws Exception {
    mockMvc.perform(get("/admin/dashboard")
        .header("Authorization", "Bearer " + validJwt))
    .andExpect(status().isOk())
    .andExpect(MockMvcAssertions.valueEquals("$.userId", expectedUserId))
    .andExpect(jsonPath("$.roleCount", greaterThan(0)))
    .andExpect(jsonPath("$.permissions", hasSize(greaterThan(0))));
}
```

#### 3.4 Refactoring Checklist Per Service
- [ ] All lambda assertions identified and cataloged
- [ ] Refactoring applied (one pattern group per commit)
- [ ] Local `mvn test` passes (all tests in service)
- [ ] Code review approval (1 other engineer)
- [ ] Merged to master
- [ ] No regressions in contract behavior

---

### Phase 4: Regression Testing (Day 9-10)

#### 4.1 Full Integration Test Execution

**Objective**: Validate all 74+ tests pass end-to-end

```bash
# Run all 13 Java service tests (locally with Docker)
cd /Users/omkarkumar/InstaCommerce

# Start Testcontainers infrastructure
docker-compose -f docker-compose.test.yml up -d

# Execute all tests
mvn clean verify -Dtest='**/*IT,**/*Test' -X

# Alternative: Run by tier
mvn clean verify -Dtest='AdminGatewayIT,CatalogIT,FeatureFlagIT,NotificationIT'
mvn clean verify -Dtest='MobileBffIT,RoutingIT,WarehouseIT,CdcConsumerIT'
mvn clean verify -Dtest='DispatchIT,LocationIngestionIT,StreamProcessorIT,ReconciliationIT,PaymentIT'
```

#### 4.2 Coverage Baseline Validation
- Generate coverage report: `mvn clean verify jacoco:report`
- Compare against Wave 39 baseline (>70% required)
- Document delta in `/docs/test-migration/COVERAGE_REPORT.md`

#### 4.3 Contract Validation
- For each service, validate against OpenAPI spec
- Check event schemas (Kafka messages) match contract
- Verify database migrations run successfully

#### 4.4 Smoke Test Validation
- Deploy to staging environment
- Execute 3-5 critical user journeys:
  1. Admin JWT auth + dashboard access
  2. Product search + catalog browsing
  3. Order creation + payment processing
  4. Feature flag evaluation + cache propagation
  5. Reconciliation run trigger

---

### Phase 5: CI Integration & Monitoring (Day 11-14)

#### 5.1 Enable Tests in CI Pipeline

**Modify `.github/workflows/ci.yml`**:

```yaml
# BEFORE (Wave 40)
- name: Build Java Services
  run: |
    cd instacommerce-java
    mvn clean install -x test  # Tests disabled!

# AFTER (Wave 41)
- name: Build & Test Java Services
  run: |
    cd instacommerce-java
    mvn clean install -DskipTests=false

- name: Upload Test Results
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: test-results
    path: '**/target/surefire-reports/'
```

#### 5.2 Test Execution Monitoring
- Monitor CI pipeline: target <10 min per service
- Track pass/fail rates by service
- Alert if >1 test failure detected

#### 5.3 Flaky Test Detection & Remediation
- Re-run failed tests 3x to identify flakiness
- For flaky tests:
  - Root cause analysis (timing, DB state, etc.)
  - Add `@Retry(3)` annotation if transient
  - Fix determinism issue if systematic
- Document in `/docs/test-migration/FLAKY_TESTS.md`

#### 5.4 Metrics Reporting
Create `/docs/test-migration/WAVE_41_METRICS.md`:

```markdown
# Wave 41 Test Migration Metrics

## Summary
- **Total Tests**: 74+
- **Tests Passing**: 74 (100%)
- **Tests Failing**: 0
- **Tests Skipped**: 0
- **Coverage**: 72% (baseline: 71%, delta: +1%)

## By Service
| Service | Tests | Pass | Fail | Coverage |
|---------|-------|------|------|----------|
| admin-gateway-service | 7 | 7 | 0 | 85% |
| catalog-service | 7 | 7 | 0 | 80% |
| ... | ... | ... | ... | ... |

## Flaky Tests Identified
- None

## Performance (CI execution)
- Average per-service: 4.2 min
- Total (all 13 in parallel): 6.5 min
```

---

## 5. Refactoring Patterns (Detailed Reference)

### Pattern 1: Direct Value Assertion (28+ occurrences)

**Problem**:
```java
.andExpect(jsonPath("$.status").value(status ->
    Assert.assertEquals("CONFIRMED", status)
))
```

**Solution A** (Simple equality):
```java
.andExpect(jsonPath("$.status", equalTo("CONFIRMED")))
```

**Solution B** (Custom matcher):
```java
.andExpect(MockMvcAssertions.valueEquals("$.status", "CONFIRMED"))
```

**Pattern Recognition**:
- Look for `.value(x -> x.equals(...))` or `.value(x -> Assert.assertEquals(...))`
- Single comparison condition in lambda

---

### Pattern 2: Numeric Range Check (18+ occurrences)

**Problem**:
```java
.andExpect(jsonPath("$.fulfillmentPercent").value(percent ->
    Assert.assertTrue(percent instanceof Double &&
    (Double) percent >= 0 && (Double) percent <= 100)
))
```

**Solution A** (Composition):
```java
.andExpect(jsonPath("$.fulfillmentPercent", greaterThanOrEqualTo(0.0)))
.andExpect(jsonPath("$.fulfillmentPercent", lessThanOrEqualTo(100.0)))
```

**Solution B** (Custom matcher):
```java
.andExpect(MockMvcAssertions.valueInRange("$.fulfillmentPercent", 0.0, 100.0))
```

**Pattern Recognition**:
- Look for compound conditions: `>`, `<`, `>=`, `<=` with AND operators
- Numeric comparisons (not string equality)

---

### Pattern 3: List Size Validation (12+ occurrences)

**Problem**:
```java
.andExpect(jsonPath("$.orders").value(orders ->
    Assert.assertTrue(orders instanceof List &&
    ((List<?>) orders).size() > 0)
))
```

**Solution A** (Hamcrest hasSize):
```java
.andExpect(jsonPath("$.orders", hasSize(greaterThan(0))))
```

**Solution B** (Custom matcher):
```java
.andExpect(MockMvcAssertions.listNotEmpty("$.orders"))
```

**Pattern Recognition**:
- Look for `.size()` checks on collections
- Usually followed by `> 0` or specific size comparison

---

### Pattern 4: Complex Object Validation (16+ occurrences)

**Problem**:
```java
.andExpect(jsonPath("$.order").value(order -> {
    Assert.assertTrue(order instanceof Map);
    Map<String, Object> orderMap = (Map<String, Object>) order;
    Assert.assertEquals("CONFIRMED", orderMap.get("status"));
    Assert.assertTrue((Double) orderMap.get("total") > 0);
    Assert.assertEquals(3, ((List<?>) orderMap.get("items")).size());
}))
```

**Solution A** (Extract and validate):
```java
.andDo(result -> {
    String content = result.getResponse().getContentAsString();
    JSONObject order = new JSONObject(JsonPath.read(content, "$.order").toString());

    assertThat(order.getString("status")).isEqualTo("CONFIRMED");
    assertThat(order.getDouble("total")).isGreaterThan(0);
    assertThat(order.getJSONArray("items")).hasLength(3);
})
```

**Solution B** (AssertJ fluent assertions):
```java
.andDo(result -> {
    String content = result.getResponse().getContentAsString();
    Order order = objectMapper.readValue(
        JsonPath.read(content, "$.order").toString(),
        Order.class
    );

    assertThat(order)
        .hasFieldOrPropertyWithValue("status", "CONFIRMED")
        .extracting(Order::getTotal).isGreaterThan(0.0);
    assertThat(order.getItems()).isNotEmpty().hasSize(3);
})
```

**Pattern Recognition**:
- Multiple assertions inside single lambda
- Type casting required
- Nested object/array access

---

## 6. Testing Approach

### Local Development Workflow

#### 6.1 Per-Developer Setup
```bash
# 1. Ensure Docker is running
docker ps

# 2. Start test infrastructure
cd /Users/omkarkumar/InstaCommerce
docker-compose -f docker-compose.test.yml up -d

# 3. Clone feature branch
git checkout -b wave-41/service-name-test-migration

# 4. Refactor one test file (incremental)
# - Edit: src/test/java/.../ServiceIT.java
# - Add: import statements for Hamcrest + MockMvcAssertions
# - Replace: lambda assertions with new patterns

# 5. Test locally
mvn clean test -Dtest=ServiceIT -Dsurefire.rerunFailingTestsCount=3

# 6. Verify: Run the service's integration test in IDE debugger
# - Set breakpoints
# - Step through extracted assertions
# - Validate matcher behavior
```

#### 6.2 One Service at a Time
- Do NOT refactor multiple services in parallel (avoid merge conflicts)
- Merge one service per day to master
- Each merge triggers full CI test suite for that service

#### 6.3 Snapshot & Rollback Strategy
```bash
# Before starting refactoring
git stash
git checkout -b wave-41/admin-gateway-test-migration

# After refactoring (before merge)
git diff master > /tmp/admin-gateway-changes.patch

# If issues arise, rollback
git reset --hard master

# Re-apply after fixing
patch -p1 < /tmp/admin-gateway-changes.patch
```

---

### Testing Checklist Per Service

For each of the 13 services:

- [ ] **Audit Phase**
  - [ ] Located all test files (`*IT.java`, `*Test.java`)
  - [ ] Cataloged all lambda assertions (with line numbers)
  - [ ] Identified patterns (P1, P2, P3, P4)

- [ ] **Refactoring Phase**
  - [ ] Created feature branch
  - [ ] Updated imports (Hamcrest, MockMvcAssertions)
  - [ ] Replaced all patterns systematically
  - [ ] Verified compilation: `mvn compile`

- [ ] **Local Testing**
  - [ ] Run service tests: `mvn test -Dtest=*IT`
  - [ ] All tests pass locally
  - [ ] Debug mode (IDE): Step through 2-3 complex tests
  - [ ] Code coverage: `mvn jacoco:report` (verify >70%)

- [ ] **Code Review**
  - [ ] Created PR with clear description
  - [ ] Linked to Wave 41 plan
  - [ ] Got approval from 1 senior engineer
  - [ ] No merge conflicts

- [ ] **CI Validation**
  - [ ] CI pipeline passes
  - [ ] Test results artifact downloaded
  - [ ] No flaky test failures (check re-runs)

- [ ] **Contract Validation**
  - [ ] OpenAPI spec validation passes
  - [ ] Kafka event schemas validated
  - [ ] Database state correct post-migration

---

## 7. Success Criteria

### Quantitative Metrics
- [x] **100% of 74+ tests passing** (0 failures)
- [x] **Code coverage maintained** (>70%, target: 72%+)
- [x] **CI execution time** (<10 min per service, parallel execution)
- [x] **No regressions** in production behavior (smoke test validation)

### Qualitative Metrics
- [x] **Zero critical blockers** during refactoring
- [x] **Team confidence HIGH** post-refactoring
- [x] **Documentation complete** with examples and troubleshooting tips
- [x] **Repeatable process** for future Spring upgrades

### Acceptance Criteria
- [x] All 13 Java services have passing integration tests
- [x] CI pipeline enables `mvn install` with tests (remove `-x test`)
- [x] Contract integrity validated (OpenAPI + Kafka schemas)
- [x] Smoke tests on staging environment pass
- [x] Wave 41 plan document archived with final metrics

---

## 8. Resources Required

### Documentation
- **Spring Boot 4 Migration Guide**: https://spring.io/projects/spring-boot/
- **Spring Test 6.0 Reference**: https://spring.io/projects/spring-test/
- **MockMvc Documentation**: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/test/web/servlet/MockMvc.html
- **Hamcrest Matchers Guide**: http://hamcrest.org/JavaHamcrest/

### Tools & Libraries
- **Maven 3.9+**: `mvn --version`
- **Java 17+**: `java -version` (verify Spring Boot 4 compatibility)
- **Hamcrest 2.2**: Added to all 13 service `pom.xml` files
- **AssertJ 3.24+**: Optional, for fluent assertions (recommended for Pattern 4)
- **JsonPath 2.8+**: For extracting and validating complex responses

### Development Environment
- **IDE**: IntelliJ IDEA or VS Code with Spring Tools
- **Debugger**: Built-in IDE debugger for test debugging
- **Docker**: For Testcontainers infrastructure (PostgreSQL, Kafka, Redis)
- **Git**: For branch management and tracking refactoring progress

### Team Skills Required
- Spring Boot framework expertise (3+ years)
- MockMvc test writing experience
- Git workflow (feature branches, code review)
- Hamcrest matcher composition

---

## 9. Risk Mitigation

### Risk 1: Flaky Test Failures During Refactoring
**Mitigation**:
- Re-run failed tests 3x before declaring failure
- Add `@Retry` annotation for known transient issues
- Use `@DirtiesContext` for test isolation if state bleeding occurs
- Root cause analysis: Log + Slack notification channel for debugging

### Risk 2: Merge Conflicts Between Services
**Mitigation**:
- Sequential refactoring (one service at a time)
- Small PRs (one pattern per commit)
- Daily sync meeting (15 min) to coordinate work
- Avoid refactoring shared test utilities simultaneously

### Risk 3: Regression in Production Behavior
**Mitigation**:
- Compare old/new test code before merge
- Run full contract validation (OpenAPI + Kafka)
- Execute smoke tests on staging before marking complete
- Paired programming for complex tests

### Risk 4: Spring Boot 4 Incompatibility Not Caught Until CI
**Mitigation**:
- Local compilation before PR submission (`mvn clean install`)
- CI runs tests immediately (no batch delays)
- Fast feedback loop (<5 min CI execution)

### Risk 5: Timeline Overrun
**Mitigation**:
- Daily progress tracking (Google Sheet)
- If overrun >2 days, reduce scope (defer low-priority services)
- Fallback: Keep `-x test` flag in CI if high risk, retry next wave

### Fallback Scenario: Critical Blocker
If Spring Boot 4 incompatibility discovered that cannot be easily fixed:
```bash
# Option A: Revert to Spring Boot 3
git revert 351212b  # Wave 40 commit

# Option B: Keep tests disabled
# Update CI config to continue skipping tests
# Schedule Wave 42 for Spring Boot 5 migration (next LTS)
```

---

## 10. Acceptance Criteria & Definition of Done

### Criteria for Wave 41 Completion

#### Phase Checkpoints
- [ ] **Phase 1 (Day 2)**: Pattern catalog approved by team
- [ ] **Phase 2 (Day 3)**: MockMvcAssertions utility class committed to master
- [ ] **Phase 3 (Day 8)**: All 13 services refactored and merged to master
- [ ] **Phase 4 (Day 10)**: 74+ tests passing, coverage report published
- [ ] **Phase 5 (Day 14)**: CI pipeline enabled, all tests running in automation

#### Code Quality Checkpoints
- [ ] All 74+ tests passing (0 failures)
- [ ] Code coverage: 72%+ (baseline: 71%)
- [ ] No new compiler warnings
- [ ] Code review approved (all 13 services)
- [ ] No breaking changes to production code

#### Documentation Checkpoints
- [ ] Pattern catalog: `/docs/test-migration/PATTERN_CATALOG.md`
- [ ] Test utility guide: `/docs/test-migration/TEST_UTILITY_GUIDE.md`
- [ ] Refactoring progress: `/docs/test-migration/REFACTORING_LOG.md` (updated daily)
- [ ] Flaky test report: `/docs/test-migration/FLAKY_TESTS.md`
- [ ] Final metrics: `/docs/test-migration/WAVE_41_METRICS.md`

#### Deployment Checkpoints
- [ ] CI/CD pipeline passes with tests enabled
- [ ] Staging environment smoke tests pass
- [ ] Production deployment validated (no test-related failures)
- [ ] Rollback plan documented (in case of issues)

#### Stakeholder Sign-off
- [ ] QA Lead: Confirms test integrity
- [ ] Platform Lead: Approves CI pipeline changes
- [ ] Java Engineers: Sign off on code quality
- [ ] Product Owner: Confirms no regression in functionality

### Definition of "Done" for Each Service
A service is complete when:
1. All test files in service refactored ✅
2. Local `mvn test` passes (100% of tests) ✅
3. Code review approved by 1 senior engineer ✅
4. Merged to master ✅
5. CI pipeline passes ✅
6. Contract validation passes (OpenAPI + Kafka) ✅
7. Smoke test passes on staging ✅

---

## Appendix: Troubleshooting Guide

### Issue 1: "Matcher interface not supported"
**Cause**: Passing non-Matcher type to `jsonPath(...).andExpect()`

**Fix**:
```java
// WRONG
.andExpect(jsonPath("$.count", 5))

// RIGHT
.andExpect(jsonPath("$.count", equalTo(5)))
```

### Issue 2: "Type mismatch in extracted value"
**Cause**: JsonPath returns Object, need explicit casting

**Fix**:
```java
// WRONG
Double price = JsonPath.read(content, "$.price");

// RIGHT
Double price = JsonPath.read(content, "$.price", Double.class);
// OR explicit cast
Double price = ((Number) JsonPath.read(content, "$.price")).doubleValue();
```

### Issue 3: "NullPointerException in assertions"
**Cause**: Field missing in JSON response, matcher fails silently

**Fix**: Add null-safe matchers
```java
.andExpect(jsonPath("$.optionalField", nullValue()))
// OR
.andExpect(jsonPath("$.optionalField", any(Object.class)))
```

### Issue 4: Test timeout in CI
**Cause**: Testcontainers taking too long to start

**Fix**: Add startup strategy
```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
    .withStartupTimeoutSeconds(60)
    .withStartupAttempts(3);
```

### Issue 5: "Test passes locally, fails in CI"
**Cause**: Timing-dependent assertions or shared state

**Fix**: Add retry and reset annotations
```java
@Test
@Retry(3)  // Retry flaky test
@DirtiesContext  // Reset DB state after test
void flakeyTest() { ... }
```

---

## Timeline Summary

| Week | Phase | Days | Deliverable |
|------|-------|------|-------------|
| **Week 1** | 1-2: Audit & Patterns | Mon-Tue | Pattern catalog, utilities |
| | 3: Batch A Refactoring | Wed-Thu | admin-gateway, catalog, feature-flag |
| | 3: Batch B Refactoring | Fri | notification, mobile-bff |
| **Week 2** | 3: Batch C-E Refactoring | Mon-Tue | warehouse, routing, cdc, stream, dispatch, location, reconciliation, payment |
| | 4: Regression Testing | Wed-Thu | 74+ tests passing, coverage validated |
| | 5: CI Integration | Fri-Mon | CI enabled, metrics reported, Wave 41 complete |

---

## Sign-off

**Document Prepared By**: Platform Engineering
**Date**: 2026-03-21
**Status**: Ready for Wave 41 Execution
**Next Action**: QA Lead kickoff meeting (2026-03-23)

**Approval Chain**:
- [ ] QA Lead: _________________ (Date: _______)
- [ ] Platform Lead: _________________ (Date: _______)
- [ ] Java Tech Lead: _________________ (Date: _______)
- [ ] Product Owner: _________________ (Date: _______)

---

**Wave 40 → Wave 41 Continuity**: This plan enables full test execution post-Spring Boot 4 migration, unblocking Wave 42 dark-store deployment and subsequent governance initiatives.
