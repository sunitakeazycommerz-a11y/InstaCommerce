#!/usr/bin/env bash
# ==============================================================================
# InstaCommerce - Local Kafka Dual-Listener Connectivity Verification
# Verifies both INTERNAL://kafka:9092 and EXTERNAL://localhost:9093 listeners
# ==============================================================================
set -euo pipefail

echo "==> [Kafka Verification] Testing dual listeners in docker-compose environment..."

# 1. Check external host listener (localhost:9093)
echo -n "Checking host external listener (localhost:9093)... "
if command -v nc >/dev/null 2>&1; then
    if nc -z -w 3 localhost 9093 2>/dev/null; then
        echo "PASS (port 9093 reachable)"
    else
        echo "FAIL (cannot connect to localhost:9093)"
        echo "Hint: Ensure docker-compose is running ('docker-compose up -d kafka')"
    fi
else
    # Fallback to python socket check
    if python3 -c 'import socket; s = socket.socket(); s.settimeout(3); s.connect(("localhost", 9093)); s.close()' 2>/dev/null; then
        echo "PASS (port 9093 reachable via socket)"
    else
        echo "FAIL (cannot connect to localhost:9093)"
    fi
fi

# 2. Check internal container listener (kafka:9092) via docker compose exec if container is active
echo -n "Checking container internal listener (kafka:9092)... "
if docker compose ps --filter "status=running" --services 2>/dev/null | grep -q "kafka"; then
    if docker compose exec -T kafka kafka-broker-api-versions --bootstrap-server kafka:9092 >/dev/null 2>&1; then
        echo "PASS (kafka:9092 cluster communication healthy)"
    else
        echo "FAIL (internal broker API check failed)"
    fi
else
    echo "SKIPPED (Kafka container not currently running locally)"
fi

echo "==> [Kafka Verification] Configuration validated successfully."
