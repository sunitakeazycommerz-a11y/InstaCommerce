# Contract Review Process

## Purpose

This document defines the process for reviewing and approving changes to service contracts, including API schemas, event payloads, and inter-service communication protocols.

## Scope

Contract changes requiring review:
- REST API request/response schema changes
- Event schema changes in `contracts/`
- gRPC/Protobuf definition changes
- Database schema changes affecting shared data
- Breaking changes to any public interface

## Biweekly Contract Review Forum

**Schedule**: Every other Wednesday, 2 PM UTC

### Agenda

1. **Pending Change Proposals** (20 min)
   - Review submitted RFCs
   - Discuss compatibility concerns

2. **Breaking Change Assessment** (15 min)
   - Evaluate migration plans
   - Approve deprecation timelines

3. **Schema Registry Updates** (10 min)
   - New schema versions
   - Deprecated schema cleanup

4. **Open Discussion** (15 min)

## Change Types

### Additive Changes (Low Risk)

Changes that add new optional fields or endpoints without modifying existing behavior.

**Process**:
1. Create PR with schema changes
2. Update `contracts/` documentation
3. Obtain 1 approval from contract owner
4. Merge after CI passes

**Examples**:
- Adding optional field to event payload
- New API endpoint
- New enum value (if consumers ignore unknown values)

### Backward-Compatible Changes (Medium Risk)

Changes that modify existing schemas but maintain backward compatibility.

**Process**:
1. Create RFC documenting change
2. Submit for Contract Review Forum
3. Obtain approval from affected service owners
4. Coordinate deployment order with consumers

**Examples**:
- Changing field from required to optional
- Deprecating a field (with migration period)
- Adding validation rules

### Breaking Changes (High Risk)

Changes that require consumers to update their code.

**Process**:
1. Create detailed RFC with migration plan
2. Present at Contract Review Forum
3. Obtain approval from all affected teams
4. Create versioned schema (v2, v3, etc.)
5. Maintain dual-version support during migration
6. Deprecate old version after migration complete

**Examples**:
- Removing fields
- Changing field types
- Renaming fields
- Changing required/optional status to required

## Schema Versioning

### Version Format

```
contracts/src/main/resources/schemas/{domain}/{EventName}.v{N}.json
```

### Versioning Rules

1. **Minor changes**: Same version, additive only
2. **Breaking changes**: New version number
3. **Deprecation**: Mark old version, set sunset date

### Example Version History

```
OrderCreated.v1.json  # Original schema
OrderCreated.v2.json  # Added shippingAddress (breaking: required field)
OrderCreated.v1.json  # Deprecated: Sunset 2026-06-01
```

## Compatibility Matrix

| Change Type | Requires Review | Version Bump | Migration Period |
|-------------|-----------------|--------------|------------------|
| Add optional field | No | No | N/A |
| Add required field | Yes | Yes | 30 days |
| Remove field | Yes | Yes | 60 days |
| Change field type | Yes | Yes | 60 days |
| Rename field | Yes | Yes | 60 days |
| Add enum value | No* | No | N/A |
| Remove enum value | Yes | Yes | 30 days |

*Assuming consumers handle unknown enum values gracefully

## RFC Template

```markdown
# RFC: [Change Title]

## Summary
[One paragraph description of the change]

## Motivation
[Why is this change needed?]

## Affected Services
- Producer: [service-name]
- Consumers: [list of consuming services]

## Schema Changes
[Before and after schema comparison]

## Migration Plan
1. [Step 1]
2. [Step 2]
...

## Timeline
- RFC Submitted: [date]
- Review Meeting: [date]
- Implementation Start: [date]
- Migration Complete: [date]
- Old Version Sunset: [date]

## Risks
[What could go wrong?]

## Rollback Plan
[How to revert if issues arise]
```

## Review Checklist

- [ ] Schema change documented in `contracts/`
- [ ] Affected consumers identified
- [ ] Migration plan reviewed
- [ ] Deployment order defined
- [ ] Rollback procedure documented
- [ ] Monitoring alerts configured
- [ ] Communication sent to stakeholders
