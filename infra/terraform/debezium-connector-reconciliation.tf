resource "kafka_topic" "reconciliation_cdc" {
  name               = "reconciliation.cdc"
  partitions         = 12
  replication_factor = var.cdc_replication_factor

  config = {
    "cleanup.policy"      = "delete"
    "retention.ms"        = tostring(var.cdc_retention_hours * 3600000)
    "segment.ms"          = "3600000"
    "compression.type"    = "snappy"
    "min.insync.replicas" = "2"
  }
}

resource "debezium_connector" "reconciliation_runs_connector" {
  name = "reconciliation-runs-connector"

  config = {
    "connector.class" = "io.debezium.connector.postgresql.PostgresConnector"
    "name"            = "reconciliation-runs-connector"

    "database.hostname" = var.reconciliation_db_host
    "database.port"     = tostring(var.reconciliation_db_port)
    "database.user"     = var.reconciliation_db_user
    "database.password" = var.reconciliation_db_password
    "database.dbname"   = "reconciliation"

    "database.server.name" = "reconciliation"
    "plugin.name"          = "pgoutput"
    "publication.name"     = "reconciliation_cdc_publication"

    "schema.include.list" = "public"
    "table.include.list"  = "public.reconciliation_runs"
    "column.include.list" = "public.reconciliation_runs.run_id,public.reconciliation_runs.run_date,public.reconciliation_runs.status,public.reconciliation_runs.mismatch_count,public.reconciliation_runs.auto_fixed_count,public.reconciliation_runs.manual_review_count,public.reconciliation_runs.started_at,public.reconciliation_runs.completed_at,public.reconciliation_runs.created_at"

    "transforms"                        = "route,unwrap"
    "transforms.route.type"             = "org.apache.kafka.connect.transforms.RegexRouter"
    "transforms.route.regex"            = "([^.]+)\\.([^.]+)\\.([^.]+)"
    "transforms.route.replacement"      = "reconciliation.cdc"
    "transforms.unwrap.type"            = "io.debezium.transforms.ExtractNewRecordState"
    "transforms.unwrap.drop.tombstones" = "true"

    "topic.prefix"          = "reconciliation"
    "topic.creation.enable" = "true"

    "decimal.handling.mode"      = "string"
    "include.unknown.datatypes"  = "false"
    "slot.name"                  = "debezium_reconciliation_slot"
    "heartbeat.interval.ms"      = "15000"
    "heartbeat.action.query"     = "UPDATE reconciliation_outbox SET published = published WHERE id = -1"
    "snapshot.mode"              = "initial"

    "offset.storage"                    = "org.apache.kafka.connect.storage.KafkaOffsetBackingStore"
    "offset.storage.topic"              = "reconciliation-cdc-offsets"
    "offset.storage.partitions"         = "1"
    "offset.storage.replication.factor" = tostring(var.cdc_replication_factor)

    "config.storage.topic"              = "reconciliation-cdc-config"
    "config.storage.partitions"         = "1"
    "config.storage.replication.factor" = tostring(var.cdc_replication_factor)

    "status.storage.topic"              = "reconciliation-cdc-status"
    "status.storage.partitions"         = "1"
    "status.storage.replication.factor" = tostring(var.cdc_replication_factor)

    "tasks.max"             = "1"
    "max.batch.size"        = "2048"
    "poll.interval.ms"      = "1000"
    "connection.timeout.ms" = "30000"
    "fetch.min.bytes"       = "1"
  }

  depends_on = [
    kafka_topic.reconciliation_cdc,
  ]
}

resource "debezium_connector" "reconciliation_mismatches_connector" {
  name = "reconciliation-mismatches-connector"

  config = {
    "connector.class"                   = "io.debezium.connector.postgresql.PostgresConnector"
    "name"                              = "reconciliation-mismatches-connector"
    "database.hostname"                 = var.reconciliation_db_host
    "database.port"                     = tostring(var.reconciliation_db_port)
    "database.user"                     = var.reconciliation_db_user
    "database.password"                 = var.reconciliation_db_password
    "database.dbname"                   = "reconciliation"
    "database.server.name"              = "reconciliation"
    "plugin.name"                       = "pgoutput"
    "publication.name"                  = "reconciliation_cdc_publication"
    "schema.include.list"               = "public"
    "table.include.list"                = "public.reconciliation_mismatches"
    "transforms"                        = "route,unwrap"
    "transforms.route.type"             = "org.apache.kafka.connect.transforms.RegexRouter"
    "transforms.route.regex"            = "([^.]+)\\.([^.]+)\\.([^.]+)"
    "transforms.route.replacement"      = "reconciliation.cdc"
    "transforms.unwrap.type"            = "io.debezium.transforms.ExtractNewRecordState"
    "transforms.unwrap.drop.tombstones" = "true"
    "topic.prefix"                      = "reconciliation"
    "topic.creation.enable"             = "true"
    "decimal.handling.mode"             = "string"
    "include.unknown.datatypes"         = "false"
    "slot.name"                         = "debezium_reconciliation_mismatches_slot"
    "heartbeat.interval.ms"             = "15000"
    "heartbeat.action.query"            = "UPDATE reconciliation_outbox SET published = published WHERE id = -1"
    "snapshot.mode"                     = "initial"
    "tasks.max"                         = "1"
  }

  depends_on = [
    kafka_topic.reconciliation_cdc,
  ]
}

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
    runs_connector       = debezium_connector.reconciliation_runs_connector.name
    mismatches_connector = debezium_connector.reconciliation_mismatches_connector.name
  }
}
