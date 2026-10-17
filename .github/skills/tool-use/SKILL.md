---
name: tool-use
description: Disciplined, low-token, high-leverage tool usage using LSP symbol navigation, targeted slicing, and narrow validation loops.
---

# Tool Use Skill: High-Leverage Monorepo Navigation

Use this skill whenever navigating, exploring, debugging, or verifying code across the InstaCommerce monorepo to minimize token expenditure and maximize investigation speed.

---

## 1. The Token Conservation Pyramid

In a 30-microservice polyglot repository, naive tool usage (reading entire files or running broad recursive searches) consumes hundreds of thousands of tokens and causes model context exhaustion.

Always move down the pyramid in order of efficiency:

```
          ┌───────────────────────────┐
          │  1. Authoritative Docs    │  GLOSSARY.md, AGENTS.md,
          │     & Manifests           │  settings.gradle.kts, go.mod
          ├───────────────────────────┤
          │  2. LSP Symbol Outlines   │  ./scripts/lsp-walkthrough outline
          │     & Definition Jump     │  ./scripts/lsp-walkthrough def
          ├───────────────────────────┤
          │  3. Targeted Grep / Search│  git grep -n "<symbol>" -- "services/<svc>/**"
          │     (Scoped to Package)   │
          ├───────────────────────────┤
          │  4. Bounded File Slices   │  view_file with StartLine & EndLine (50-150 lines)
          ├───────────────────────────┤
          │  5. Narrow Test Execution │  ./gradlew :services:<svc>:test --tests "<Class>"
          └───────────────────────────┘
```

---

## 2. LSP Symbol Navigation Guide (`scripts/lsp-walkthrough`)

InstaCommerce bundles a unified CLI wrapping `jdtls` (Java), `gopls` (Go), and `pyright` (Python):

```bash
# 1. Verify language server health
./scripts/lsp-walkthrough status

# 2. Extract structural outline (classes, structs, functions, routes, line numbers)
# This takes ~200 tokens instead of 10,000 tokens for the full file!
./scripts/lsp-walkthrough outline services/order-service/src/main/java/com/instacommerce/order/service/OrderService.java

# 3. Jump to symbol definition
./scripts/lsp-walkthrough def services/order-service/src/main/java/com/instacommerce/order/service/OrderService.java 45 18
```

---

## 3. Bounded File Reading Rules

When using the `view_file` tool:
1. **Rule of 150**: Never read more than 150 lines in a single call unless modifying a contiguous block.
2. **Find Seam First**:
   - Locate the function or class name line number using `git grep -n "class OrderService" services/order-service/` or the LSP outline.
   - Set `StartLine` 5 lines before the method declaration and `EndLine` at the method closing brace.
3. **Never Cat Files**: Avoid running `cat <large-file>` in terminal commands. Use `head -n 50` or `view_file` with explicit ranges.

---

## 4. Scoped Search & Ripgrep Patterns

```bash
# Good: Target specific service directory and file extension
git grep -n "outbox_events" -- "services/order-service/**"

# Good: Search contracts for event schemas
git grep -n "OrderPlaced" -- "contracts/**"

# Bad: Broad search dumping thousands of matches
# grep -rn "order" .
```

---

## 5. Narrow Validation Loops

Always validate changes with the narrowest possible command first:

| Stack | Narrowest Validation Command (Fastest) | Escalate To (Only When Cross-Cutting) |
|---|---|---|
| **Java** | `./gradlew :services:<svc>:test --tests "*<TestClass>.<method>*"` | `./gradlew :services:<svc>:test` |
| **Go** | `cd services/<svc> && go test -race ./pkg/<pkg> -run '^TestName$'` | `cd services/<svc> && go test -race ./...` |
| **Python** | `cd services/<svc> && pytest path/to/test.py::test_fn -v` | `cd services/<svc> && pytest -v` |
| **dbt** | `cd data-platform/dbt && dbt test --select <model_name>` | `cd data-platform/dbt && dbt test` |
| **Contracts**| `./gradlew :contracts:build` | `./gradlew build -x test` |
| **Monorepo** | `./scripts/check-monorepo-consistency.sh` | Full CI Run |

