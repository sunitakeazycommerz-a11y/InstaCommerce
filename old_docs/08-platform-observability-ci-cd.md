# 08 — Platform, Observability & CI/CD

> **Scope**: GKE cluster, Istio mesh, OpenTelemetry, Prometheus/Grafana, Argo CD, GitHub Actions, Helm, Local Dev  
> **Cloud**: Google Cloud Platform (GCP)  
> **Deployment**: GitOps via Argo CD  
> **Infrastructure-as-Code**: Terraform (HCL)

---

## 1. GKE Cluster Architecture

```
                        ┌──────────────────────────────────────────────┐
                        │           Google Cloud Platform              │
                        │                                              │
  Internet ──► Cloud    │  ┌────────────────────────────────────────┐  │
              Armor ──────►│   GKE Standard Cluster                 │  │
              (WAF)     │  │   Region: asia-south1                  │  │
                        │  │   Nodes: e2-standard-4 (2–6 autoscale)│  │
                        │  │                                        │  │
                        │  │   Namespaces:                          │  │
                        │  │   ├── instacommerce (workloads)        │  │
                        │  │   ├── istio-system                     │  │
                        │  │   ├── monitoring (Prometheus/Grafana)  │  │
                        │  │   ├── argocd                           │  │
                        │  │   └── temporal                         │  │
                        │  │                                        │  │
                        │  │   Istio Ingress Gateway (:443 TLS)     │  │
                        │  └──────────────┬─────────────────────────┘  │
                        │                 │                            │
                        │  ┌──────────────▼───────────┐                │
                        │  │  Cloud SQL (PostgreSQL 15)│                │
                        │  │  ├── identity_db          │                │
                        │  │  ├── catalog_db           │                │
                        │  │  ├── inventory_db         │                │
                        │  │  ├── order_db             │                │
                        │  │  ├── payment_db           │                │
                        │  │  ├── fulfillment_db       │                │
                        │  │  └── notification_db      │                │
                        │  └──────────────────────────┘                │
                        │                                              │
                        │  ┌────────────────┐  ┌────────────────────┐  │
                        │  │ Memorystore    │  │ Managed Kafka      │  │
                        │  │ Redis 7 (HA)   │  │ (Confluent or      │  │
                        │  │ 4GB Standard   │  │  self-hosted on    │  │
                        │  └────────────────┘  │  GKE via Strimzi)  │  │
                        │                      └────────────────────┘  │
                        └──────────────────────────────────────────────┘
```

---

## 2. Terraform Module Layout

```
infra/terraform/
├── environments/
│   ├── dev/
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── terraform.tfvars
│   └── prod/
│       ├── main.tf
│       ├── variables.tf
│       └── terraform.tfvars
├── modules/
│   ├── gke/
│   │   ├── main.tf         # google_container_cluster, node pools
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── cloudsql/
│   │   ├── main.tf         # google_sql_database_instance, databases, users
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── memorystore/
│   │   ├── main.tf         # google_redis_instance
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── vpc/
│   │   ├── main.tf         # google_compute_network, subnets, Cloud NAT
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── iam/
│   │   ├── main.tf         # Workload Identity, service accounts
│   │   ├── variables.tf
│   │   └── outputs.tf
│   └── secret-manager/
│       ├── main.tf
│       ├── variables.tf
│       └── outputs.tf
└── backend.tf               # GCS bucket for state
```

### Key Terraform: GKE Cluster

```hcl
resource "google_container_cluster" "primary" {
  name     = "instacommerce-${var.env}"
  location = "asia-south1"

  remove_default_node_pool = true
  initial_node_count       = 1

  networking_mode = "VPC_NATIVE"
  network         = module.vpc.network_id
  subnetwork      = module.vpc.subnet_id

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  release_channel {
    channel = "REGULAR"
  }
}

resource "google_container_node_pool" "general" {
  name       = "general-pool"
  cluster    = google_container_cluster.primary.id
  node_count = 2

  autoscaling {
    min_node_count = 2
    max_node_count = 6
  }

  node_config {
    machine_type = "e2-standard-4"
    disk_size_gb = 50
    disk_type    = "pd-ssd"

    oauth_scopes = [
      "https://www.googleapis.com/auth/cloud-platform",
    ]

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    labels = {
      env = var.env
    }
  }
}
```

### Cloud SQL

```hcl
resource "google_sql_database_instance" "main" {
  name             = "instacommerce-pg-${var.env}"
  database_version = "POSTGRES_15"
  region           = "asia-south1"

  settings {
    tier              = "db-custom-2-8192"  # 2 vCPU, 8GB RAM
    availability_type = var.env == "prod" ? "REGIONAL" : "ZONAL"
    disk_size         = 20
    disk_type         = "PD_SSD"
    disk_autoresize   = true

    ip_configuration {
      ipv4_enabled    = false
      private_network = module.vpc.network_id
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "02:00"
    }

    database_flags {
      name  = "log_min_duration_statement"
      value = "1000"  # Log queries over 1s
    }
  }
}

# Create one database per service
resource "google_sql_database" "databases" {
  for_each = toset([
    "identity_db", "catalog_db", "inventory_db",
    "order_db", "payment_db", "fulfillment_db", "notification_db"
  ])

  name     = each.key
  instance = google_sql_database_instance.main.name
}
```

---

## 3. Istio Configuration

### PeerAuthentication (Strict mTLS)

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: instacommerce
spec:
  mtls:
    mode: STRICT
```

### Gateway + VirtualService (Ingress)

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: instacommerce-gateway
  namespace: instacommerce
spec:
  selector:
    istio: ingressgateway
  servers:
    - port:
        number: 443
        name: https
        protocol: HTTPS
      tls:
        mode: SIMPLE
        credentialName: instacommerce-tls
      hosts:
        - "api.instacommerce.dev"
---
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: api-routing
  namespace: instacommerce
spec:
  hosts:
    - "api.instacommerce.dev"
  gateways:
    - instacommerce-gateway
  http:
    - match:
        - uri: { prefix: "/api/v1/auth" }
      route:
        - destination: { host: identity-service, port: { number: 8080 } }
    - match:
        - uri: { prefix: "/api/v1/products" }
        - uri: { prefix: "/api/v1/categories" }
        - uri: { prefix: "/api/v1/search" }
      route:
        - destination: { host: catalog-service, port: { number: 8080 } }
    - match:
        - uri: { prefix: "/api/v1/cart" }
      route:
        - destination: { host: cart-service, port: { number: 8080 } }
    - match:
        - uri: { prefix: "/api/v1/orders" }
        - uri: { prefix: "/api/v1/checkout" }
      route:
        - destination: { host: order-service, port: { number: 8080 } }
    - match:
        - uri: { prefix: "/api/v1/deliveries" }
      route:
        - destination: { host: fulfillment-service, port: { number: 8080 } }
```

### AuthorizationPolicy (Service-to-Service)

```yaml
# Example: payment-service only accepts requests from order-service and fulfillment-service
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: payment-service-access
  namespace: instacommerce
spec:
  selector:
    matchLabels:
      app: payment-service
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - "cluster.local/ns/instacommerce/sa/order-service"
              - "cluster.local/ns/instacommerce/sa/fulfillment-service"
```

---

## 4. Helm Chart Structure

```
deploy/helm/
├── Chart.yaml
├── values.yaml              # Default values
├── values-dev.yaml          # Dev overrides
├── values-prod.yaml         # Prod overrides
├── templates/
│   ├── _helpers.tpl
│   ├── deployment.yaml      # Generic deployment template
│   ├── service.yaml
│   ├── hpa.yaml
│   ├── pdb.yaml
│   ├── configmap.yaml
│   ├── serviceaccount.yaml
│   ├── ingress.yaml
│   └── istio/
│       ├── gateway.yaml
│       ├── virtual-service.yaml
│       ├── peer-authentication.yaml
│       ├── request-authentication.yaml
│       └── authorization-policy.yaml
└── charts/                   # Sub-charts per service (optional)
```

### values.yaml (Common)

```yaml
global:
  image:
    registry: asia-south1-docker.pkg.dev/instacommerce/images
    pullPolicy: IfNotPresent
  
services:
  identity-service:
    replicas: 2
    image: identity-service
    port: 8080
    resources:
      requests: { cpu: 250m, memory: 512Mi }
      limits:   { cpu: 500m, memory: 1Gi }
    env:
      SPRING_PROFILES_ACTIVE: gcp
      SPRING_DATASOURCE_URL: jdbc:postgresql:///identity_db?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory
    hpa:
      minReplicas: 2
      maxReplicas: 5
      targetCPU: 70

  catalog-service:
    replicas: 2
    image: catalog-service
    port: 8080
    resources:
      requests: { cpu: 250m, memory: 512Mi }
      limits:   { cpu: 500m, memory: 1Gi }
    hpa:
      minReplicas: 2
      maxReplicas: 8
      targetCPU: 70

  cart-service:
    replicas: 2
    image: cart-service
    port: 8080
    resources:
      requests: { cpu: 200m, memory: 384Mi }
      limits:   { cpu: 400m, memory: 768Mi }

  order-service:
    replicas: 2
    image: order-service
    port: 8080
    resources:
      requests: { cpu: 500m, memory: 768Mi }
      limits:   { cpu: 1000m, memory: 1536Mi }

  inventory-service:
    replicas: 2
    image: inventory-service
    port: 8080
    grpcPort: 9090
    resources:
      requests: { cpu: 500m, memory: 768Mi }
      limits:   { cpu: 1000m, memory: 1536Mi }

  payment-service:
    replicas: 2
    image: payment-service
    port: 8080
    resources:
      requests: { cpu: 250m, memory: 512Mi }
      limits:   { cpu: 500m, memory: 1Gi }

  fulfillment-service:
    replicas: 2
    image: fulfillment-service
    port: 8080
    resources:
      requests: { cpu: 250m, memory: 512Mi }
      limits:   { cpu: 500m, memory: 1Gi }

  notification-service:
    replicas: 1
    image: notification-service
    port: 8080
    resources:
      requests: { cpu: 200m, memory: 384Mi }
      limits:   { cpu: 400m, memory: 768Mi }
```

### Generic Deployment Template

```yaml
{{- range $name, $svc := .Values.services }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  namespace: instacommerce
  labels:
    app: {{ $name }}
spec:
  replicas: {{ $svc.replicas }}
  selector:
    matchLabels:
      app: {{ $name }}
  template:
    metadata:
      labels:
        app: {{ $name }}
        version: "v1"
      annotations:
        sidecar.istio.io/inject: "true"
    spec:
      serviceAccountName: {{ $name }}
      containers:
        - name: {{ $name }}
          image: "{{ $.Values.global.image.registry }}/{{ $svc.image }}:{{ $svc.tag | default $.Chart.AppVersion }}"
          ports:
            - containerPort: {{ $svc.port }}
              name: http
            {{- if $svc.grpcPort }}
            - containerPort: {{ $svc.grpcPort }}
              name: grpc
            {{- end }}
          resources:
            {{- toYaml $svc.resources | nindent 12 }}
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: {{ $svc.env.SPRING_PROFILES_ACTIVE | default "gcp" }}
            {{- range $k, $v := $svc.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 30
            periodSeconds: 15
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 20
---
{{- end }}
```

---

## 5. HPA (Horizontal Pod Autoscaler)

```yaml
{{- range $name, $svc := .Values.services }}
{{- if $svc.hpa }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ $name }}
  namespace: instacommerce
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ $name }}
  minReplicas: {{ $svc.hpa.minReplicas }}
  maxReplicas: {{ $svc.hpa.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ $svc.hpa.targetCPU }}
---
{{- end }}
{{- end }}
```

---

## 6. OpenTelemetry Collector

```yaml
# otel-collector-config.yaml (deployed as ConfigMap)
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    timeout: 5s
    send_batch_size: 1024
  memory_limiter:
    limit_mib: 512
    check_interval: 5s
  resource:
    attributes:
      - key: environment
        value: "${ENVIRONMENT}"
        action: upsert

exporters:
  prometheus:
    endpoint: 0.0.0.0:8889
    namespace: instacommerce
  otlp/traces:
    endpoint: "tempo.monitoring:4317"    # Or GCP Cloud Trace
    tls:
      insecure: true
  loki:
    endpoint: "http://loki.monitoring:3100/loki/api/v1/push"

service:
  pipelines:
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, batch, resource]
      exporters: [prometheus]
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch, resource]
      exporters: [otlp/traces]
```

### Spring Boot OTel Config (all services)

```yaml
# application.yml (common)
management:
  tracing:
    sampling:
      probability: 1.0  # 100% in dev; 10% in prod
  otlp:
    tracing:
      endpoint: http://otel-collector.monitoring:4318/v1/traces
    metrics:
      endpoint: http://otel-collector.monitoring:4318/v1/metrics
  metrics:
    tags:
      service: ${spring.application.name}
      environment: ${ENVIRONMENT:dev}
    export:
      prometheus:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
      group:
        readiness:
          include: readinessState,db,redis
        liveness:
          include: livenessState
```

---

## 7. GitHub Actions CI Pipeline

```yaml
# .github/workflows/ci.yml
name: CI Pipeline

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

env:
  REGISTRY: asia-south1-docker.pkg.dev/instacommerce/images
  JAVA_VERSION: '21'

jobs:
  detect-changes:
    runs-on: ubuntu-latest
    outputs:
      services: ${{ steps.changes.outputs.services }}
    steps:
      - uses: actions/checkout@v4
      - id: changes
        uses: dorny/paths-filter@v2
        with:
          filters: |
            identity-service: 'services/identity-service/**'
            catalog-service: 'services/catalog-service/**'
            inventory-service: 'services/inventory-service/**'
            cart-service: 'services/cart-service/**'
            order-service: 'services/order-service/**'
            payment-service: 'services/payment-service/**'
            fulfillment-service: 'services/fulfillment-service/**'
            notification-service: 'services/notification-service/**'

  build-test:
    needs: detect-changes
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: ${{ fromJson(needs.detect-changes.outputs.services) }}
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'
          cache: 'gradle'

      - name: Run Tests
        working-directory: services/${{ matrix.service }}
        run: ./gradlew test jacocoTestReport

      - name: Check Coverage
        working-directory: services/${{ matrix.service }}
        run: ./gradlew jacocoTestCoverageVerification  # min 70% line coverage

      - name: Build Image
        if: github.ref == 'refs/heads/main'
        run: |
          docker build -t ${{ env.REGISTRY }}/${{ matrix.service }}:${{ github.sha }} \
            services/${{ matrix.service }}

      - name: Push to Artifact Registry
        if: github.ref == 'refs/heads/main'
        run: |
          gcloud auth configure-docker asia-south1-docker.pkg.dev --quiet
          docker push ${{ env.REGISTRY }}/${{ matrix.service }}:${{ github.sha }}

  deploy-dev:
    needs: build-test
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Update Helm values with new tag
        run: |
          for svc in $(echo '${{ needs.detect-changes.outputs.services }}' | jq -r '.[]'); do
            yq -i ".services.${svc}.tag = \"${{ github.sha }}\"" deploy/helm/values-dev.yaml
          done
      - name: Commit and push (triggers Argo CD sync)
        run: |
          git config user.name "github-actions"
          git config user.email "actions@github.com"
          git add deploy/helm/values-dev.yaml
          git commit -m "chore: deploy ${GITHUB_SHA::8} to dev"
          git push
```

---

## 8. Argo CD Application

```yaml
# argocd/app-of-apps.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: instacommerce
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/your-org/instacommerce
    targetRevision: main
    path: deploy/helm
    helm:
      valueFiles:
        - values.yaml
        - values-dev.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: instacommerce
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

---

## 9. Docker Compose (Local Development)

```yaml
# docker-compose.yml
version: '3.9'

services:
  postgres:
    image: postgres:15-alpine
    ports: ["5432:5432"]
    environment:
      POSTGRES_PASSWORD: devpass
    volumes:
      - ./scripts/init-dbs.sql:/docker-entrypoint-initdb.d/01-init.sql
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports: ["9092:9092"]
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:29093
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      CLUSTER_ID: "q1Sh-9_ISia_zwGINzRvyQ"

  kafka-connect:
    image: debezium/connect:2.4
    ports: ["8083:8083"]
    environment:
      BOOTSTRAP_SERVERS: kafka:9092
      GROUP_ID: connect-cluster
      CONFIG_STORAGE_TOPIC: connect-configs
      OFFSET_STORAGE_TOPIC: connect-offsets
      STATUS_STORAGE_TOPIC: connect-status
    depends_on: [kafka, postgres]

  temporal:
    image: temporalio/auto-setup:latest
    ports: ["7233:7233"]
    environment:
      DB: postgresql
      POSTGRES_SEEDS: postgres
      DYNAMIC_CONFIG_FILE_PATH: /etc/temporal/config/dynamicconfig/dev.yaml
    depends_on: [postgres]

  temporal-ui:
    image: temporalio/ui:latest
    ports: ["8233:8080"]
    environment:
      TEMPORAL_ADDRESS: temporal:7233

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports: ["8090:8080"]
    environment:
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
      KAFKA_CLUSTERS_0_KAFKACONNECT_0_ADDRESS: http://kafka-connect:8083

volumes:
  pgdata:
```

### init-dbs.sql

```sql
-- Creates all per-service databases
CREATE DATABASE identity_db;
CREATE DATABASE catalog_db;
CREATE DATABASE inventory_db;
CREATE DATABASE order_db;
CREATE DATABASE payment_db;
CREATE DATABASE fulfillment_db;
CREATE DATABASE notification_db;
```

---

## 10. Prometheus Alerting Rules

```yaml
# monitoring/prometheus-rules.yaml
groups:
  - name: instacommerce-slos
    rules:
      # Error rate > 1% for any service (5min window)
      - alert: HighErrorRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (service)
          /
          sum(rate(http_server_requests_seconds_count[5m])) by (service)
          > 0.01
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.service }} error rate is {{ $value | humanizePercentage }}"

      # P99 latency > 500ms for any endpoint (5min window)
      - alert: HighLatency
        expr: |
          histogram_quantile(0.99,
            sum(rate(http_server_requests_seconds_bucket[5m])) by (le, service, uri)
          ) > 0.5
        for: 5m
        labels:
          severity: warning

      # Kafka consumer lag > 1000 for any group
      - alert: KafkaConsumerLag
        expr: kafka_consumer_records_lag_max > 1000
        for: 10m
        labels:
          severity: warning

      # Pod restart > 3 in 30 min
      - alert: FrequentPodRestarts
        expr: increase(kube_pod_container_status_restarts_total[30m]) > 3
        labels:
          severity: critical

      # Cloud SQL CPU > 80%
      - alert: DatabaseHighCPU
        expr: cloudsql_database_cpu_utilization > 0.8
        for: 10m
        labels:
          severity: warning
```

---

## 11. Grafana Dashboard Panels

Each service gets a standard dashboard with:

| Panel | PromQL Query |
|-------|-------------|
| Request Rate | `sum(rate(http_server_requests_seconds_count{service="$service"}[5m]))` |
| P50 Latency | `histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{service="$service"}[5m])) by (le))` |
| P99 Latency | Same with 0.99 |
| Error Rate | `sum(rate(http_server_requests_seconds_count{service="$service",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{service="$service"}[5m]))` |
| JVM Heap Used | `jvm_memory_used_bytes{service="$service",area="heap"}` |
| Active DB Connections | `hikaricp_connections_active{service="$service"}` |
| Kafka Consumer Lag | `kafka_consumer_records_lag_max{group=~"$service.*"}` |

---

## 12. Dockerfile (Standard per service)

```dockerfile
# services/<service-name>/Dockerfile
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -g 1001 app && adduser -u 1001 -G app -D app

WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar
RUN chown -R app:app /app

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseZGC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
```

---

## 13. Agent Instructions

### MUST DO
1. Every service MUST have readiness + liveness + startup probes.
2. All GCP credentials via Workload Identity — NEVER hardcode service account keys.
3. Every service MUST use Cloud SQL Auth Proxy sidecar or socket factory.
4. Istio sidecar auto-injection MUST be enabled (`sidecar.istio.io/inject: "true"`).
5. Docker images MUST run as non-root user.
6. Use multi-stage builds for Docker to minimize image size.
7. Log as JSON (structured logging) using Logback JSON encoder.

### MUST NOT DO
1. Do NOT create LoadBalancer-type services — all external traffic goes through Istio Ingress Gateway.
2. Do NOT use `latest` tag for images — always use commit SHA or semver.
3. Do NOT expose management endpoints (Actuator) externally.
4. Do NOT store Terraform state locally — use GCS backend.

### DEFAULTS
- JVM: `-XX:MaxRAMPercentage=75.0 -XX:+UseZGC`
- Health check path: `/actuator/health/readiness` and `/actuator/health/liveness`
- Resource requests: 250m CPU / 512Mi memory (adjust per service)
- HPA target: 70% CPU utilization
- PDB: `maxUnavailable: 1` for all services
