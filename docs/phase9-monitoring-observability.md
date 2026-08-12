# Phase 9: Monitoring & Observability Overview

## Overview

Observability strategy for PayFlow covering metrics, logging, tracing, and alerting. The three pillars: metrics (what's happening), logs (why it happened), traces (where it happened).

## Three Pillars of Observability

```
┌────────────────────────────────────────────────────────────────┐
│                 OBSERVABILITY PILLARS                            │
├────────────────┬────────────────────┬──────────────────────────┤
│    METRICS     │      LOGS          │     TRACES               │
│                │                    │                          │
│ • Request rate │ • Structured JSON  │ • Correlation ID        │
│ • Error rate   │ • Error stacktrace │ • Request flow          │
│ • Latency P95  │ • Business events  │ • Service-to-service    │
│ • CPU/Memory   │ • Audit trail      │ • Bottleneck detection  │
│                │                    │                          │
│ Tool:          │ Tool:              │ Tool:                   │
│ Actuator +     │ SLF4J + Logback    │ Correlation ID          │
│ CloudWatch     │ → CloudWatch Logs  │ in headers              │
└────────────────┴────────────────────┴──────────────────────────┘
```

## Key Metrics to Monitor

| Category | Metric | Alert Threshold |
|----------|--------|----------------|
| Availability | Health check failures | > 2 consecutive |
| Latency | P95 response time | > 500ms |
| Error Rate | 5xx responses / total | > 1% |
| Throughput | Requests per second | < 10 (dead) or > 800 (overload) |
| Saturation | CPU usage | > 80% |
| Saturation | Memory usage | > 85% |
| Business | Payment success rate | < 90% |
| Business | Settlement batch failures | Any failure |
| Business | Webhook delivery rate | < 95% |

## Technology Stack

| Component | Tool | Purpose |
|-----------|------|---------|
| Metrics Exposure | Spring Actuator | `/actuator/metrics`, `/actuator/health` |
| Custom Metrics | Micrometer | Business-specific counters/gauges |
| Log Framework | SLF4J + Logback | Structured JSON logging |
| Log Aggregation | CloudWatch Logs | Centralized log storage |
| Metrics Dashboards | CloudWatch Metrics | Visualization + alerting |
| Alerting | CloudWatch Alarms | SNS notifications |
| Distributed Tracing | Correlation ID | Request tracking across services |

## Logging Strategy

```
┌───────────────────────────────────────────────────────────┐
│ Log Levels                                                 │
├───────────────────────────────────────────────────────────┤
│ ERROR  → System failures, unhandled exceptions             │
│ WARN   → Degraded performance, retries, circuit breaker    │
│ INFO   → Business events (order created, payment captured) │
│ DEBUG  → Detailed flow (disabled in production)            │
│ TRACE  → Ultra-detailed (never in production)              │
└───────────────────────────────────────────────────────────┘
```

## Structured Log Format

```json
{
  "timestamp": "2024-01-15T10:30:00.123Z",
  "level": "INFO",
  "service": "payment-service",
  "correlationId": "req-abc123",
  "message": "Payment authorized",
  "context": {
    "orderId": "ord-xyz789",
    "merchantId": "mer-456",
    "amount": 50000,
    "responseTime": 234
  }
}
```

## Alerting Strategy

| Severity | Response Time | Channel | Example |
|----------|--------------|---------|---------|
| Critical | < 5 min | SMS + Email | Service down, DB unreachable |
| High | < 30 min | Email | Error rate spike, latency degradation |
| Medium | < 4 hours | Email | Disk space warning, certificate expiry |
| Low | Next business day | Dashboard | Performance trends |
