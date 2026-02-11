# Model Card: {{MODEL_NAME}}

## Overview
- **Model Name:** {{MODEL_NAME}}
- **Version:** {{VERSION}}
- **Owner:** {{OWNER}}
- **Framework:** {{FRAMEWORK}}
- **Serving Latency SLA:** p95 < {{P95_MS}}ms

## Purpose
{{DESCRIPTION}}

## Training Data
- **Source:** {{DATA_SOURCE}}
- **Date Range:** {{DATE_RANGE}}
- **Size:** {{NUM_SAMPLES}} samples
- **Features:** {{NUM_FEATURES}} features

## Performance Metrics
| Metric | Value | Threshold |
|--------|-------|-----------|
{{METRICS_TABLE}}

## Bias & Fairness
{{BIAS_ANALYSIS}}

## Limitations
{{LIMITATIONS}}

## Monitoring
- Drift detection: PSI threshold {{PSI_THRESHOLD}}
- Retraining trigger: {{RETRAIN_TRIGGER}}
- Kill switch: feature flag `{{KILL_SWITCH_FLAG}}`
