#!/usr/bin/env python3
"""
Wave 34 Track B: Helm Values per-service token injection script.
Updates deploy/helm/values.yaml to inject INTERNAL_SERVICE_TOKEN from service-tokens secret.
"""

import re
import sys
from typing import Dict, Tuple

SERVICES = [
    "identity-service", "catalog-service", "inventory-service", "order-service",
    "payment-service", "fulfillment-service", "notification-service", "search-service",
    "pricing-service", "cart-service", "checkout-orchestrator-service", "warehouse-service",
    "rider-fleet-service", "routing-eta-service", "wallet-loyalty-service", "audit-trail-service",
    "fraud-detection-service", "config-feature-flag-service", "mobile-bff-service",
    "admin-gateway-service", "cdc-consumer-service", "dispatch-optimizer-service",
    "location-ingestion-service", "outbox-relay-service", "payment-webhook-service",
    "reconciliation-engine", "stream-processor-service", "ai-orchestrator-service",
    "ai-inference-service"
]

TOKEN_INJECTION = """      INTERNAL_SERVICE_TOKEN:
        secretKeyRef:
          name: service-tokens
          key: {service}-token"""

def find_service_sections(content: str) -> Dict[str, Tuple[int, int]]:
    """Find start and end positions of each service section in values.yaml"""
    service_sections = {}

    for service in SERVICES:
        # Match: "  {service-name}:"
        pattern = rf"^  {re.escape(service)}:\n"
        matches = list(re.finditer(pattern, content, re.MULTILINE))

        if not matches:
            continue

        start = matches[0].start()

        # Find next service or end of file
        remaining = content[start+1:]
        next_service_match = re.search(r"^  [a-z\-]+:\n", remaining, re.MULTILINE)

        if next_service_match:
            end = start + 1 + next_service_match.start()
        else:
            end = len(content)

        service_sections[service] = (start, end)

    return service_sections

def has_internal_service_token(section: str) -> bool:
    """Check if section already has INTERNAL_SERVICE_TOKEN configured"""
    return "INTERNAL_SERVICE_TOKEN:" in section

def inject_token(content: str, service: str, section_start: int, section_end: int) -> str:
    """Inject token injection into service section"""
    section = content[section_start:section_end]

    if has_internal_service_token(section):
        # Already has token, skip
        return content

    # Find the env: section
    env_match = re.search(r"    env:\n", section)
    if not env_match:
        print(f"Warning: No 'env:' section found in {service}", file=sys.stderr)
        return content

    # Find the last env variable in this service
    # Look for the pattern: "      KEY: value" and find where env section ends
    env_start = env_match.end()

    # Find next section at same or lower indentation
    remaining_section = section[env_start:]
    next_section_match = re.search(r"^    [a-z]", remaining_section, re.MULTILINE)

    if next_section_match:
        env_end = env_start + next_section_match.start()
    else:
        env_end = len(section)

    # Insert token injection before the next section
    injection = TOKEN_INJECTION.format(service=service)

    # Insert at env_end (before next section or hpa)
    new_section = (
        section[:env_end] +
        injection + "\n" +
        section[env_end:]
    )

    return content[:section_start] + new_section + content[section_end:]

def main():
    if len(sys.argv) != 2:
        print("Usage: python3 helm_values_updater.py <path-to-values.yaml>")
        sys.exit(1)

    filepath = sys.argv[1]

    try:
        with open(filepath, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        print(f"Error: File not found: {filepath}", file=sys.stderr)
        sys.exit(1)

    service_sections = find_service_sections(content)
    print(f"Found {len(service_sections)} service definitions", file=sys.stderr)

    updated_count = 0
    for service in SERVICES:
        if service not in service_sections:
            print(f"Warning: {service} not found in values.yaml", file=sys.stderr)
            continue

        start, end = service_sections[service]
        new_content = inject_token(content, service, start, end)

        if new_content != content:
            updated_count += 1
            content = new_content
            print(f"Updated: {service}", file=sys.stderr)
        else:
            print(f"Skipped: {service} (already has INTERNAL_SERVICE_TOKEN)", file=sys.stderr)

    # Write updated content
    with open(filepath, 'w') as f:
        f.write(content)

    print(f"\nUpdated {updated_count}/{len(SERVICES)} services", file=sys.stderr)
    print("File saved successfully", file=sys.stderr)

if __name__ == "__main__":
    main()
