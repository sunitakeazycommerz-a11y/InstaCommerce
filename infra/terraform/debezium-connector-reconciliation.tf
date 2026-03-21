# Wave 36 Track B: CDC Connector for Reconciliation Engine
# Debezium PostgreSQL connector to capture payment ledger changes for reconciliation
# This connector uses PostgreSQL logical decoding (pgoutput) to stream changes
# from the reconciliation database to a Kafka topic for CDC consumer processing.

# Kafka topic for reconciliation CDC events
resource "kafka_topic" "reconciliation_cdc" {
  name              = "reconciliation.cdc"
  partitions        = 3
  replication_factor = 2

  config = {
    "cleanup.policy"      = "delete"
    "retention.ms"        = "86400000" # 24 hours - sufficient for nightly batch processing
    "segment.ms"          = "3600000"  # 1 hour segments
    "compression.type"    = "snappy"
    "min.insync.replicas" = "2"
  }

  depends_on = [
    # Kafka cluster must be available before creating topics
    # This is a placeholder; in production, depend on actual Kafka cluster resource
  ]
}

# Debezium connector for reconciliation_runs table
# Captures INSERT and UPDATE operations on reconciliation_runs
resource "debezium_connector" "reconciliation_runs_connector" {
  name = "reconciliation-runs-connector"

  # Connector class for PostgreSQL
  config = {
    # Core Debezium connector configuration
    "connector.class" = "io.debezium.connector.postgresql.PostgresConnector"
    "name"            = "reconciliation-runs-connector"

    # Database connection details - uses Kubernetes service DNS
    "database.hostname" = var.reconciliation_db_host
    "database.port"     = var.reconciliation_db_port
    "database.user"     = var.reconciliation_db_user
    "database.password" = var.reconciliation_db_password
    "database.dbname"   = "reconciliation"

    # Logical decoding settings
    "database.server.name" = "reconciliation"
    "plugin.name"          = "pgoutput" # PostgreSQL logical decoding plugin

    # Publication for captured tables - must be created on PostgreSQL side
    "publication.name" = "reconciliation_cdc_publication"

    # Schema and table inclusion
    "schema.include.list" = "public"
    "table.include.list"  = "public.reconciliation_runs,public.reconciliation_mismatches"

    # Event transformation pipeline
    "transforms"                    = "route,unwrap"
    "transforms.route.type"         = "org.apache.kafka.connect.transforms.RegexRouter"
    "transforms.route.regex"        = "([^.]+)\\.([^.]+)\\.([^.]+)" # Matches: server.schema.table
    "transforms.route.replacement"  = "reconciliation.cdc"
    "transforms.unwrap.type"        = "io.debezium.transforms.ExtractNewRecordState"
    "transforms.unwrap.drop.tombstones" = "true"

    # Output topic configuration
    "topic.prefix"          = "reconciliation"
    "topic.creation.enable" = "true"

    # Decimal and numeric handling
    "decimal.handling.mode" = "string" # Store NUMERIC as strings to preserve precision

    # Data type handling
    "include.unknown.datatypes" = "false"

    # Replication and sync settings
    "replication.slot.name" = "reconciliation_cdc_slot"

    # Column include/exclude (optional - include all columns)
    "column.include.list" = "public.reconciliation_runs.run_id,public.reconciliation_runs.run_date,public.reconciliation_runs.status,public.reconciliation_runs.mismatch_count,public.reconciliation_runs.auto_fixed_count,public.reconciliation_runs.manual_review_count,public.reconciliation_runs.started_at,public.reconciliation_runs.completed_at,public.reconciliation_runs.created_at,public.reconciliation_mismatches.mismatch_id,public.reconciliation_mismatches.run_id,public.reconciliation_mismatches.transaction_id,public.reconciliation_mismatches.ledger_amount,public.reconciliation_mismatches.psp_amount"

    # Heartbeat settings to detect idle connections
    "heartbeat.interval.ms" = "15000"
    "heartbeat.action.query" = "UPDATE reconciliation_outbox SET updated_at = NOW() WHERE id = -1" # No-op query

    # Initial snapshot behavior
    "snapshot.mode" = "initial" # Take initial snapshot, then follow WAL

    # Offset and history storage in Kafka
    "offset.storage"              = "org.apache.kafka.connect.storage.KafkaOffsetBackingStore"
    "offset.storage.topic"        = "reconciliation-cdc-offsets"
    "offset.storage.partitions"   = 1
    "offset.storage.replication.factor" = 2

    "config.storage.topic"         = "reconciliation-cdc-config"
    "config.storage.partitions"    = 1
    "config.storage.replication.factor" = 2

    "status.storage.topic"         = "reconciliation-cdc-status"
    "status.storage.partitions"    = 1
    "status.storage.replication.factor" = 2

    # Task configuration
    "tasks.max"       = "1" # Single task for simplicity; can be increased for throughput
    "max.batch.size"  = "2048"
    "poll.interval.ms" = "1000"

    # Connection settings
    "connection.timeout.ms"   = "30000"
    "fetch.min.bytes"         = "1"
  }

  depends_on = [
    kafka_topic.reconciliation_cdc,
  ]
}

# Debezium connector for reconciliation_mismatches table
# Captures all mismatch records for downstream aggregation
resource "debezium_connector" "reconciliation_mismatches_connector" {
  name = "reconciliation-mismatches-connector"

  config = {
    "connector.class"       = "io.debezium.connector.postgresql.PostgresConnector"
    "name"                  = "reconciliation-mismatches-connector"
    "database.hostname"     = var.reconciliation_db_host
    "database.port"         = var.reconciliation_db_port
    "database.user"         = var.reconciliation_db_user
    "database.password"     = var.reconciliation_db_password
    "database.dbname"       = "reconciliation"
    "database.server.name"  = "reconciliation"
    "plugin.name"           = "pgoutput"
    "publication.name"      = "reconciliation_cdc_publication"
    "schema.include.list"   = "public"
    "table.include.list"    = "public.reconciliation_mismatches"
    "transforms"            = "route,unwrap"
    "transforms.route.type" = "org.apache.kafka.connect.transforms.RegexRouter"
    "transforms.route.regex" = "([^.]+)\\.([^.]+)\\.([^.]+)"
    "transforms.route.replacement" = "reconciliation.cdc"
    "transforms.unwrap.type" = "io.debezium.transforms.ExtractNewRecordState"
    "topic.prefix"          = "reconciliation"
    "decimal.handling.mode" = "string"
    "replication.slot.name" = "reconciliation_cdc_slot"
    "snapshot.mode"         = "initial"
    "tasks.max"             = "1"
  }

  depends_on = [
    kafka_topic.reconciliation_cdc,
  ]
}

# Output the Kafka topic details for reference
output "reconciliation_cdc_topic_name" {
  description = "Kafka topic name for reconciliation CDC events"
  value       = kafka_topic.reconciliation_cdc.name
}

output "reconciliation_cdc_topic_partitions" {
  description = "Number of partitions for reconciliation CDC topic"
  value       = kafka_topic.reconciliation_cdc.partitions
}

output "reconciliation_cdc_connector_status" {
  description = "Debezium connector resource status"
  value = {
    runs_connector = debezium_connector.reconciliation_runs_connector.name
    mismatches_connector = debezium_connector.reconciliation_mismatches_connector.name
  }
}
