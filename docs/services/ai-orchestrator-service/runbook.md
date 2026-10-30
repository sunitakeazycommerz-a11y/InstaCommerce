# AI Orchestrator Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify ai-orchestrator service health and readiness
  ```bash
  kubectl exec deploy/ai-orchestrator-service -- \
    curl -s http://localhost:8100/health/ready | jq .
  ```

- [ ] Check AI inference service connectivity (port 8101)
  ```bash
  kubectl exec deploy/ai-orchestrator-service -- \
    curl -v http://ai-inference-service:8101/health
  ```

- [ ] Verify downstream services connectivity (catalog, pricing, inventory)
  ```bash
  kubectl exec deploy/ai-orchestrator-service -- \
    curl -v http://catalog-service:8082/actuator/health/readiness && \
    curl -v http://pricing-service:8089/actuator/health/readiness && \
    curl -v http://inventory-service:8083/actuator/health/readiness
  ```

- [ ] Verify Redis semantic cache connectivity
  ```bash
  kubectl exec deploy/ai-orchestrator-service -- \
    nc -zv redis-primary 6379
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Check HTTP request metrics
kubectl exec deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/metrics

# Current replica count
kubectl get deploy ai-orchestrator-service -o jsonpath='{.spec.replicas}'
```

### Rolling Update Deployment

```bash
helm upgrade ai-orchestrator-service deploy/helm/ai-orchestrator-service
kubectl rollout status deploy/ai-orchestrator-service --timeout=10m

# Smoke test agent assist endpoint
kubectl exec deploy/ai-orchestrator-service -- \
  curl -s -X POST http://localhost:8100/agent/assist \
  -H "Content-Type: application/json" \
  -d '{"message":"hello","session_id":"test-session"}' | jq .
```

---

## Incident Response Procedures

### P0 - AI Orchestration Down

**Symptom**: Customer assist or out-of-stock substitutions failing

**Diagnosis**:
```bash
kubectl get pods -l app=ai-orchestrator-service
kubectl logs deploy/ai-orchestrator-service --tail=100 | grep -i "error\|exception\|rate_limit"

# Check readiness probe
kubectl exec deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/health/ready | jq .

# Check AI inference service connectivity
kubectl exec deploy/ai-orchestrator-service -- \
  curl -v http://ai-inference-service:8101/health
```

**Resolution**:
1. Restart: `kubectl rollout restart deploy/ai-orchestrator-service`
2. Scale up: `kubectl scale deploy ai-orchestrator-service --replicas=5`
3. If downstream services degraded: Check guardrails rate limiter and circuit breakers

### P1 - Workflow Execution Slow (>10s latency)

**Diagnosis**:
```bash
# Check latency
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/metrics/workflow-execution-latency-p99 | jq '.value'

# Check AI inference latency
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/metrics/inference-service-latency-p99 | jq '.value'

# Check workflow queue depth
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/metrics/workflow-queue-depth | jq '.value'
```

**Resolution**:
1. If inference service slow: Scale up AI inference
2. If queue deep: Scale up orchestrator replicas
3. Check if workflows have complex dependencies causing bottlenecks

### P2 - Workflow Failures

**Diagnosis**:
```bash
# Check failure rate
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/metrics/workflow-failure-rate | jq '.value'

# Check failed workflows
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/v1/admin/failed-workflows?limit=10 | jq '.workflows'
```

**Resolution**:
1. Analyze failure logs
2. Check if dependent services are up
3. Retry failed workflows: `kubectl exec deploy/ai-orchestrator-service -n ai -- curl -X POST http://localhost:8100/v1/admin/retry-failed`

---

## Common Issues & Troubleshooting

### Issue: Stuck Workflows (Not Progressing)

```bash
# Check workflow status
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/v1/admin/workflows/WORKFLOW-ID | jq '.status'

# Check if waiting for AI inference
kubectl logs -n ai deploy/ai-orchestrator-service | grep "WORKFLOW-ID"

# Force workflow progress or timeout
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -X POST http://localhost:8100/v1/admin/workflows/WORKFLOW-ID/force-complete \
  -H "Authorization: Bearer admin-token" | jq '.status'
```

### Issue: AI Inference Service Unavailable During Workflow

```bash
# Check if fallback mechanism enabled
kubectl set env deploy/ai-orchestrator-service -n ai \
  INFERENCE_FALLBACK_ENABLED=true

# Restart orchestrator to enable fallback
kubectl rollout restart deploy/ai-orchestrator-service -n ai

# Or skip step and mark as deferred:
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -X POST http://localhost:8100/v1/admin/defer-inference-steps \
  -H "Authorization: Bearer admin-token" | jq '.deferred_count'
```

### Issue: High Memory Usage (Workflow State Accumulation)

```bash
# Check memory usage
kubectl top pod -n ai -l app=ai-orchestrator-service

# Check number of in-flight workflows
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8100/metrics/active-workflows-count | jq '.value'

# If > 1000: Increase memory limit
helm upgrade ai-orchestrator-service deploy/helm/ai-orchestrator-service \
  -n ai \
  --set resources.limits.memory=4Gi

# Or increase time to live for completed workflows (archive faster)
kubectl set env deploy/ai-orchestrator-service -n ai \
  WORKFLOW_COMPLETION_TTL_MINUTES=10
```

---

## Performance Tuning

### Concurrency Settings

```bash
# For high-volume workflow execution (>1000 workflows/min)
kubectl set env deploy/ai-orchestrator-service -n ai \
  WORKFLOW_CONCURRENCY=100 \
  STEP_EXECUTION_THREADS=50
```

### State Management

```bash
# Optimize workflow state storage
helm upgrade ai-orchestrator-service deploy/helm/ai-orchestrator-service \
  -n ai \
  --set db.stateCompression=enabled \
  --set db.archiveTtlDays=7
```

---

## Monitoring & Alerting

### Key Metrics

```
# Workflow execution latency
histogram_quantile(0.99, rate(ai_workflow_execution_seconds_bucket[5m]))

# Workflow success rate
rate(ai_workflows_total{status="success"}[5m]) / rate(ai_workflows_total[5m])

# Active workflows count
ai_active_workflows_count

# AI inference step latency
ai_inference_step_latency_seconds_p99
```

---

## On-Call Handoff

- [ ] Active workflows? `kubectl exec -n ai deploy/ai-orchestrator-service -- curl -s http://localhost:8100/metrics/active-workflows-count`
- [ ] Workflow failure rate? `kubectl exec -n ai deploy/ai-orchestrator-service -- curl -s http://localhost:8100/metrics/workflow-failure-rate`
- [ ] Execution latency? `kubectl exec -n ai deploy/ai-orchestrator-service -- curl -s http://localhost:8100/metrics/workflow-execution-latency-p99`

---

## Related Documentation

- **Deployment**: `/deploy/helm/ai-orchestrator-service/`
- **Workflow Definitions**: `/deploy/ai-workflows/`
- **Integration**: AI Inference Service, Order Service, Fraud Detection Service
