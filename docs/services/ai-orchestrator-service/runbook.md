# AI Orchestrator Service Runbook

## Pre-Deployment Verification Checklist

- [ ] Verify ai-orchestrator-db (PostgreSQL for workflow state)
  ```bash
  kubectl exec -n ai deploy/ai-orchestrator-service -- \
    curl -s http://localhost:8119/actuator/health/db | jq '.components.db.status'
  ```

- [ ] Check AI inference service connectivity
  ```bash
  kubectl exec -n ai deploy/ai-orchestrator-service -- \
    curl -v http://ai-inference-service:8118/health
  ```

- [ ] Verify Kafka connectivity (orchestration events)
  ```bash
  kubectl exec -n ai deploy/ai-orchestrator-service -- \
    curl -s http://localhost:8119/actuator/health | jq '.components.kafka'
  ```

- [ ] Check external ML services connectivity (if any)
  ```bash
  kubectl exec -n ai deploy/ai-orchestrator-service -- \
    curl -s http://localhost:8119/v1/admin/external-service-health | jq '.services'
  ```

---

## Deployment Procedures

### Pre-Deployment Baseline

```bash
# Workflow execution latency (p99)
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/workflow-execution-latency-p99 | jq '.value'

# Active workflows (should be within expected range)
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/active-workflows-count | jq '.value'

# Current replica count
kubectl get deploy ai-orchestrator-service -n ai -o jsonpath='{.spec.replicas}'
```

### Blue-Green Deployment

```bash
helm upgrade ai-orchestrator-service deploy/helm/ai-orchestrator-service -n ai
kubectl rollout status deploy/ai-orchestrator-service -n ai --timeout=10m

# Verify active workflows resuming
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/active-workflows-count | jq '.value'

# Verify execution latency maintained
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/workflow-execution-latency-p99 | jq '.value'
```

---

## Incident Response Procedures

### P0 - Workflow Orchestration Down

**Symptom**: AI model pipelines not executing, complex AI tasks stuck

**Diagnosis**:
```bash
kubectl get pods -n ai -l app=ai-orchestrator-service
kubectl logs -n ai deploy/ai-orchestrator-service --tail=100 | grep -i "error\|exception\|workflow.*failed"

# Check database
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/actuator/health/db | jq '.status'

# Check AI inference service connectivity
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -v http://ai-inference-service:8118/health

# Check active workflows
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/active-workflows-count | jq '.value'
```

**Resolution**:
1. Restart: `kubectl rollout restart deploy/ai-orchestrator-service -n ai`
2. Scale up: `kubectl scale deploy ai-orchestrator-service -n ai --replicas=3`
3. If AI inference down: Notify AI team

### P1 - Workflow Execution Slow (>10s latency)

**Diagnosis**:
```bash
# Check latency
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/workflow-execution-latency-p99 | jq '.value'

# Check AI inference latency
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/inference-service-latency-p99 | jq '.value'

# Check workflow queue depth
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/workflow-queue-depth | jq '.value'
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
  curl -s http://localhost:8119/metrics/workflow-failure-rate | jq '.value'

# Check failed workflows
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/v1/admin/failed-workflows?limit=10 | jq '.workflows'
```

**Resolution**:
1. Analyze failure logs
2. Check if dependent services are up
3. Retry failed workflows: `kubectl exec deploy/ai-orchestrator-service -n ai -- curl -X POST http://localhost:8119/v1/admin/retry-failed`

---

## Common Issues & Troubleshooting

### Issue: Stuck Workflows (Not Progressing)

```bash
# Check workflow status
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/v1/admin/workflows/WORKFLOW-ID | jq '.status'

# Check if waiting for AI inference
kubectl logs -n ai deploy/ai-orchestrator-service | grep "WORKFLOW-ID"

# Force workflow progress or timeout
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -X POST http://localhost:8119/v1/admin/workflows/WORKFLOW-ID/force-complete \
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
  curl -X POST http://localhost:8119/v1/admin/defer-inference-steps \
  -H "Authorization: Bearer admin-token" | jq '.deferred_count'
```

### Issue: High Memory Usage (Workflow State Accumulation)

```bash
# Check memory usage
kubectl top pod -n ai -l app=ai-orchestrator-service

# Check number of in-flight workflows
kubectl exec -n ai deploy/ai-orchestrator-service -- \
  curl -s http://localhost:8119/metrics/active-workflows-count | jq '.value'

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

- [ ] Active workflows? `kubectl exec -n ai deploy/ai-orchestrator-service -- curl -s http://localhost:8119/metrics/active-workflows-count`
- [ ] Workflow failure rate? `kubectl exec -n ai deploy/ai-orchestrator-service -- curl -s http://localhost:8119/metrics/workflow-failure-rate`
- [ ] Execution latency? `kubectl exec -n ai deploy/ai-orchestrator-service -- curl -s http://localhost:8119/metrics/workflow-execution-latency-p99`

---

## Related Documentation

- **Deployment**: `/deploy/helm/ai-orchestrator-service/`
- **Workflow Definitions**: `/deploy/ai-workflows/`
- **Integration**: AI Inference Service, Order Service, Fraud Detection Service
