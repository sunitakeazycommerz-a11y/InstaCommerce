# End-to-End Data Lineage Architecture with OpenLineage and Marquez

## Overview
InstaCommerce captures column-level and dataset-level data lineage across transactional sources, Kafka streaming topics, Apache Beam pipelines, dbt staging/intermediate/mart transformations, and BigQuery analytical destinations.

```mermaid
flowchart LR
    subgraph Sources["Transactional Sources"]
        PG["PostgreSQL Outbox"]
    end

    subgraph Kafka["Event Backbone"]
        KT1["orders.events"]
        KT2["payments.events"]
        KT3["inventory.events"]
    end

    subgraph Streaming["Beam Streaming"]
        B1["Order Events Pipeline"]
        B2["Payment Events Pipeline"]
        B3["Join Pipeline"]
    end

    subgraph Warehouse["BigQuery & dbt"]
        STG["dbt Staging Views"]
        INT["dbt Intermediate Tables"]
        MART["dbt Marts (mart_daily_sales, mart_order_fulfillment)"]
    end

    subgraph Lineage["Governance & Lineage Plane"]
        OL["OpenLineage Airflow Provider"]
        MQ["Marquez Lineage Portal (:5000)"]
    end

    PG -->|Outbox Relay| KT1
    PG -->|Outbox Relay| KT2
    PG -->|Outbox Relay| KT3

    KT1 --> B1
    KT2 --> B2
    KT1 & KT2 --> B3

    B1 & B2 & B3 --> STG
    STG --> INT
    INT --> MART

    STG -.->|Lineage Events| OL
    INT -.->|Lineage Events| OL
    MART -.->|Lineage Events| OL
    OL -->|HTTP API /api/v1/lineage| MQ
```

## Marquez Integration
1. **Airflow Lineage Provider**: Configured in `data-platform/airflow/openlineage.yml` emitting dataset and job lineage events upon task state transitions.
2. **Column-Level Lineage**: Captured from dbt manifest and catalog metadata on daily runs.
3. **Portal Access**: Marquez UI deployed at `http://marquez.internal.instacommerce.com:5000`.
