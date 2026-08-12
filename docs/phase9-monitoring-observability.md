# Phase 9 — Monitoring & Observability

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 9 — Monitoring & Observability |
| **Part** | Complete Monitoring Guide |
| **Previous** | [Phase 8 Part 6 — Load Balancer & Deploy](phase8-part6-load-balancer-deploy.md) |
| **Next** | [API Documentation](api-documentation.md) |
| **Time** | ~3 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | Spring Boot, AWS CloudWatch, running services |

---

## Table of Contents

1. [What to Monitor (RED Method)](#1-what-to-monitor-red-method)
2. [Spring Boot Actuator](#2-spring-boot-actuator)
3. [Custom Metrics with Micrometer](#3-custom-metrics-with-micrometer)
4. [Structured JSON Logging](#4-structured-json-logging)
5. [CloudWatch Setup](#5-cloudwatch-setup)
6. [CloudWatch Dashboards](#6-cloudwatch-dashboards)
7. [CloudWatch Alarms](#7-cloudwatch-alarms)
8. [What You Learned](#what-you-learned)
9. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. What to Monitor (RED Method)

The **RED method** is ideal for request-driven services like payment gateways:

```
┌─────────────────────────────────────────────────────────────────┐
│                      RED METHOD                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  R — Rate       How many requests per second?                    │
│                 → Payment volume, API throughput                  │
│                                                                   │
│  E — Errors     How many requests are failing?                   │
│                 → Payment failures, 5xx errors, timeouts         │
│                                                                   │
│  D — Duration   How long do requests take?                       │
│                 → Payment latency p50, p95, p99                  │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

### PayFlow Monitoring Priorities

| Priority | Metric | Why |
|----------|--------|-----|
| 🔴 Critical | Payment success rate | Revenue impact |
| 🔴 Critical | Payment latency p95 | Customer experience |
| 🟡 High | Error rate (5xx) | System health |
| 🟡 High | DLQ message count | Lost events |
| 🟢 Normal | Request rate | Capacity planning |
| 🟢 Normal | DB connection pool | Resource exhaustion |

---

## 2. Spring Boot Actuator

Actuator exposes operational endpoints out of the box.

### Configuration

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, info, prometheus
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized
      show-components: always
  health:
    db:
      enabled: true
    redis:
      enabled: true
    kafka:
      enabled: true
```

### Key Endpoints

| Endpoint | Purpose | Example Response |
|----------|---------|-----------------|
| `/actuator/health` | Service health + dependencies | `{"status":"UP","components":{"db":{"status":"UP"}}}` |
| `/actuator/metrics` | List all available metrics | `{"names":["jvm.memory.used","http.server.requests"]}` |
| `/actuator/metrics/{name}` | Specific metric details | Counter/gauge values |
| `/actuator/info` | App info (version, git) | `{"app":{"version":"1.0.0"}}` |

### Health Check Response

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
    "redis": { "status": "UP", "details": { "version": "7.0.12" } },
    "kafka": { "status": "UP" },
    "diskSpace": { "status": "UP", "details": { "free": 25000000000 } }
  }
}
```

---

## 3. Custom Metrics with Micrometer

Micrometer is the metrics facade for Spring Boot (like SLF4J for logging).

### Payment Success Rate

```java
@Service
@RequiredArgsConstructor
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;

    // Counter — tracks total payment attempts by outcome
    public void recordPaymentAttempt(String method, String status) {
        meterRegistry.counter("payments.total",
            "method", method,      // CARD, UPI, NET_BANKING
            "status", status       // success, failed, timeout
        ).increment();
    }

    // Timer — tracks payment processing duration
    public Timer.Sample startPaymentTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopPaymentTimer(Timer.Sample sample, String method) {
        sample.stop(meterRegistry.timer("payments.duration",
            "method", method
        ));
    }

    // Gauge — current active payments being processed
    public void registerActivePayments(AtomicInteger activeCount) {
        meterRegistry.gauge("payments.active", activeCount);
    }
}
```

### Using Metrics in Service

```java
@Service
public class PaymentServiceImpl implements PaymentService {

    private final PaymentMetrics metrics;

    @Override
    public PaymentOrder processPayment(PaymentRequest request) {
        Timer.Sample timer = metrics.startPaymentTimer();

        try {
            PaymentOrder order = doProcessPayment(request);
            metrics.recordPaymentAttempt(request.getMethod(), "success");
            return order;
        } catch (PaymentDeclinedException e) {
            metrics.recordPaymentAttempt(request.getMethod(), "declined");
            throw e;
        } catch (Exception e) {
            metrics.recordPaymentAttempt(request.getMethod(), "error");
            throw e;
        } finally {
            metrics.stopPaymentTimer(timer, request.getMethod());
        }
    }
}
```

### Latency Percentiles

```yaml
# application.yml — enable histogram percentiles
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        payments.duration: true
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
        payments.duration: 0.5, 0.95, 0.99
      sla:
        payments.duration: 100ms, 500ms, 1000ms, 5000ms
```

---

## 4. Structured JSON Logging

JSON logs are machine-parseable and work well with CloudWatch Logs Insights.

### Logback Configuration

```xml
<!-- logback-spring.xml -->
<configuration>
  <springProfile name="prod,docker">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>correlationId</includeMdcKeyName>
        <includeMdcKeyName>merchantId</includeMdcKeyName>
        <includeMdcKeyName>orderId</includeMdcKeyName>
      </encoder>
    </appender>
    <root level="INFO">
      <appender-ref ref="JSON" />
    </root>
  </springProfile>
</configuration>
```

### Correlation ID Filter

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put("correlationId", correlationId);
        response.setHeader("X-Correlation-ID", correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
```

### Resulting JSON Log

```json
{
  "timestamp": "2024-01-15T10:30:45.123Z",
  "level": "INFO",
  "logger": "com.payflow.payment.service.PaymentServiceImpl",
  "message": "Payment authorized successfully",
  "correlationId": "abc123-def456-ghi789",
  "merchantId": "merchant-001",
  "orderId": "ORD_xyz789",
  "thread": "http-nio-8080-exec-5"
}
```

---

## 5. CloudWatch Setup

### Log Groups

```bash
# Create log groups for each service
SERVICES=("api-gateway" "identity-service" "payment-service" "merchant-service"
          "settlement-service" "notification-service" "webhook-service"
          "analytics-service" "routing-engine" "bank-simulator" "reconciliation-service")

for svc in "${SERVICES[@]}"; do
  aws logs create-log-group --log-group-name "/payflow/${svc}"
  aws logs put-retention-policy --log-group-name "/payflow/${svc}" --retention-in-days 30
done
```

### Metric Filters

Extract custom metrics from log patterns:

```bash
# Filter: count payment failures
aws logs put-metric-filter \
  --log-group-name "/payflow/payment-service" \
  --filter-name "PaymentFailures" \
  --filter-pattern '{ $.level = "ERROR" && $.message = "*payment*failed*" }' \
  --metric-transformations \
    metricName=PaymentFailureCount,metricNamespace=PayFlow,metricValue=1
```

---

## 6. CloudWatch Dashboards

```bash
aws cloudwatch put-dashboard --dashboard-name PayFlow-Overview --dashboard-body '{
  "widgets": [
    {
      "type": "metric",
      "x": 0, "y": 0, "width": 12, "height": 6,
      "properties": {
        "title": "Payment Volume (per minute)",
        "metrics": [["PayFlow", "PaymentTotal", "status", "success"],
                    ["PayFlow", "PaymentTotal", "status", "failed"]],
        "period": 60, "stat": "Sum"
      }
    },
    {
      "type": "metric",
      "x": 12, "y": 0, "width": 12, "height": 6,
      "properties": {
        "title": "Payment Latency (p95)",
        "metrics": [["PayFlow", "PaymentDuration", "stat", "p95"]],
        "period": 60
      }
    },
    {
      "type": "metric",
      "x": 0, "y": 6, "width": 8, "height": 6,
      "properties": {
        "title": "Error Rate (%)",
        "metrics": [["PayFlow", "ErrorRate"]],
        "period": 300
      }
    }
  ]
}'
```

---

## 7. CloudWatch Alarms

### High Payment Failure Rate

```bash
aws cloudwatch put-metric-alarm \
  --alarm-name "PayFlow-HighFailureRate" \
  --alarm-description "Payment failure rate above 10%" \
  --metric-name PaymentFailureCount \
  --namespace PayFlow \
  --statistic Sum \
  --period 300 \
  --threshold 10 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --alarm-actions arn:aws:sns:ap-south-1:123456789012:payflow-alerts
```

### DLQ Messages Alarm

```bash
aws cloudwatch put-metric-alarm \
  --alarm-name "PayFlow-DLQMessages" \
  --alarm-description "Messages in Dead Letter Queue" \
  --metric-name ApproximateNumberOfMessagesVisible \
  --namespace AWS/SQS \
  --dimensions Name=QueueName,Value=payment-events-dlq \
  --statistic Sum \
  --period 60 \
  --threshold 1 \
  --comparison-operator GreaterThanOrEqualToThreshold \
  --evaluation-periods 1 \
  --alarm-actions arn:aws:sns:ap-south-1:123456789012:payflow-alerts
```

### Service Health Alarm

```bash
aws cloudwatch put-metric-alarm \
  --alarm-name "PayFlow-ServiceUnhealthy" \
  --alarm-description "ALB target unhealthy" \
  --metric-name UnHealthyHostCount \
  --namespace AWS/ApplicationELB \
  --dimensions Name=TargetGroup,Value=targetgroup/payflow-backend-tg/abc123 \
  --statistic Maximum \
  --period 60 \
  --threshold 1 \
  --comparison-operator GreaterThanOrEqualToThreshold \
  --evaluation-periods 2 \
  --alarm-actions arn:aws:sns:ap-south-1:123456789012:payflow-alerts
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | RED method | Rate, Errors, Duration — core metrics for services |
| 2 | Actuator | Built-in health, metrics, info endpoints |
| 3 | Micrometer | Counters, timers, gauges for custom business metrics |
| 4 | Structured logging | JSON + correlation IDs enable log tracing |
| 5 | CloudWatch Logs | Centralized log collection with retention policies |
| 6 | Dashboards | Visual overview of system health |
| 7 | Alarms | Automated alerts for failure conditions |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `/actuator/health` returns 404 | Actuator not on classpath | Add `spring-boot-starter-actuator` dependency |
| Metrics endpoint empty | Micrometer not configured | Add `micrometer-registry-prometheus` or check config |
| Logs not appearing in CloudWatch | Log driver not configured | Use `awslogs` driver in Docker compose |
| Alarm fires immediately | Threshold too low for normal traffic | Adjust threshold based on baseline |
| Correlation ID missing in logs | MDC filter not registered | Ensure filter is a Spring `@Component` |
| Dashboard shows no data | Metrics not published yet | Wait 5 minutes for first data points |
| High p95 latency | Cold starts or DB connection issues | Check connection pool and JVM warm-up |

---

<div align="center">

**[← Phase 8 Part 6: Load Balancer](phase8-part6-load-balancer-deploy.md)** | **[Documentation Index](../README.md)** | **[API Documentation →](api-documentation.md)**

</div>
