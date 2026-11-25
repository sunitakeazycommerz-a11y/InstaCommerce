// Package migration embeds authoritative PostgreSQL migrations for reverse-etl-orchestrator.
package migration

import _ "embed"

// V1SchemaSQL contains the idempotent schema DDL for subscriptions and sync audits.
//
//go:embed V1__create_reverse_etl_schema.sql
var V1SchemaSQL string
