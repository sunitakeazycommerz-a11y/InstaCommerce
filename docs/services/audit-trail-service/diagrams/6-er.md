# Audit Trail Service - Data Model

```mermaid
erDiagram
    DOMAIN_EVENT ||--o{ AUDIT_EVENT : transforms_to
    AUDIT_EVENT ||--o{ AUDIT_PARTITION : stored_in
    AUDIT_EVENT ||--o{ AUDIT_QUERY_RESULT : queried_by
    AUDIT_EVENT ||--o{ CSV_EXPORT_ROW : exported_as

    DOMAIN_EVENT {
        string event_id PK
        string event_type
        string aggregate_id
        uuid actor_id
        json data
        timestamp created_at
    }

    AUDIT_EVENT {
        uuid audit_id PK
        string event_id
        string event_type
        uuid aggregate_id
        uuid actor_id
        string action
        string resource_type
        json details
        timestamp event_timestamp
        timestamp created_at
    }

    AUDIT_PARTITION {
        string partition_name PK
        string table_name
        timestamp month_start
        timestamp month_end
        int event_count
        string status
        timestamp created_at
        timestamp archived_at
    }

    AUDIT_QUERY_RESULT {
        string query_id PK
        json criteria
        int total_count
        int returned_count
        float latency_ms
        timestamp executed_at
    }

    CSV_EXPORT_JOB {
        string export_id PK
        json search_criteria
        int total_rows
        int batch_size
        timestamp started_at
        timestamp completed_at
    }

    CSV_EXPORT_ROW {
        string export_id FK
        int row_number PK
        string csv_line
        timestamp exported_at
    }

    SEARCH_CRITERIA {
        string event_type
        uuid actor_id
        string resource_type
        timestamp start_date
        timestamp end_date
    }

    DLQ_MESSAGE {
        string dlq_id PK
        string original_event_id
        bytes event_value
        string error_reason
        json error_context
        timestamp created_at
        int operator_reviewed
    }

    METRICS_RECORD {
        string record_id PK
        int ingest_count
        int query_count
        int export_count
        int dlq_count
        float ingest_latency_p99_ms
        float query_latency_p99_ms
        timestamp recorded_at
    }

    AUDIT_PARTITION ||--o{ AUDIT_EVENT : contains
    SEARCH_CRITERIA ||--o{ AUDIT_QUERY_RESULT : filters
    CSV_EXPORT_JOB ||--o{ CSV_EXPORT_ROW : generates
    AUDIT_EVENT ||--o{ SEARCH_CRITERIA : matches
    DOMAIN_EVENT ||--o{ DLQ_MESSAGE : failed_to_process
```

## Key Entities

| Entity | Purpose |
|--------|---------|
| **DOMAIN_EVENT** | Event from Kafka producer |
| **AUDIT_EVENT** | Immutable audit log entry |
| **AUDIT_PARTITION** | Monthly partition metadata |
| **AUDIT_QUERY_RESULT** | Paginated search result |
| **CSV_EXPORT_JOB** | Export job tracking |
| **CSV_EXPORT_ROW** | Individual CSV row |
| **SEARCH_CRITERIA** | Dynamic query filters |
| **DLQ_MESSAGE** | Failed ingestion for replay |
| **METRICS_RECORD** | Ingestion/query/export metrics |
