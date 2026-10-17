# InstaCommerce Reviews & Architecture Directory

> **Active Canonical Review Suite:** [`iter3/`](iter3/README.md)  
> **Master Review:** [`iter3/master-review.md`](iter3/master-review.md)  
> **Documentation Archive:** [`docs/archive/`](../archive/README.md)

---

## 1. Overview & Directory Organization

This directory contains the authoritative architecture reviews, low-level designs, and implementation wave tracking for the InstaCommerce platform.

```
docs/reviews/
├── README.md                                          # This navigation index
│
├── iter3/                                             # CANONICAL LIVING REVIEW SUITE (Active)
│   ├── README.md                                      # Iteration 3 index
│   ├── master-review.md                               # Consolidated findings & risk register
│   ├── implementation-program.md                      # Multi-wave execution roadmap
│   ├── service-wise-guide.md                          # Service cluster remediation guides
│   ├── platform-wise-guide.md                         # Cross-cutting platform standards
│   ├── WAVE-30-IMPLEMENTATION.md                      # Wave 30 execution track report
│   ├── benchmarks/                                    # Industry benchmarks (India & Global)
│   ├── diagrams/                                      # HLD, LLD, and end-to-end dataflow diagrams
│   ├── platform/                                      # SRE, Security, Governance, Data & ML guides
│   ├── services/                                      # Deep cluster architecture guides (C1-C9)
│   └── appendices/                                    # Issue register & rollout playbooks
│
├── [Per-Service Foundation Reviews]                   # Canonical single-service deep-dive baselines
│   ├── identity-service-review.md
│   ├── catalog-service-review.md
│   ├── inventory-service-review.md
│   ├── order-service-review.md
│   ├── payment-service-review.md
│   ├── fulfillment-service-review.md
│   ├── notification-service-review.md
│   ├── search-service-review.md
│   ├── pricing-service-review.md
│   ├── cart-service-review.md
│   ├── checkout-orchestrator-review.md
│   ├── warehouse-service-review.md
│   ├── rider-fleet-service-review.md
│   ├── routing-eta-service-review.md
│   ├── wallet-loyalty-service-review.md
│   ├── fraud-detection-service-review.md
│   ├── audit-trail-service-review.md
│   └── config-feature-flag-service-review.md
│
├── [Cross-Cutting & Program Baselines]
│   ├── infrastructure-review.md
│   ├── contracts-events-review.md
│   ├── FLEET-ARCHITECTURE-REVIEW-2026-02-13.md
│   ├── PRINCIPAL-ENGINEERING-REVIEW-2026-03-06.md
│   ├── PRINCIPAL-ENGINEERING-REVIEW-ITERATION-2-2026-03-06.md
│   ├── PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md
│   ├── PRINCIPAL-ENGINEERING-IMPLEMENTATION-PROGRAM-2026-03-06.md
│   ├── PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-SERVICE-WISE-2026-03-06.md
│   └── PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-PLATFORM-WISE-2026-03-06.md
│
└── [Archived Documentation]
    └── docs/archive/                                  # Superseded drafts & snapshots
        ├── legacy_specs/                              # Phase 0 inception specs (old_docs/)
        └── reviews/iter3_backup/                      # Wave 30 snapshot of iter3
```

---

## 2. Navigation Guide

- For **current system context and target architecture**, start with [`iter3/master-review.md`](iter3/master-review.md).
- For **service-specific implementation guides**, navigate to [`iter3/services/`](iter3/services/) for cluster-wide architecture or the individual `*-service-review.md` files for deep-dive component internals.
- For **production issues, risk classification, and remediation**, consult [`iter3/appendices/issue-register.md`](iter3/appendices/issue-register.md).
- For **historical pre-wave specifications**, consult [`docs/archive/README.md`](../archive/README.md).

