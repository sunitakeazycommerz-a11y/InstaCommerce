#!/usr/bin/env bash
# ==============================================================================
# InstaCommerce - Local Kafka Dual-Listener Connectivity Verification
# Verifies both INTERNAL://kafka:29092 and EXTERNAL://localhost:9092 listeners
# ==============================================================================
set -euo pipefail

echo "==> [Kafka Verification] Testing dual listeners in docker-compose environment..."

FAILURES=0

# 1. Check external host listener (localhost:9092)
echo -n "Checking host external listener (localhost:9092)... "
if command -v nc >/dev/null 2>&1; then
    if nc -z -w 3 localhost 9092 2>/dev/null; then
        echo "PASS (port 9092 reachable)"
    else
        echo "FAIL (cannot connect to localhost:9092)"
        echo "Hint: Ensure docker-compose is running ('docker compose up -d kafka')"
        FAILURES=$((FAILURES + 1))
    fi
else
    if python3 -c 'import socket; s = socket.socket(); s.settimeout(3); s.connect(("localhost", 9092)); s.close()' 2>/dev/null; then
        echo "PASS (port 9092 reachable via socket)"
    else
        echo "FAIL (cannot connect to localhost:9092)"
        FAILURES=$((FAILURES + 1))
    fi
fi

# 2. Check internal container listener (kafka:29092) via docker compose exec if container is active
echo -n "Checking container internal listener (kafka:29092)... "
if docker compose ps --filter "status=running" --services 2>/dev/null | grep -q "^kafka$"; then
    if docker compose exec -T kafka kafka-broker-api-versions --bootstrap-server kafka:29092 >/dev/null 2>&1; then
        echo "PASS (kafka:29092 cluster communication healthy)"
    else
        echo "FAIL (internal broker API check failed)"
        FAILURES=$((FAILURES + 1))
    fi
else
    echo "SKIPPED (Kafka container not currently running locally)"
fi

if [ "$FAILURES" -gt 0 ]; then
    echo "==> [Kafka Verification] Failed with ${FAILURES} error(s)."
    exit 1
fi

echo "==> [Kafka Verification] Configuration validated successfully."
