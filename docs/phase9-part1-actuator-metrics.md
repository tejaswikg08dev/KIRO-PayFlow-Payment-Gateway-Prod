# Phase 9 Part 1: Actuator & Custom Metrics

## Overview

Spring Boot Actuator configuration, custom Micrometer metrics for business KPIs, and structured logging setup.

## Actuator Configuration

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info,prometheus
      base-path: /actuator
  endpoint:
    health:
      show-details: when_authorized
      show-components: when_authorized
  health:
    db:
      enabled: true
    redis:
      enabled: true
    kafka:
      enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:local}
```

## Custom Business Metrics

```java
@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    private final MeterRegistry meterRegistry;

    // Counters
    private Counter paymentCreatedCounter;
    private Counter paymentAuthorizedCounter;
    private Counter paymentFailedCounter;
    private Counter paymentCapturedCounter;

    // Timers
    private Timer paymentProcessingTimer;

    // Gauges
    private AtomicInteger activeOrders = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        paymentCreatedCounter = Counter.builder("payment.created.total")
            .description("Total payments created")
            .register(meterRegistry);

        paymentAuthorizedCounter = Counter.builder("payment.authorized.total")
            .description("Total payments authorized")
            .register(meterRegistry);

        paymentFailedCounter = Counter.builder("payment.failed.total")
            .description("Total payments failed")
            .tag("reason", "unknown")
            .register(meterRegistry);

        paymentCapturedCounter = Counter.builder("payment.captured.total")
            .description("Total payments captured")
            .register(meterRegistry);

        paymentProcessingTimer = Timer.builder("payment.processing.duration")
            .description("Payment processing latency")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);

        Gauge.builder("payment.active.orders", activeOrders, AtomicInteger::get)
            .description("Currently active (non-terminal) orders")
            .register(meterRegistry);
    }

    public void recordPaymentCreated() {
        paymentCreatedCounter.increment();
        activeOrders.incrementAndGet();
    }

    public void recordPaymentAuthorized() {
        paymentAuthorizedCounter.increment();
    }

    public void recordPaymentFailed(String reason) {
        Counter.builder("payment.failed.total")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
        activeOrders.decrementAndGet();
    }

    public void recordPaymentCaptured() {
        paymentCapturedCounter.increment();
        activeOrders.decrementAndGet();
    }

    public Timer.Sample startProcessingTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopProcessingTimer(Timer.Sample sample) {
        sample.stop(paymentProcessingTimer);
    }
}
```

### Using Metrics in Service

```java
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentMetrics metrics;

    public PaymentResponse processPayment(UUID orderId, PaymentRequest request) {
        Timer.Sample timer = metrics.startProcessingTimer();
        metrics.recordPaymentCreated();

        try {
            // ... process payment ...
            if (response.approved()) {
                metrics.recordPaymentAuthorized();
            } else {
                metrics.recordPaymentFailed(response.reason());
            }
            return response;
        } finally {
            metrics.stopProcessingTimer(timer);
        }
    }
}
```

## Structured Logging (Logback)

```xml
<!-- src/main/resources/logback-spring.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProfile name="!local">
        <!-- JSON format for production/cloud -->
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

    <springProfile name="local">
        <!-- Human-readable for local development -->
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="DEBUG">
            <appender-ref ref="CONSOLE" />
        </root>
    </springProfile>
</configuration>
```

## Correlation ID Filter

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws Exception {
        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put("correlationId", correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

## Key Actuator Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/actuator/health` | Service health (UP/DOWN) |
| `/actuator/health/db` | Database connectivity |
| `/actuator/health/redis` | Redis connectivity |
| `/actuator/metrics` | List all available metrics |
| `/actuator/metrics/payment.created.total` | Specific metric value |
| `/actuator/info` | Application info (version, build) |

## Health Check Response Example

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
    "redis": { "status": "UP", "details": { "version": "7.2.0" } },
    "kafka": { "status": "UP" },
    "diskSpace": { "status": "UP", "details": { "free": "15GB" } }
  }
}
```
