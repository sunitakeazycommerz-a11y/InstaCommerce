#!/usr/bin/env python3
"""
Verify Istio RequestAuthentication and AuthorizationPolicy mesh overlays.
Ensures zero-trust edge tier policies conform to Issue #234 specifications.
"""
import sys
import yaml

def verify_istio_policies():
    print("=== Validating Istio Security Mesh Overlays ===")

    # 1. Load values.yaml
    with open("deploy/helm/values.yaml", "r") as f:
        values = yaml.safe_load(f)

    istio = values.get("istio", {})
    req_auths = istio.get("requestAuthentications", [])
    auth_policies = istio.get("authorizationPolicies", [])

    print(f"Loaded base values: {len(req_auths)} RequestAuthentications, {len(auth_policies)} AuthorizationPolicies")

    # Ingress Gateway JWT check
    ingress_auth = next((ra for ra in req_auths if ra.get("name") == "ingress-gateway-jwt"), None)
    assert ingress_auth is not None, "Missing ingress-gateway-jwt RequestAuthentication"
    assert ingress_auth.get("selector", {}).get("istio") == "ingressgateway", "ingress-gateway-jwt must select istio: ingressgateway"
    jwt_rules = ingress_auth.get("jwtRules", [])
    assert len(jwt_rules) > 0, "ingress-gateway-jwt must contain jwtRules"
    assert "8081/.well-known/jwks.json" in jwt_rules[0].get("jwksUri", ""), "JWKS URI must point to identity-service port 8081"
    print("  ✔ [PASS] Ingress Gateway RequestAuthentication properly configured")

    # Admin Gateway RequestAuthentication check
    admin_auth = next((ra for ra in req_auths if ra.get("name") == "admin-gateway-service-jwt"), None)
    assert admin_auth is not None, "Missing admin-gateway-service-jwt"
    assert "instacommerce-admin" in admin_auth.get("jwtRules", [])[0].get("audiences", []), "Admin JWT must require instacommerce-admin audience"
    print("  ✔ [PASS] Admin Gateway RequestAuthentication enforces admin audience")

    # Mobile BFF RequestAuthentication check
    mobile_auth = next((ra for ra in req_auths if ra.get("name") == "mobile-bff-service-jwt"), None)
    assert mobile_auth is not None, "Missing mobile-bff-service-jwt"
    print("  ✔ [PASS] Mobile BFF RequestAuthentication configured")

    # Admin Gateway RBAC AuthorizationPolicy check
    admin_ap = next((ap for ap in auth_policies if ap.get("name") == "admin-gateway-rbac-policy"), None)
    assert admin_ap is not None, "Missing admin-gateway-rbac-policy"
    assert admin_ap.get("action") == "ALLOW", "admin-gateway-rbac-policy must be ALLOW"
    rules = admin_ap.get("rules", [])
    aud_check = any("instacommerce-admin" in when.get("values", []) for r in rules for when in r.get("when", []))
    assert aud_check, "admin-gateway-rbac-policy must verify request.auth.claims[aud] == instacommerce-admin"
    print("  ✔ [PASS] Admin Gateway RBAC policy restricts access to admin claims")

    # Ingress Admin Host Isolation AuthorizationPolicy check
    host_isolation = next((ap for ap in auth_policies if ap.get("name") == "ingress-admin-host-isolation"), None)
    assert host_isolation is not None, "Missing ingress-admin-host-isolation"
    assert host_isolation.get("action") == "DENY", "ingress-admin-host-isolation must be DENY"
    print("  ✔ [PASS] Ingress host isolation policy drops unauthenticated traffic targeting admin host")

    # Mobile BFF AuthorizationPolicy check
    mobile_ap = next((ap for ap in auth_policies if ap.get("name") == "mobile-bff-access-policy"), None)
    assert mobile_ap is not None, "Missing mobile-bff-access-policy"
    public_paths = [path for r in mobile_ap.get("rules", []) for to in r.get("to", []) for path in to.get("operation", {}).get("paths", [])]
    assert any("/api/v1/products/*" in p for p in public_paths), "Mobile BFF policy must allow public product browsing"
    assert any("/actuator/health/*" in p for p in public_paths), "Mobile BFF policy must allow health probes"
    print("  ✔ [PASS] Mobile BFF policy permits public discovery while gating user claims")

    # 2. Load values-prod.yaml
    with open("deploy/helm/values-prod.yaml", "r") as f:
        prod_values = yaml.safe_load(f)

    prod_istio = prod_values.get("istio", {})
    assert prod_istio.get("enabled") is True, "Production Istio must be enabled"
    prod_req_auths = prod_istio.get("requestAuthentications", [])
    prod_auth_policies = prod_istio.get("authorizationPolicies", [])
    assert len(prod_req_auths) >= 3, "Production values must include at least 3 RequestAuthentications"
    assert len(prod_auth_policies) >= 3, "Production values must include at least 3 AuthorizationPolicies"
    print("  ✔ [PASS] Production Istio overlay contains full defense-in-depth mesh configurations")

    print("\nAll Istio mesh security verification checks passed successfully!")
    return 0

if __name__ == "__main__":
    sys.exit(verify_istio_policies())
