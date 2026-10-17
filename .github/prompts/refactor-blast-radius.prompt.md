---
name: refactor-blast-radius
description: Analyze references, callers, contracts, and blast radius before modifying a shared model, interface, or event.
---

# Pre-Refactoring Blast Radius & Reference Analysis Prompt

Analyze the blast radius of modifying `$TARGET_SYMBOL` in `$TARGET_FILE`.

## Analysis Protocol:
1. **LSP Reference Search:**
   - Execute `./scripts/lsp-walkthrough refs $TARGET_FILE $LINE $COL` (or `gopls references`) to find all direct usages across the repository.
2. **Contract & Schema Compatibility:**
   - Check if the symbol maps to protobuf messages (`contracts/src/main/proto/`) or JSON schemas (`contracts/src/main/resources/schemas/`).
   - Is the proposed change additive (`v1`) or breaking (`v2`)?
3. **Database Migration Impact:**
   - Does this change require a Flyway migration?
   - Check if columns have `DEFAULT` or `NULL` constraints to preserve zero-downtime rolling upgrades.
4. **CI & Deployment Blast Radius:**
   - Trace path-filters in `.github/workflows/ci.yml`.
   - Identify which services will be rebuilt and redeployed.

