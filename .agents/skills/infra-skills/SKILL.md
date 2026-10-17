---
name: infra-skills
description: Cloud infrastructure management using Terraform GCP modules, local docker-compose bootstrap, and secure IAM/KMS platform configurations.
---

# Infra Skills: Terraform, GCP & Local Platform Engineering

Use this skill when provisioning cloud infrastructure in `infra/terraform/`, maintaining the local `docker-compose.yml` stack, or managing GCP security posture, KMS keys, and databases.

---

## 1. Platform Topology

```
  Google Cloud Platform (GCP)
  ├── GKE Cluster (asia-south1)
  │   ├── Istio Service Mesh (Ingress Gateway, Envoy sidecars)
  │   └── 30 Microservices (Java, Go, Python workloads)
  ├── Cloud SQL (PostgreSQL 16)
  │   ├── Dedicated databases per microservice (Database-per-service pattern)
  │   └── Debezium CDC publication slots
  ├── Cloud Storage (GCS)
  │   └── CMEK-encrypted WORM archive buckets (`infra/terraform/modules/gcs-kms-audit-archive`)
  ├── Google Cloud KMS
  │   └── Hardware Security Module (HSM) encryption keys for financial audit trails
  └── BigQuery
      └── Enterprise Lakehouse & Data Mesh storage for dbt analytics
```

---

## 2. Terraform Module Architecture (`infra/terraform`)

1. **Module Reusability**: All shared resources live under `infra/terraform/modules/`:
   - `gcs-kms-audit-archive`: Immutable, object-locked GCS bucket with CMEK encryption for `audit-trail-service`.
   - `cloud-sql-postgres`: Multi-database provisioning with automated backups, point-in-time recovery, and private IP peering.
2. **State Hygiene**:
   - Remote state stored in a versioned GCS bucket with state locking.
   - Always run `terraform plan` and inspect the diff before `terraform apply`.
   - Never hardcode secrets or credentials in `.tf` files; use Google Secret Manager.

---

## 3. Local Infrastructure Bootstrap (`docker-compose.yml`)

Start the local dependencies with:
```bash
docker-compose up -d
```

### Services & Ports
| Container | Port | Purpose |
|---|---|---|
| **PostgreSQL 16** | `5432` | Shared engine hosting per-service databases initialized via `scripts/init-dbs.sql` |
| **Redis** | `6379` | Fast distributed caching, cart sessions, rate-limit counters |
| **Apache Kafka** | `9092` | Event broker for all canonical domain event topics |
| **Kafka Connect / Debezium** | `8083` | CDC relay streaming Postgres WAL changes into Kafka |
| **Temporal Server** | `7233` | Durable workflow engine for distributed sagas |
| **Temporal UI** | `8088` | Web console for inspecting active workflows, history, and compensations |
| **Kafka UI** | `8080` | Web dashboard for topic inspection, schema registry, and consumer lag |

---

## 4. Disaster Recovery & Zero-Data-Loss Invariants

1. **Audit Archive Immutability**:
   - Audit trail archives (`audit-trail-service`) use Object Lock (Bucket Lock in COMPLIANCE mode) preventing deletion even by cloud root administrators for 7 years.
2. **Database Isolation**:
   - Each service has its own logical database and credentials defined in `scripts/init-dbs.sql`.
   - Cross-database SQL queries are strictly forbidden. All cross-boundary communication must use Kafka events or REST/gRPC APIs.

