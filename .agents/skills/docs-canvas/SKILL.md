---
name: docs-canvas
description: >-
  Render documentation, architecture notes, and API references as a structured Antigravity
  Artifact with tables of contents, Mermaid diagrams, and deep links. Use when the
  user asks for a docs canvas, documentation overview, architecture walkthrough,
  or API reference page.
---

# Docs Canvas (Antigravity Artifact)

Build an interactive, structured Antigravity Artifact that presents documentation — architecture notes, API references, design docs, runbooks, or codebase walkthroughs — as an organized, navigable surface.

## Artifact Target

Write the resulting documentation artifact directly to the Antigravity Artifact directory:
`<appDataDir>/brain/<conversation-id>/docs-<topic_slug>.md`
Always provide `ArtifactMetadata` with `UserFacing: true`, a detailed `Summary`, and `RequestFeedback: false`.

## Structure Guidelines

1. **Header & Context**:
   - Scope, audience, and system purpose.
   - GitHub alerts for critical operational considerations (`> [!IMPORTANT]`, `> [!NOTE]`).
2. **Architecture Topology (Mermaid)**:
   - Use `flowchart TD` or `sequenceDiagram` to visualize service boundaries, data pipelines, and synchronous/asynchronous message flows.
3. **Core APIs & Data Contracts**:
   - Tabulate endpoints, Protobuf definitions, Kafka topics, and error schemas.
   - Use clickable file links: `[Filename](file:///path/to/file#L1-L50)`.
4. **Execution Paths & Code Walkthroughs**:
   - Trace end-to-end request lifecycle with call-hierarchy steps.
5. **Operational Runbook & Failure Modes**:
   - Common alerts, mitigation steps, and rollbacks.
