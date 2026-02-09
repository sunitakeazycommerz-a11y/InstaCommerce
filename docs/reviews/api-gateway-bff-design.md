# InstaCommerce API Gateway, Mobile BFF & Admin Gateway — Design Document

> **Version**: 1.0  
> **Date**: 2026-02-07  
> **Status**: Proposed  
> **Scope**: API Gateway Layer, Mobile BFF, Admin Operations Gateway  
> **Platform**: 20M+ users, 18 microservices, Q-commerce (10-minute delivery)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current State Analysis](#2-current-state-analysis)
3. [API Gateway Layer](#3-api-gateway-layer)
4. [Mobile BFF (Backend for Frontend)](#4-mobile-bff-backend-for-frontend)
5. [Admin Operations Gateway](#5-admin-operations-gateway)
6. [Implementation Plan](#6-implementation-plan)
7. [Appendices](#7-appendices)

---

## 1. Executive Summary

InstaCommerce currently routes all external traffic through a single Istio VirtualService with simple prefix-based routing to 18 backend services. While functional, this architecture lacks:

- **Centralized cross-cutting concerns** — No gateway-level rate limiting, JWT validation, CORS, compression, or API versioning.
- **Mobile optimization** — Mobile clients make N+1 calls to compose screens; no aggregation layer.
- **Admin operations surface** — No dedicated admin API surface with RBAC, audit, or bulk operations.
- **Traffic management** — No load shedding, per-user throttling, or circuit breaking at the edge.

This document designs three new architectural components:

| Component | Purpose | Technology |
|-----------|---------|------------|
| **API Gateway** | Edge proxy: auth, rate limiting, routing, CORS, versioning | Istio Gateway + Envoy filters + EnvoyFilter CRDs |
| **Mobile BFF** | Screen-optimized aggregation for iOS/Android | Spring Boot 3 (WebFlux) — reactive |
| **Admin Gateway** | Ops dashboard APIs with RBAC | Spring Boot 3 (MVC) — traditional |

### Architecture Overview

```
                          ┌─────────────────────────────────────────────────────┐
                          │                   GCP Cloud Armor                   │
                          │              (DDoS / WAF / Geo-blocking)            │
                          └──────────────────────┬──────────────────────────────┘
                                                 │
                          ┌──────────────────────▼──────────────────────────────┐
                          │           Istio Ingress Gateway (Envoy)             │
                          │  ┌─────────────────────────────────────────────┐    │
                          │  │  EnvoyFilter: JWT · Rate Limit · CORS ·    │    │
                          │  │  Compression · Correlation-ID · Headers    │    │
                          │  └─────────────────────────────────────────────┘    │
                          └───────┬──────────────────┬───────────────┬──────────┘
                                  │                  │               │
                    ┌─────────────▼────┐  ┌──────────▼─────┐  ┌─────▼──────────┐
                    │  api.insta...dev  │  │ m.insta...dev  │  │admin.insta.dev │
                    │  Direct routing   │  │  Mobile BFF    │  │ Admin Gateway  │
                    │  to 18 services   │  │  (aggregator)  │  │   (RBAC)       │
                    └────────┬─────────┘  └───────┬────────┘  └───────┬────────┘
                             │                    │                   │
                    ┌────────▼────────────────────▼───────────────────▼────────┐
                    │                    Istio Service Mesh (mTLS)             │
                    │  ┌─────────┐ ┌──────────┐ ┌───────────┐ ┌───────────┐   │
                    │  │identity │ │ catalog  │ │  order    │ │ payment   │   │
                    │  │service  │ │ service  │ │  service  │ │ service   │   │
                    │  └─────────┘ └──────────┘ └───────────┘ └───────────┘   │
                    │  ┌─────────┐ ┌──────────┐ ┌───────────┐ ┌───────────┐   │
                    │  │  cart   │ │ checkout │ │fulfillment│ │  search   │   │
                    │  │service  │ │orchestr. │ │  service  │ │ service   │   │
                    │  └─────────┘ └──────────┘ └───────────┘ └───────────┘   │
                    │  + 10 more services (inventory, pricing, rider, ...)    │
                    └─────────────────────────────────────────────────────────┘
```

---

## 2. Current State Analysis

### 2.1 What Exists Today

**Istio Gateway** (`deploy/helm/templates/istio/gateway.yaml`):
- Single HTTPS listener on port 443 for `api.instacommerce.dev`
- TLS termination via `instacommerce-tls` credential

**VirtualService** (`deploy/helm/templates/istio/virtual-service.yaml`):
- 20 prefix-based routes mapping `/api/v1/*` to 18 backend services
- All routes target port 8080 (hardcoded — **bug**: search=8086, pricing=8087, cart=8088, etc.)
- No timeouts, retries, fault injection, or header manipulation

**DestinationRules**:
- Connection pooling: 100 TCP / 1000 HTTP2 max connections per service
- Outlier detection: eject after 5 consecutive 5xx errors for 30s
- No service-specific tuning

**Security**:
- `PeerAuthentication`: STRICT mTLS mesh-wide
- `RequestAuthentication`: JWT validation only on payment-service and inventory-service (internal services)
- `AuthorizationPolicy`: payment & inventory restricted to order-service and fulfillment-service principals
- EnvoyFilter for security headers (X-Content-Type-Options, X-Frame-Options, HSTS, CSP)
- **No JWT validation at the gateway edge** — each service validates its own tokens

### 2.2 Gaps Identified

| Gap | Impact | Priority |
|-----|--------|----------|
| No gateway-level JWT validation | Every service duplicates auth logic; inconsistent enforcement | P0 |
| No rate limiting | Vulnerable to abuse, no per-user throttling | P0 |
| No CORS at gateway | Each service configures CORS independently (or doesn't) | P1 |
| No API versioning strategy | Cannot evolve APIs without breaking clients | P1 |
| Hardcoded port 8080 in VirtualService | Routing broken for services on non-8080 ports | P0 — Bug |
| No mobile BFF | Mobile app makes 5-8 calls per screen | P0 |
| No admin API surface | Admin ops go through same consumer API | P1 |
| No request/response transformation | No correlation IDs, no header stripping | P1 |
| No compression | Wasted bandwidth for mobile clients | P2 |
| No WebSocket support | Cannot support real-time tracking | P1 |
| No load shedding | System browns out under peak load instead of shedding | P1 |

---

## 3. API Gateway Layer

### 3.1 Technology Decision: Istio Gateway + EnvoyFilter (Enhanced)

**Decision**: Extend the existing Istio Gateway with EnvoyFilter CRDs + an external rate limit service, rather than introducing Kong or Ambassador.

**Rationale**:
- All 18 services already run in the Istio mesh with mTLS — no new sidecar or data-plane component.
- Envoy natively supports JWT, rate limiting, CORS, compression, circuit breaking, and header manipulation.
- Avoids adding another proxy hop (Kong would sit in front of or beside Envoy).
- The team already manages Helm-templated Istio resources.

**Trade-off acknowledged**: EnvoyFilter CRDs are lower-level than Kong plugins. We mitigate this with well-documented Helm templates and a dedicated `deploy/helm/templates/istio/` directory.

### 3.2 Gateway Hosts

```yaml
# deploy/helm/values.yaml — enhanced gateway configuration
istio:
  gateway:
    name: instacommerce-gateway
    servers:
      - hosts: ["api.instacommerce.dev"]          # Consumer API
        port: { number: 443, name: https-api, protocol: HTTPS }
      - hosts: ["m.instacommerce.dev"]             # Mobile BFF
        port: { number: 443, name: https-mobile, protocol: HTTPS }
      - hosts: ["admin.instacommerce.dev"]         # Admin Gateway
        port: { number: 443, name: https-admin, protocol: HTTPS }
      - hosts: ["ws.instacommerce.dev"]            # WebSocket (tracking)
        port: { number: 443, name: https-ws, protocol: HTTPS }
    tlsSecret: instacommerce-tls
```

### 3.3 Rate Limiting

#### Architecture

```
Client → Envoy (Ingress) → rate limit check → rate-limit-service (envoy-ratelimit)
                                                       │
                                                  Redis Cluster
```

#### Rate Limit Service Deployment

Deploy Envoy's reference `ratelimit` service backed by Redis:

```yaml
# deploy/helm/templates/ratelimit/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ratelimit-service
spec:
  replicas: 2
  template:
    spec:
      containers:
        - name: ratelimit
          image: envoyproxy/ratelimit:latest
          env:
            - name: REDIS_SOCKET_TYPE
              value: tcp
            - name: REDIS_URL
              value: redis-cluster.infra.svc.cluster.local:6379
            - name: RUNTIME_ROOT
              value: /data
            - name: RUNTIME_SUBDIRECTORY
              value: ratelimit
            - name: USE_STATSD
              value: "false"
            - name: LOG_LEVEL
              value: info
          resources:
            requests: { cpu: 250m, memory: 256Mi }
            limits:   { cpu: 500m, memory: 512Mi }
          volumeMounts:
            - name: config
              mountPath: /data/ratelimit/config
      volumes:
        - name: config
          configMap:
            name: ratelimit-config
```

#### Rate Limit Configuration

```yaml
# ConfigMap: ratelimit-config
domain: instacommerce-api
descriptors:
  # --- Per-User Rate Limits ---
  - key: user_id
    rate_limit:
      unit: minute
      requests_per_unit: 300            # 300 req/min per authenticated user
    descriptors:
      - key: path
        value: "/api/v1/checkout"
        rate_limit:
          unit: minute
          requests_per_unit: 10          # Checkout: 10/min per user (anti-abuse)
      - key: path
        value: "/api/v1/auth/login"
        rate_limit:
          unit: minute
          requests_per_unit: 5           # Login: 5/min per user (brute-force)
      - key: path
        value: "/api/v1/search"
        rate_limit:
          unit: second
          requests_per_unit: 5           # Search: 5/sec per user

  # --- Per-IP Rate Limits (unauthenticated) ---
  - key: remote_address
    rate_limit:
      unit: minute
      requests_per_unit: 100             # 100 req/min per IP (anonymous)
    descriptors:
      - key: path
        value: "/api/v1/auth/register"
        rate_limit:
          unit: hour
          requests_per_unit: 10          # Registration: 10/hr per IP

  # --- Global Endpoint Limits ---
  - key: path
    value: "/api/v1/cart"
    rate_limit:
      unit: second
      requests_per_unit: 5000            # Cart: 5000 req/sec global

  # --- Burst Handling ---
  # Token bucket with burst allowance via Redis sliding window
  # Configured per-descriptor with shadow_mode for monitoring before enforcement
```

#### EnvoyFilter for Rate Limiting

```yaml
# deploy/helm/templates/istio/envoyfilter-ratelimit.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: ratelimit-filter
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    # 1. Add rate limit filter to HTTP filter chain
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
        listener:
          filterChain:
            filter:
              name: envoy.filters.network.http_connection_manager
              subFilter:
                name: envoy.filters.http.router
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.ratelimit
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.ratelimit.v3.RateLimit
            domain: instacommerce-api
            failure_mode_deny: false     # Fail open — don't block if ratelimit service is down
            timeout: 50ms               # Fast timeout — don't add latency
            rate_limit_service:
              transport_api_version: V3
              grpc_service:
                envoy_grpc:
                  cluster_name: rate_limit_cluster
    # 2. Add rate limit cluster
    - applyTo: CLUSTER
      patch:
        operation: ADD
        value:
          name: rate_limit_cluster
          type: STRICT_DNS
          connect_timeout: 1s
          lb_policy: ROUND_ROBIN
          typed_extension_protocol_options:
            envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
              "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
              explicit_http_config:
                http2_protocol_options: {}
          load_assignment:
            cluster_name: rate_limit_cluster
            endpoints:
              - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: ratelimit-service
                          port_value: 8081
    # 3. Rate limit actions on routes (extract user_id from JWT, remote_address, path)
    - applyTo: VIRTUAL_HOST
      match:
        context: GATEWAY
      patch:
        operation: MERGE
        value:
          rate_limits:
            - actions:
                - request_headers:
                    header_name: x-user-id
                    descriptor_key: user_id
                - request_headers:
                    header_name: ":path"
                    descriptor_key: path
            - actions:
                - remote_address: {}
                - request_headers:
                    header_name: ":path"
                    descriptor_key: path
```

#### Rate Limit Response Headers

All rate-limited responses include:
```
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 247
X-RateLimit-Reset: 1706889600
Retry-After: 42                  # Only on 429 responses
```

### 3.4 Authentication — Gateway-Level JWT Validation

Move JWT validation from individual services to the Istio ingress gateway:

```yaml
# deploy/helm/templates/istio/request-authentication-gateway.yaml
apiVersion: security.istio.io/v1beta1
kind: RequestAuthentication
metadata:
  name: gateway-jwt-auth
spec:
  selector:
    matchLabels:
      istio: ingressgateway
  jwtRules:
    - issuer: "https://identity.instacommerce.dev"
      jwksUri: "http://identity-service.instacommerce.svc.cluster.local/.well-known/jwks.json"
      forwardOriginalToken: true
      outputPayloadToHeader: x-jwt-payload    # Forward decoded claims as header
      fromHeaders:
        - name: Authorization
          prefix: "Bearer "
      fromCookies:
        - "access_token"                       # Support cookie-based auth for admin
---
# Authorization policy: require valid JWT for all routes except public ones
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: gateway-require-auth
spec:
  selector:
    matchLabels:
      istio: ingressgateway
  action: DENY
  rules:
    - from:
        - source:
            notRequestPrincipals: ["*"]       # Deny if no valid JWT principal
      to:
        - operation:
            notPaths:                          # Except these public paths
              - "/api/v1/auth/login"
              - "/api/v1/auth/register"
              - "/api/v1/auth/refresh"
              - "/api/v1/auth/forgot-password"
              - "/api/v1/products*"            # Product browsing is public
              - "/api/v1/categories*"
              - "/api/v1/search*"
              - "/api/v1/stores*"
              - "/healthz"
              - "/readyz"
```

**JWT Claims Propagation**: After gateway validation, decoded claims are forwarded as headers to backend services:

```
x-user-id: usr_abc123          # Extracted from JWT sub claim
x-user-role: CUSTOMER          # Extracted from JWT role claim  
x-user-email: user@email.com   # Extracted from JWT email claim
x-jwt-payload: <base64>        # Full decoded payload
```

Backend services trust these headers (since they come via mTLS from the gateway) and no longer need to validate JWTs themselves.

### 3.5 Request Routing — Enhanced VirtualService

Fix the current routing bugs and add versioning, timeouts, retries:

```yaml
# deploy/helm/values.yaml — enhanced routes
istio:
  routes:
    # --- Identity & Auth ---
    - prefix: /api/v1/auth
      service: identity-service
      port: 8080
      timeout: 5s
      retries: { attempts: 2, perTryTimeout: 2s, retryOn: "5xx,reset" }

    - prefix: /api/v1/users
      service: identity-service
      port: 8080
      timeout: 5s

    # --- Catalog & Search ---
    - prefix: /api/v1/products
      service: catalog-service
      port: 8080
      timeout: 3s
      retries: { attempts: 2, perTryTimeout: 1500ms, retryOn: "5xx,reset,connect-failure" }

    - prefix: /api/v1/categories
      service: catalog-service
      port: 8080
      timeout: 3s

    - prefix: /api/v1/search
      service: search-service
      port: 8086                    # FIX: was hardcoded 8080
      timeout: 2s
      retries: { attempts: 1, perTryTimeout: 1500ms }

    - prefix: /api/v1/pricing
      service: pricing-service
      port: 8087                    # FIX: was hardcoded 8080
      timeout: 2s

    # --- Cart & Checkout ---
    - prefix: /api/v1/cart
      service: cart-service
      port: 8088                    # FIX: was hardcoded 8080
      timeout: 3s

    - prefix: /api/v1/checkout
      service: checkout-orchestrator-service
      port: 8089                    # FIX: was hardcoded 8080
      timeout: 30s                  # Checkout is a saga — longer timeout
      retries: { attempts: 0 }     # No retries for checkout (idempotency risk)

    # --- Orders ---
    - prefix: /api/v1/orders
      service: order-service
      port: 8080
      timeout: 5s

    # --- Fulfillment & Delivery ---
    - prefix: /api/v1/fulfillment
      service: fulfillment-service
      port: 8080
      timeout: 5s

    - prefix: /api/v1/stores
      service: warehouse-service
      port: 8090                    # FIX: was hardcoded 8080
      timeout: 3s

    - prefix: /api/v1/riders
      service: rider-fleet-service
      port: 8091                    # FIX: was hardcoded 8080
      timeout: 5s

    - prefix: /api/v1/deliveries
      service: routing-eta-service
      port: 8092                    # FIX: was hardcoded 8080
      timeout: 5s

    - prefix: /api/v1/tracking
      service: routing-eta-service
      port: 8092                    # FIX: was hardcoded 8080
      timeout: 5s

    # --- Financial ---
    - prefix: /api/v1/wallet
      service: wallet-loyalty-service
      port: 8093                    # FIX: was hardcoded 8080
      timeout: 5s

    - prefix: /api/v1/loyalty
      service: wallet-loyalty-service
      port: 8093
      timeout: 3s

    - prefix: /api/v1/referral
      service: wallet-loyalty-service
      port: 8093
      timeout: 3s

    # --- Platform ---
    - prefix: /api/v1/notifications
      service: notification-service
      port: 8080
      timeout: 3s

    - prefix: /api/v1/fraud
      service: fraud-detection-service
      port: 8095                    # FIX: was hardcoded 8080
      timeout: 3s

    - prefix: /api/v1/flags
      service: config-feature-flag-service
      port: 8096                    # FIX: was hardcoded 8080
      timeout: 2s

    - prefix: /api/v1/audit
      service: audit-trail-service
      port: 8094                    # FIX: was hardcoded 8080
      timeout: 5s

    # --- BFF Routes ---
    - prefix: /m/v1
      service: mobile-bff
      port: 8080
      timeout: 5s

    # --- Admin Routes ---
    - prefix: /admin/v1
      service: admin-gateway
      port: 8080
      timeout: 30s
```

Updated VirtualService template with per-route timeouts, retries, and correct ports:

```yaml
# deploy/helm/templates/istio/virtual-service.yaml (enhanced)
{{- if .Values.istio.enabled }}
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: {{ .Values.istio.virtualService.name }}
spec:
  hosts:
    {{- range .Values.istio.virtualService.hosts }}
    - {{ . | quote }}
    {{- end }}
  gateways:
    - {{ .Values.istio.gateway.name }}
  http:
    {{- range .Values.istio.routes }}
    - match:
        - uri:
            prefix: {{ .prefix | quote }}
      route:
        - destination:
            host: {{ .service }}
            port:
              number: {{ .port | default 8080 }}
      {{- if .timeout }}
      timeout: {{ .timeout }}
      {{- end }}
      {{- if .retries }}
      retries:
        attempts: {{ .retries.attempts }}
        perTryTimeout: {{ .retries.perTryTimeout }}
        retryOn: {{ .retries.retryOn | quote }}
      {{- end }}
    {{- end }}
{{- end }}
```

### 3.6 API Versioning Strategy

**Strategy**: URI-path versioning (`/api/v1/`, `/api/v2/`) with header-based override.

```
/api/v1/products       → catalog-service (v1 controller)
/api/v2/products       → catalog-service (v2 controller)  OR  catalog-service-v2 (separate deployment)
```

**Versioning Rules**:
1. **Minor/patch changes** (additive fields, new optional params): Same version, same service.
2. **Breaking changes** (removed fields, renamed endpoints): New version prefix → can route to same service (internal versioned controllers) or a separate deployment.
3. **Deprecation**: `Sunset: Sat, 01 Mar 2027 00:00:00 GMT` + `Deprecation: true` headers on old versions.
4. **Maximum 2 live versions** at any time (v1 + v2). v1 sunset within 6 months of v2 GA.

```yaml
# Version routing in VirtualService
- match:
    - uri:
        prefix: /api/v2/products
  route:
    - destination:
        host: catalog-service
        port: { number: 8080 }
  headers:
    response:
      add:
        X-API-Version: "v2"
- match:
    - uri:
        prefix: /api/v1/products
  route:
    - destination:
        host: catalog-service
        port: { number: 8080 }
  headers:
    response:
      add:
        X-API-Version: "v1"
        Sunset: "Sat, 01 Mar 2027 00:00:00 GMT"
        Deprecation: "true"
```

### 3.7 CORS — Centralized Configuration

```yaml
# deploy/helm/templates/istio/envoyfilter-cors.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: cors-filter
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    - applyTo: ROUTE_CONFIGURATION
      patch:
        operation: MERGE
        value:
          virtual_hosts:
            - name: "api.instacommerce.dev:443"
              cors:
                allow_origin_string_match:
                  - exact: "https://app.instacommerce.dev"
                  - exact: "https://admin.instacommerce.dev"
                  - exact: "https://m.instacommerce.dev"
                  - safe_regex:
                      google_re2: {}
                      regex: "https://.*\\.instacommerce\\.dev"
                allow_methods: "GET, POST, PUT, PATCH, DELETE, OPTIONS"
                allow_headers: "Authorization, Content-Type, X-Request-ID, X-Correlation-ID, X-App-Version, X-Device-ID"
                expose_headers: "X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset, X-Correlation-ID, X-Request-ID"
                max_age: "86400"
                allow_credentials: true
```

**Per-service CORS is removed** — all CORS configuration lives at the gateway. Backend services should strip any CORS headers they might add to avoid conflicts.

### 3.8 Request/Response Transformation

```yaml
# deploy/helm/templates/istio/envoyfilter-headers.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: request-response-transform
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    # --- Request Transformations ---
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
        listener:
          filterChain:
            filter:
              name: envoy.filters.network.http_connection_manager
              subFilter:
                name: envoy.filters.http.router
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.lua
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.lua.v3.Lua
            inline_code: |
              function envoy_on_request(request_handle)
                -- Generate correlation ID if not present
                local corr_id = request_handle:headers():get("x-correlation-id")
                if corr_id == nil or corr_id == "" then
                  corr_id = request_handle:headers():get("x-request-id")
                  if corr_id ~= nil then
                    request_handle:headers():add("x-correlation-id", corr_id)
                  end
                end
                
                -- Extract user ID from JWT and set as header
                local jwt_payload = request_handle:headers():get("x-jwt-payload")
                if jwt_payload ~= nil then
                  -- Decoded by Istio RequestAuthentication
                  request_handle:headers():add("x-authenticated", "true")
                end
                
                -- Strip internal headers from external requests
                request_handle:headers():remove("x-internal-service")
                request_handle:headers():remove("x-debug-mode")
                request_handle:headers():remove("x-trace-override")
              end
              
              function envoy_on_response(response_handle)
                -- Strip internal headers from responses
                response_handle:headers():remove("x-envoy-upstream-service-time")
                response_handle:headers():remove("x-envoy-decorator-operation")
                response_handle:headers():remove("server")
                
                -- Add platform headers
                response_handle:headers():add("x-powered-by", "InstaCommerce")
                response_handle:headers():add("x-served-by", "gateway")
              end
```

### 3.9 Request Size Limits

```yaml
# deploy/helm/templates/istio/envoyfilter-request-limits.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: request-size-limits
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    # Default: 1MB request body limit
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.buffer
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.buffer.v3.Buffer
            max_request_bytes: 1048576    # 1MB default
```

**Per-endpoint overrides** (handled in Lua filter):

| Endpoint | Max Size | Rationale |
|----------|----------|-----------|
| `POST /api/v1/auth/*` | 16 KB | Auth payloads are small |
| `POST /api/v1/products` (admin) | 5 MB | Product images as base64 |
| `POST /admin/v1/catalog/import` | 50 MB | Bulk CSV import |
| `POST /api/v1/cart/*` | 64 KB | Cart operations |
| `POST /api/v1/checkout/*` | 128 KB | Checkout payload |
| All other POST/PUT | 1 MB | Sensible default |

### 3.10 Response Compression

```yaml
# deploy/helm/templates/istio/envoyfilter-compression.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: response-compression
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
        listener:
          filterChain:
            filter:
              name: envoy.filters.network.http_connection_manager
              subFilter:
                name: envoy.filters.http.router
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.compressor
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.compressor.v3.Compressor
            response_direction_config:
              common_config:
                min_content_length: 256                    # Compress responses > 256 bytes
                content_type:
                  - "application/json"
                  - "application/grpc"
                  - "text/plain"
                  - "text/html"
            compressor_library:
              name: text_optimized
              typed_config:
                "@type": type.googleapis.com/envoy.extensions.compression.gzip.compressor.v3.Gzip
                memory_level: 5
                window_bits: 15
                compression_level: SPEED           # Optimize for latency, not ratio
                compression_strategy: DEFAULT_STRATEGY
```

**Brotli** is added as a second compressor for clients that support it (modern browsers):

```yaml
    - applyTo: HTTP_FILTER
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.compressor
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.compressor.v3.Compressor
            compressor_library:
              name: brotli
              typed_config:
                "@type": type.googleapis.com/envoy.extensions.compression.brotli.compressor.v3.Brotli
                quality: 3                        # Low quality = fast compression
```

### 3.11 Circuit Breaking — Gateway Level

Enhanced DestinationRules with per-service circuit breaker tuning:

```yaml
# deploy/helm/values.yaml — per-service circuit breaker config
circuitBreakers:
  # High-traffic, latency-sensitive services
  cart-service:
    maxConnections: 500
    maxPendingRequests: 200
    maxRequests: 2000
    maxRetries: 3
    consecutiveErrors: 3
    ejectionTime: 15s
    
  search-service:
    maxConnections: 300
    maxPendingRequests: 150
    maxRequests: 3000
    maxRetries: 2
    consecutiveErrors: 5
    ejectionTime: 10s
    
  # Critical transaction services — stricter thresholds
  checkout-orchestrator-service:
    maxConnections: 200
    maxPendingRequests: 50
    maxRequests: 500
    maxRetries: 0                   # No retries — saga handles failure
    consecutiveErrors: 3
    ejectionTime: 30s
    
  payment-service:
    maxConnections: 200
    maxPendingRequests: 50
    maxRequests: 500
    maxRetries: 0
    consecutiveErrors: 2
    ejectionTime: 60s
    
  # Default for all other services
  default:
    maxConnections: 100
    maxPendingRequests: 100
    maxRequests: 1000
    maxRetries: 3
    consecutiveErrors: 5
    ejectionTime: 30s
```

### 3.12 Load Shedding

**Strategy**: Priority-based load shedding using Envoy's overload manager + custom Lua filter.

```yaml
# deploy/helm/templates/istio/envoyfilter-load-shedding.yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: load-shedding
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
        listener:
          filterChain:
            filter:
              name: envoy.filters.network.http_connection_manager
              subFilter:
                name: envoy.filters.http.router
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.lua
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.lua.v3.Lua
            inline_code: |
              -- Load shedding priority levels:
              -- P0 (never shed): checkout, payment, order status
              -- P1 (shed at 90% capacity): cart, auth
              -- P2 (shed at 80% capacity): search, product browse
              -- P3 (shed at 70% capacity): recommendations, promotions, reviews
              
              function envoy_on_request(request_handle)
                local path = request_handle:headers():get(":path")
                local active = tonumber(request_handle:headers():get("x-envoy-active-requests") or "0")
                local max_capacity = 10000
                local utilization = active / max_capacity
                
                local priority = 3  -- default: lowest priority
                if string.find(path, "/checkout") or string.find(path, "/payment") then
                  priority = 0
                elseif string.find(path, "/orders") then
                  priority = 0
                elseif string.find(path, "/cart") or string.find(path, "/auth") then
                  priority = 1
                elseif string.find(path, "/search") or string.find(path, "/products") then
                  priority = 2
                end
                
                -- Shed based on priority
                local shed = false
                if priority == 3 and utilization > 0.70 then shed = true end
                if priority == 2 and utilization > 0.80 then shed = true end
                if priority == 1 and utilization > 0.90 then shed = true end
                -- P0 is never shed
                
                if shed then
                  request_handle:respond(
                    {[":status"] = "503"},
                    '{"error":"SERVICE_OVERLOADED","message":"System is under heavy load. Please retry in a few seconds.","retryAfter":5}'
                  )
                end
              end
```

**Load Shedding Response**:
```json
HTTP/1.1 503 Service Unavailable
Retry-After: 5
Content-Type: application/json

{
  "error": "SERVICE_OVERLOADED",
  "message": "System is under heavy load. Please retry in a few seconds.",
  "retryAfter": 5,
  "timestamp": "2026-02-07T10:30:00Z"
}
```

### 3.13 API Key Management

For third-party integrations (store POS systems, delivery aggregators, analytics partners):

```yaml
# deploy/helm/templates/istio/envoyfilter-apikey.yaml
# API key validation via Lua filter checking against a ConfigMap or external service
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: apikey-filter
spec:
  workloadSelector:
    labels:
      istio: ingressgateway
  configPatches:
    - applyTo: HTTP_FILTER
      match:
        context: GATEWAY
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.lua
          typed_config:
            "@type": type.googleapis.com/envoy.extensions.filters.http.lua.v3.Lua
            inline_code: |
              function envoy_on_request(request_handle)
                local path = request_handle:headers():get(":path")
                -- Only apply to partner API paths
                if not string.find(path, "/api/v1/partner/") then
                  return
                end
                
                local api_key = request_handle:headers():get("x-api-key")
                if api_key == nil or api_key == "" then
                  request_handle:respond(
                    {[":status"] = "401"},
                    '{"error":"MISSING_API_KEY","message":"X-Api-Key header is required"}'
                  )
                  return
                end
                
                -- Validate against external API key service via HTTP call
                local headers, body = request_handle:httpCall(
                  "identity_service",
                  {
                    [":method"] = "POST",
                    [":path"] = "/internal/api-keys/validate",
                    [":authority"] = "identity-service",
                    ["content-type"] = "application/json"
                  },
                  '{"apiKey":"' .. api_key .. '"}',
                  200    -- timeout ms
                )
                
                if headers[":status"] ~= "200" then
                  request_handle:respond(
                    {[":status"] = "403"},
                    '{"error":"INVALID_API_KEY","message":"API key is invalid or expired"}'
                  )
                end
              end
```

**API Key Tiers**:

| Tier | Rate Limit | Endpoints | Use Case |
|------|-----------|-----------|----------|
| `basic` | 100 req/min | Read-only product, store | Small integrations |
| `standard` | 1000 req/min | Products, orders, inventory | POS systems |
| `premium` | 10000 req/min | All except admin | Delivery aggregators |
| `internal` | Unlimited | All | Internal tools |

### 3.14 WebSocket Support — Real-Time Tracking

```yaml
# deploy/helm/values.yaml — WebSocket route
istio:
  routes:
    - prefix: /ws/v1/tracking
      service: routing-eta-service
      port: 8092
      websocket: true

# deploy/helm/templates/istio/virtual-service-websocket.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: websocket-routing
spec:
  hosts:
    - "ws.instacommerce.dev"
  gateways:
    - instacommerce-gateway
  http:
    - match:
        - uri:
            prefix: /ws/v1/tracking
          headers:
            upgrade:
              exact: websocket
      route:
        - destination:
            host: routing-eta-service
            port:
              number: 8092
      timeout: 0s              # No timeout for WebSocket connections
      retries:
        attempts: 3
        perTryTimeout: 5s
        retryOn: "connect-failure"
```

**WebSocket Message Protocol**:

```json
// Client → Server: Subscribe to order tracking
{
  "type": "SUBSCRIBE",
  "orderId": "ord_abc123",
  "token": "eyJhbGciOi..."
}

// Server → Client: Location update
{
  "type": "RIDER_LOCATION",
  "orderId": "ord_abc123",
  "rider": {
    "lat": 19.0760,
    "lng": 72.8777,
    "heading": 45,
    "speed": 28.5
  },
  "eta": {
    "minutes": 4,
    "distance_meters": 1200,
    "updated_at": "2026-02-07T10:30:15Z"
  }
}

// Server → Client: Status change
{
  "type": "ORDER_STATUS",
  "orderId": "ord_abc123",
  "status": "OUT_FOR_DELIVERY",
  "timestamp": "2026-02-07T10:28:00Z"
}
```

---

## 4. Mobile BFF (Backend for Frontend)

### 4.1 Service Overview

| Property | Value |
|----------|-------|
| Service Name | `mobile-bff-service` |
| Port | `8097` |
| Framework | Spring Boot 3.x with **WebFlux** (reactive, non-blocking) |
| Purpose | Aggregate multiple backend calls into mobile-optimized responses |
| Latency Target | p99 < 2s for all endpoints |

**Why WebFlux:** BFF is I/O-bound — waiting on 3-7 parallel backend calls. WebFlux + `WebClient` provides native async composition via `Mono.zip()`. With Project Loom (Java 21 virtual threads), traditional Spring MVC could also work, but WebFlux gives richer reactive operators for timeout/fallback composition.

### 4.2 Aggregation Endpoints

#### Home Screen (`GET /bff/mobile/v1/home`)

Aggregates 7 services in parallel:

```
                ┌─── catalog-service ──────── featured products
                ├─── pricing-service ──────── prices + promotions  
                ├─── inventory-service ────── stock availability
Client ──► BFF ─┼─── cart-service ─────────── cart item count
                ├─── warehouse-service ────── nearest store + ETA
                ├─── order-service ────────── active order count
                └─── feature-flag-service ─── feature flags
```

**Response (mobile-optimized, ~2KB):**
```json
{
  "nearestStore": {
    "id": "store_hsr_001",
    "name": "InstaCommerce HSR Layout",
    "deliveryMinutes": 8,
    "isOpen": true
  },
  "activeOrderCount": 1,
  "cartItemCount": 3,
  "featuredProducts": [
    {
      "id": "prod_abc",
      "name": "Amul Toned Milk 500ml",
      "imageUrl": "https://cdn.instacommerce.dev/products/amul-milk.webp",
      "priceCents": 2700,
      "originalPriceCents": 3200,
      "discountPercent": 15,
      "inStock": true
    }
  ],
  "categories": [
    { "id": "cat_dairy", "name": "Dairy & Bread", "iconUrl": "...", "productCount": 42 }
  ],
  "banners": [
    { "id": "banner_1", "imageUrl": "...", "deepLink": "instacommerce://promo/summer" }
  ],
  "featureFlags": { "enableWallet": true, "enableLoyalty": false }
}
```

**Implementation Pattern:**
```java
@GetMapping("/home")
public Mono<HomeResponse> getHome(@RequestHeader("X-User-Id") String userId,
        @RequestHeader(value = "X-Location-Lat", required = false) Double lat,
        @RequestHeader(value = "X-Location-Lng", required = false) Double lng) {
    return Mono.zip(
        catalogClient.getFeatured(12).onErrorReturn(List.of()),
        cartClient.getCount(userId).onErrorReturn(0),
        warehouseClient.findNearest(lat, lng).onErrorReturn(StoreInfo.unavailable()),
        orderClient.getActiveCount(userId).onErrorReturn(0),
        flagClient.evaluate(userId, HOME_FLAGS).onErrorReturn(Map.of())
    ).map(t -> HomeResponse.builder()
        .featuredProducts(t.getT1())
        .cartItemCount(t.getT2())
        .nearestStore(t.getT3())
        .activeOrderCount(t.getT4())
        .featureFlags(t.getT5())
        .build()
    ).timeout(Duration.ofSeconds(2));
}
```

#### Product Detail (`GET /bff/mobile/v1/product/{id}`)
- Aggregates: catalog (detail) + pricing (price + promos) + inventory (stock) + search (related)
- Price shown is server-authoritative (not cached, not client-supplied)

#### Search Results (`GET /bff/mobile/v1/search?q=milk&page=0`)
- Aggregates: search (results) → batch pricing + batch inventory in one call each
- Batch calls with product ID list to avoid N+1

#### Cart (`GET /bff/mobile/v1/cart`)
- Aggregates: cart (items) + pricing (revalidate prices) + inventory (recheck stock) + wallet (balance)
- **Critical:** BFF re-checks prices on every cart view. If price changed, returns `priceChanged: true`

#### Checkout Summary (`GET /bff/mobile/v1/checkout/summary`)
- Aggregates: cart + pricing (final) + wallet (balance) + fraud (pre-check) + warehouse (delivery slot)

#### Active Orders (`GET /bff/mobile/v1/orders/active`)
- Aggregates: order (active) + fulfillment (status) + routing-eta (live ETA + rider location)
- Returns WebSocket URL for real-time tracking

### 4.3 Graceful Degradation

| Service Down | BFF Behavior |
|-------------|-------------|
| pricing-service | Return last Caffeine-cached price (5min TTL) |
| inventory-service | Show "Check availability" instead of stock count |
| search-service | Fall back to catalog basic search |
| wallet-service | Hide wallet section |
| feature-flag-service | Use hardcoded defaults |
| routing-eta-service | Show "Tracking unavailable" |

All calls use Resilience4j circuit breakers (50% failure → open for 30s) + `.onErrorReturn(fallback)`.

### 4.4 Performance

- **Caching:** Caffeine L1 cache for product catalog (2min TTL), categories (5min), feature flags (5min)
- **No cache:** Cart, stock, prices on checkout path (must be real-time)
- **Compression:** Brotli for text, WebP for images
- **Sparse fieldsets:** `?fields=id,name,price` to reduce payload size
- **Connection pooling:** Reactor Netty pool per backend: 200 max connections, 30s idle, 5min max life

---

## 5. Admin Operations Gateway

### 5.1 Service Overview

| Property | Value |
|----------|-------|
| Service Name | `admin-gateway-service` |
| Port | `8099` |
| Framework | Spring Boot 3.x (MVC — admin is lower volume) |
| Access | Corporate VPN / Cloud IAP only |

### 5.2 RBAC Model

```
SUPER_ADMIN        → All permissions
├── OPS_MANAGER    → Orders, fulfillment, riders
│   ├── STORE_MGR  → Inventory, catalog (own store only)
│   └── SUPPORT    → Order view, refund (<₹500)
├── FINANCE        → Payments, settlements, reports
└── PRODUCT_MGR    → Catalog, pricing, promotions
```

### 5.3 Key Endpoints

| Category | Endpoint | Roles |
|----------|---------|-------|
| Dashboard | `GET /admin/v1/dashboard` | All admin |
| Orders | `GET/POST /admin/v1/orders/*` | OPS_MANAGER, SUPPORT |
| Refunds | `POST /admin/v1/orders/{id}/refund` | SUPPORT (<₹500), OPS_MANAGER (any) |
| Inventory | `POST /admin/v1/inventory/bulk-update` | STORE_MGR, OPS_MANAGER |
| Catalog | `POST /admin/v1/catalog/products` | PRODUCT_MGR |
| Riders | `GET /admin/v1/riders/performance` | OPS_MANAGER |
| Feature Flags | `PUT /admin/v1/feature-flags/{key}` | SUPER_ADMIN |
| Fraud Rules | `POST /admin/v1/fraud/rules` | OPS_MANAGER |
| Finance | `GET /admin/v1/finance/reconciliation` | FINANCE |

### 5.4 Admin Security
- IP whitelisting via Cloud IAP (Identity-Aware Proxy)
- MFA required: TOTP or WebAuthn
- Session: 30-min idle timeout, 8-hour max
- Audit: Every action → audit-trail-service (actor, action, before/after state)
- Refunds > ₹5,000 require 2-person approval
- Rate limit: 200 req/min per admin user

---

## 6. Implementation Plan

### Phase 1: Gateway Foundation (Weeks 1-3)
- Deploy Envoy RLS with Redis for centralized rate limiting
- Configure gateway-level JWT validation (Istio RequestAuthentication)
- Standardize error responses across all routes
- Deploy Cloud Armor WAF rules (OWASP, DDoS, geo-blocking)

### Phase 2: Mobile BFF (Weeks 3-6)
- Create `mobile-bff-service` (WebFlux skeleton + Helm + Docker)
- Implement home, product detail, search, cart, checkout aggregation endpoints
- Add circuit breakers, fallbacks, Caffeine caching
- Load test: 10K concurrent users with k6

### Phase 3: Rider BFF + Admin Gateway (Weeks 6-10)
- Create `rider-bff-service` — dashboard, delivery detail, location streaming
- Create `admin-gateway-service` — RBAC, dashboards, order/inventory management
- Integrate audit trail for all admin actions
- Set up Cloud IAP for admin access control

### Phase 4: Advanced (Weeks 10-14)
- B2B API key management + per-partner rate limits
- GraphQL federation evaluation for mobile BFF v2
- CDN integration (Cloud CDN) for static assets + product images
- API analytics pipeline (request logs → BigQuery → dashboards)

---

## 7. Competitive Analysis — How Top Q-Commerce Companies Handle This

### Zepto (India, 10-minute delivery)
- **Gateway:** Kong with custom Lua plugins for rate limiting and request transformation
- **BFF:** Separate BFF per app (customer, rider, admin) in Kotlin/Ktor
- **Key insight:** 1-second TTL caching on home screen during IPL + dinner peaks (10K+ orders/min)
- **Admin:** Two portals — store-level (mobile-friendly, simple) and central ops (power-user dashboard)

### Instacart (US, same-day delivery)
- **Gateway:** Custom Envoy-based with per-customer-tier rate limiting
- **BFF:** Migrated from REST BFF → **GraphQL federation** — 40% less over-fetching on mobile
- **Key insight:** 70% of sessions start with search; ML-ranked search is gateway to conversion

### Blinkit (India, 10-minute delivery)
- **Gateway:** Envoy + Istio (similar to our approach)
- **BFF:** Go-based for response assembly speed (<5ms overhead)
- **Key insight:** Device-specific payloads — low-end Android gets simplified responses

### DoorDash (US, on-demand delivery)
- **Gateway:** Apache Traffic Server + custom middleware
- **BFF:** GraphQL gateway with schema stitching from 500+ microservices
- **Key insight:** "Tier 0" services (cart, checkout, payment) get dedicated gateway capacity pools

### Key Takeaways for InstaCommerce
1. **Separate BFFs per client type** — customer, rider, admin have fundamentally different data needs
2. **GraphQL worth evaluating for v2** — reduces over-fetching, gives mobile flexibility
3. **Gateway capacity isolation** — Critical path (checkout/payment) needs dedicated resources
4. **CDN for API responses** — Some competitors cache entire home screen at CDN

---

*End of Document*
