# 🏗️ Phase 4 Part 8a: Payment Service — Project Setup

> **"Three new technologies in one service: Redis, Kafka, and Feign. This is where PayFlow becomes a real distributed system."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8a — Project Setup |
| **What You Build** | pom.xml, application.yml, PaymentServiceApplication.java, SecurityConfig.java |
| **Previous** | [Part 8 Overview](./phase4-part08-payment-service-overview.md) |
| **Next** | [Part 8b — Entities](./phase4-part08b-payment-entities.md) |

---

## 📖 Table of Contents

1. [What We Build and Why](#1-what-we-build-and-why)
2. [What's NEW Compared to Merchant Service](#2-whats-new-compared-to-merchant-service)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: pom.xml](#4-step-by-step-pomxml)
5. [Step-by-Step: application.yml](#5-step-by-step-applicationyml)
6. [Step-by-Step: PaymentServiceApplication.java](#6-step-by-step-paymentserviceapplicationjava)
7. [Step-by-Step: SecurityConfig.java](#7-step-by-step-securityconfigjava)
8. [How These 4 Files Work Together](#8-how-these-4-files-work-together)
9. [What You Learned](#9-what-you-learned)

---

## 1. What We Build and Why

Same 4 foundation files as Merchant Service, but with **3 major additions**:

| File | What's New vs Merchant |
|---|---|
| `pom.xml` | +Redis dependency, +Kafka dependency, +Feign dependency, +Testcontainers |
| `application.yml` | +Redis config, +Kafka config, +HikariCP pool config, +Feign logging |
| `PaymentServiceApplication.java` | `@EnableFeignClients` (new annotation!) |
| `SecurityConfig.java` | +`/internal/**` permitAll (for Feign service-to-service calls) |

---

## 2. What's NEW Compared to Merchant Service

| Technology | Merchant Service | Payment Service | Why Payment Needs It |
|---|---|---|---|
| **Redis** | ❌ Not used | ✅ `spring-boot-starter-data-redis` | Idempotency cache — prevent double charges |
| **Kafka** | ❌ Not used | ✅ `spring-kafka` | Publish payment events (authorized, captured, refunded) |
| **Feign** | ❌ Not used | ✅ `spring-cloud-starter-openfeign` | Call routing-service (bank authorization) and merchant-service |
| **HikariCP config** | Default (no config) | ✅ Explicit pool sizing | Payment handles high traffic — needs tuned connection pool |
| **Testcontainers** | ❌ H2 only | ✅ PostgreSQL + Kafka containers | Integration tests with real infrastructure |
| **`/internal/**`** | ❌ Not needed | ✅ permitAll | Feign clients from other services call internal endpoints |

---

## 3. Folder Structure After This Part

```
backend/payment-service/
├── pom.xml                                              ← YOU CREATE THIS
└── src/main/
    ├── java/com/payflow/payment/
    │   ├── PaymentServiceApplication.java               ← YOU CREATE THIS
    │   └── config/
    │       └── SecurityConfig.java                      ← YOU CREATE THIS
    └── resources/
        └── application.yml                              ← YOU CREATE THIS
```

---

## 4. Step-by-Step: pom.xml

**File:** `backend/payment-service/pom.xml`

### Parent and Identity

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.payflow</groupId>
        <artifactId>payflow-payment-gateway</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>payment-service</artifactId>
    <name>PayFlow Payment Service</name>
    <description>Core payment engine — order management, authorization, capture, refunds</description>
```

Same parent inheritance pattern. Nothing new here.

### Dependencies — Same as Merchant

```xml
    <dependencies>
        <!-- PayFlow Common Library -->
        <dependency>
            <groupId>com.payflow</groupId>
            <artifactId>common-lib</artifactId>
        </dependency>
```

Same as merchant — provides `ApiResponse`, exceptions, `PaymentEvent`, `PaymentStatus`, `OrderStatus`, `IdGenerator`.

```xml
        <!-- Spring Boot Web (REST controllers) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring Boot JPA (ORM / Hibernate) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- Spring Boot Validation (Jakarta Bean Validation) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Spring Boot Actuator (health, metrics) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Eureka Client (service discovery) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- Config Client (centralized config) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>

        <!-- PostgreSQL Driver -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Flyway (database migrations) -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- MapStruct (compile-time DTO mapping) -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
        </dependency>

        <!-- SpringDoc OpenAPI (Swagger UI) -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>

        <!-- Spring Security (endpoint protection) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
```

All of these are **identical to merchant**. You've seen them before. Now the NEW ones:

---

### 🆕 NEW Dependency #1: Redis

```xml
        <!-- Redis (idempotency, caching) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

**WHAT:** Spring Data Redis — a library for talking to a Redis server.

**WHY PAYMENT SERVICE NEEDS IT:**
```
PROBLEM: Customer clicks "Pay" → network timeout → clicks "Pay" again
         WITHOUT Redis: Two charges on the card!

SOLUTION: Store idempotency key in Redis
         First request:  "key123" → not in Redis → process payment → store result
         Second request: "key123" → FOUND in Redis → return cached result (no second charge)
```

**WHAT IS REDIS?**
Redis is an **in-memory key-value store**. Think of it as a super-fast dictionary:
```
Key: "payment:idempotency:unique-uuid-123"
Value: "{\"paymentId\":\"pay_abc\",\"status\":\"AUTHORIZED\"}"
TTL: 24 hours (auto-deleted after)
```

It's orders of magnitude faster than PostgreSQL for simple lookups because everything is in RAM, not on disk.

**WHY NOT USE POSTGRESQL FOR IDEMPOTENCY?**
| Feature | PostgreSQL | Redis |
|---|---|---|
| Speed | ~5ms per query | ~0.1ms per query |
| Storage | Disk (permanent) | RAM (ephemeral) |
| Use case | Permanent data (orders, payments) | Temporary data (caches, locks) |
| After 24 hours | Data stays forever | Auto-deleted (TTL) |

Idempotency keys are temporary (24hr) and checked on EVERY payment request. Redis is 50x faster for this.

---

### 🆕 NEW Dependency #2: Kafka

```xml
        <!-- Kafka (event publishing) -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
```

**WHAT:** Apache Kafka client for Spring Boot.

**WHY PAYMENT SERVICE NEEDS IT:**
When a payment is authorized, captured, or refunded, OTHER services need to know:
- **Webhook Service** → sends notification to merchant's URL
- **Settlement Service** → calculates how much to pay the merchant
- **Notification Service** → sends email/SMS to customer

Instead of calling each service directly (slow, coupled), we PUBLISH an event to Kafka. Each service SUBSCRIBES and processes at its own pace.

```
WITHOUT Kafka (synchronous — slow, coupled):
  PaymentService → call WebhookService → call SettlementService → call NotificationService
  If WebhookService is down → payment fails! (bad)

WITH Kafka (asynchronous — fast, decoupled):
  PaymentService → publish event to Kafka → done! (fast)
  WebhookService reads event later → delivers webhook
  SettlementService reads event later → calculates settlement
  NotificationService reads event later → sends email
  If any service is down → event waits in Kafka → processed when service recovers
```

**WHAT IS KAFKA?**
Kafka is a **distributed message queue**. Think of it as a post office:
- **Producer** (PaymentService) drops a letter (event) in a mailbox (topic)
- **Consumers** (Webhook, Settlement, Notification) each pick up a copy
- Letters stay in the mailbox for days (configurable retention)
- If a consumer is offline, they get the letters when they come back

---

### 🆕 NEW Dependency #3: OpenFeign

```xml
        <!-- OpenFeign (inter-service HTTP calls) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
```

**WHAT:** Spring Cloud OpenFeign — a declarative HTTP client.

**WHY PAYMENT SERVICE NEEDS IT:**
When a customer authorizes a payment, PaymentService needs to call the **Routing Service** to send the transaction to the bank. Instead of writing HTTP code manually, Feign lets you write a Java interface:

```java
// WITHOUT Feign (manual HTTP):
RestTemplate restTemplate = new RestTemplate();
Map<String, Object> response = restTemplate.postForObject(
    "http://routing-service/internal/route", request, Map.class);
// 5+ lines, manual URL, manual error handling, manual serialization

// WITH Feign (declarative):
@FeignClient("routing-service")
public interface RoutingServiceClient {
    @PostMapping("/internal/route")
    Map<String, Object> routePayment(@RequestBody Map<String, Object> request);
}
// Spring generates the HTTP client FROM the interface. You just call the method.
```

**HOW FEIGN FINDS THE SERVICE:**
```
"routing-service" → Eureka lookup → 192.168.x.x:8084 → HTTP POST to that IP
```

Feign + Eureka = service-to-service calls by NAME, not hardcoded URLs.

---

### 🆕 NEW Dependencies: Test Infrastructure

```xml
        <!-- Test Dependencies -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>kafka</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

**NEW — Testcontainers:**

| Dependency | What It Does |
|---|---|
| `spring-kafka-test` | Utilities for testing Kafka producers/consumers |
| `testcontainers:postgresql` | Spins up a REAL PostgreSQL in Docker during tests |
| `testcontainers:kafka` | Spins up a REAL Kafka in Docker during tests |
| `testcontainers:junit-jupiter` | JUnit 5 integration for Testcontainers |
| `h2` | In-memory DB for quick unit tests (no Docker needed) |

**WHY TESTCONTAINERS?**
```
H2 (in-memory):
  ✅ Fast (no Docker)
  ❌ Not real PostgreSQL (some SQL features differ)
  ❌ Can't test Kafka

Testcontainers:
  ✅ Real PostgreSQL in Docker (exact production behavior)
  ✅ Real Kafka in Docker
  ❌ Slower (starts Docker containers)
  ❌ Requires Docker installed
```

Merchant used H2 for simplicity. Payment uses Testcontainers for accuracy (payment code can't afford subtle SQL differences).

---

### Build Section

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Same as merchant — creates executable fat JAR.

---

## 5. Step-by-Step: application.yml

**File:** `backend/payment-service/src/main/resources/application.yml`

### Server

```yaml
server:
  port: 8083
```

Identity=8081, Merchant=8082, **Payment=8083**.

### Database + HikariCP (NEW)

```yaml
spring:
  application:
    name: payment-service

  # PostgreSQL
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_payment
    username: payflow
    password: payflow_secret
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      idle-timeout: 30000
      connection-timeout: 5000
```

**WHAT'S NEW — HikariCP pool config:**

| Property | Value | Meaning |
|---|---|---|
| `maximum-pool-size: 20` | Max 20 connections | "Keep at most 20 open connections to PostgreSQL" |
| `minimum-idle: 5` | Min 5 idle | "Always keep at least 5 connections ready (warm)" |
| `idle-timeout: 30000` | 30 seconds | "Close idle connections after 30s (if above minimum)" |
| `connection-timeout: 5000` | 5 seconds | "If no connection available in 5s, throw exception" |

**WHY CONFIGURE THIS?** Payment handles the most traffic in PayFlow. Every authorize, capture, refund needs a DB connection. If all 20 connections are busy and a 21st request arrives, it waits up to 5 seconds. If still no connection → error.

**WHY NOT 100 CONNECTIONS?** PostgreSQL has a max connection limit (default: 100). If 5 payment-service instances each open 20 connections = 100 total. Leave room for other services.

**MERCHANT DIDN'T NEED THIS** because it has lower traffic. Spring Boot's default HikariCP settings (pool-size=10) are fine for merchant CRUD.

### JPA / Flyway

```yaml
  # JPA / Hibernate
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true

  # Flyway migrations
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

Same as merchant. `validate` + Flyway = Flyway owns the schema.

**NEW: `format_sql: true`** — when `show-sql` is enabled for debugging, SQL is pretty-printed instead of on one line. Only cosmetic, doesn't affect behavior.

### 🆕 Redis (NEW)

```yaml
  # Redis
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
```

| Property | Meaning |
|---|---|
| `host: localhost` | Redis server address (Docker runs on localhost) |
| `port: 6379` | Redis default port |
| `timeout: 2000ms` | If Redis doesn't respond in 2 seconds, throw exception |

**IF REDIS IS DOWN:** IdempotencyService catches the exception and processes the payment normally (without idempotency protection). Payment doesn't fail just because Redis is unavailable.

### 🆕 Kafka (NEW)

```yaml
  # Kafka
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
```

| Property | Meaning |
|---|---|
| `bootstrap-servers: localhost:9092` | Kafka broker address |
| `key-serializer: StringSerializer` | Message keys are strings (e.g., paymentId) |
| `value-serializer: JsonSerializer` | Message values are JSON (e.g., PaymentEvent object → JSON) |
| `acks: all` | Wait for ALL Kafka replicas to confirm before returning success |
| `retries: 3` | Retry up to 3 times on transient failures |
| `enable.idempotence: true` | Kafka ensures exactly-once delivery (no duplicate messages) |

**WHY `acks: all`?** In a payment system, you can't lose events:
```
acks=0:  Fire and forget (fastest, but message can be lost)
acks=1:  Leader broker confirms (fast, but loses if leader crashes before replication)
acks=all: ALL replicas confirm (slowest, but message is NEVER lost) ← We use this
```

**WHY `enable.idempotence: true`?** Network retries might send the same message twice. Kafka idempotence deduplicates at the broker level — even if we retry, only ONE copy is stored.

**IF KAFKA IS DOWN:** EventPublisher catches the exception and logs it. Payment is NOT rolled back — events are fire-and-forget. The payment succeeds even if Kafka is unavailable.

### Config Server + Eureka

```yaml
  # Config import
  config:
    import: "optional:configserver:http://localhost:8888"

# Eureka client
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
    register-with-eureka: true
    fetch-registry: true
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}
```

Same as merchant. **NEW: `fetch-registry: true`** — Payment Service needs the registry because it CALLS other services (routing-service, merchant-service) via Feign. It needs to know their addresses.

Merchant didn't set this explicitly because it doesn't call other services.

### Actuator + Swagger + Logging

```yaml
# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized

# SpringDoc / Swagger
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html

# Feign client logging
logging:
  level:
    com.payflow.payment.feign: DEBUG
    com.payflow.payment: INFO
    org.springframework.kafka: WARN
```

**NEW — Feign logging:**
| Logger | Level | Why |
|---|---|---|
| `com.payflow.payment.feign: DEBUG` | Verbose | See every Feign HTTP request/response (debugging inter-service calls) |
| `com.payflow.payment: INFO` | Normal | Business logic logs |
| `org.springframework.kafka: WARN` | Quiet | Kafka is chatty at INFO — only show warnings/errors |

---

## 6. Step-by-Step: PaymentServiceApplication.java

**File:** `src/main/java/com/payflow/payment/PaymentServiceApplication.java`

```java
package com.payflow.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
```

### 🆕 NEW: `@EnableFeignClients`

**WHAT:** "Spring, scan for interfaces annotated with `@FeignClient` and generate HTTP client implementations for them."

**WHY:** Without this annotation, `@FeignClient` interfaces are ignored — Spring doesn't create beans for them, and injection fails:
```
Without @EnableFeignClients:
  @Autowired RoutingServiceClient client;
  → "No qualifying bean of type 'RoutingServiceClient'"

With @EnableFeignClients:
  Spring scans → finds RoutingServiceClient → generates HTTP client → injects it ✓
```

**COMPARISON OF ENTRY POINT ANNOTATIONS:**

| Service | Annotations | Why Different |
|---|---|---|
| Identity | `@SpringBootApplication` only | Auto-detects Eureka; no Feign needed |
| Merchant | `@SpringBootApplication` + `@EnableDiscoveryClient` | Explicit Eureka |
| **Payment** | `@SpringBootApplication` + `@EnableFeignClients` | Needs Feign for inter-service HTTP calls |

Each service adds only what it needs.

---

## 7. Step-by-Step: SecurityConfig.java

**File:** `src/main/java/com/payflow/payment/config/SecurityConfig.java`

```java
package com.payflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
```

**NEW IMPORT: `AbstractHttpConfigurer`** — used for `AbstractHttpConfigurer::disable` (method reference for CSRF disable). Merchant used `csrf -> csrf.disable()` (lambda). Both do the same thing — just different syntax.

```java
/**
 * Security configuration for the payment service.
 * In a microservice architecture, authentication is handled by the API Gateway.
 * Internal service-to-service calls are trusted within the cluster.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
```

**`AbstractHttpConfigurer::disable`** = method reference. Same as `csrf -> csrf.disable()`. Just a cleaner syntax for "call the disable method."

```java
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator health/info endpoints
                        .requestMatchers("/actuator/**").permitAll()
                        // Swagger/OpenAPI
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // All payment endpoints — authentication handled by gateway
                        .requestMatchers("/v1/**").permitAll()
```

Same as merchant — all public endpoints.

```java
                        // Internal endpoints (service-to-service)
                        .requestMatchers("/internal/**").permitAll()
```

**🆕 NEW: `/internal/**` permitAll**

**WHY?** Feign clients from other services call endpoints under `/internal/`:
```
Routing Service calls: POST /internal/route (if we had internal endpoints)
Webhook Service calls: GET /internal/merchants/{id}/webhooks
```

These are NOT exposed to external clients (API Gateway doesn't route `/internal/**`). They're only reachable within the Docker network / Kubernetes cluster.

**SECURITY MODEL:**
```
External:  Browser → Gateway (validates JWT) → /v1/** endpoints
Internal:  Service → Feign (trusted) → /internal/** endpoints

/v1/** = Gateway-protected (JWT validated before reaching us)
/internal/** = Cluster-protected (only reachable from inside the network)
```

```java
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}
```

**`return http.build()`** — Note the slightly different syntax from merchant. Merchant had `return http...build()` as a chain. Payment assigns to `http` first, then calls `http.build()`. Both patterns are equivalent — just style.

---

## 8. How These 4 Files Work Together

```
┌───────────────────────────────────────────────────────────────────────────┐
│                    PAYMENT SERVICE STARTUP                                 │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  1. pom.xml → Maven downloads: JPA + Redis + Kafka + Feign + 20 others  │
│                                                                           │
│  2. PaymentServiceApplication.main()                                     │
│     @SpringBootApplication → scan + auto-configure                       │
│     @EnableFeignClients → scan for @FeignClient interfaces               │
│                                                                           │
│  3. application.yml is read:                                              │
│     port=8083, PostgreSQL connection, Redis connection, Kafka connection  │
│                                                                           │
│  4. Auto-configuration kicks in:                                          │
│     spring-data-jpa → DataSource + HikariCP (20 max connections)        │
│     spring-data-redis → RedisConnectionFactory + RedisTemplate          │
│     spring-kafka → KafkaTemplate + ProducerFactory                      │
│     openfeign → Feign client implementations for @FeignClient interfaces│
│     flyway → Migration runner                                            │
│                                                                           │
│  5. SecurityConfig bean created:                                         │
│     CSRF=off, STATELESS, /v1/** + /internal/** + /actuator/** = permit  │
│                                                                           │
│  6. Flyway runs V1-V4 migrations                                        │
│  7. Hibernate validates entities match DB                                │
│  8. Tomcat starts on 8083                                                │
│  9. Registers with Eureka + fetches registry (for Feign calls)          │
│                                                                           │
│  READY: Accepting HTTP on :8083                                          │
│         Connected to: PostgreSQL, Redis, Kafka, Eureka                   │
│         Can call: routing-service, merchant-service (via Feign)          │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 9. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **spring-data-redis** | Redis client for idempotency caching — prevents double charges |
| 2 | **spring-kafka** | Kafka client for publishing payment events asynchronously |
| 3 | **spring-cloud-openfeign** | Declarative HTTP client — call other services by writing an interface |
| 4 | **@EnableFeignClients** | Activates Feign — Spring generates HTTP clients from @FeignClient interfaces |
| 5 | **HikariCP explicit config** | Connection pool tuning — max-pool-size, idle-timeout, connection-timeout |
| 6 | **Redis config in yml** | host, port, timeout — connects to Redis for fast key-value lookups |
| 7 | **Kafka producer config** | acks=all (no message loss), enable.idempotence=true (exactly-once delivery) |
| 8 | **`/internal/**` endpoints** | Service-to-service paths — not exposed externally, trusted within cluster |
| 9 | **AbstractHttpConfigurer::disable** | Method reference syntax for CSRF disable (alternative to lambda) |
| 10 | **fetch-registry: true** | Payment needs the Eureka registry to FIND other services for Feign calls |
| 11 | **Feign logging: DEBUG** | See every HTTP request/response between services (essential for debugging) |
| 12 | **Kafka: WARN** | Reduce Kafka's chatty INFO logs — only show problems |
| 13 | **Testcontainers** | Real PostgreSQL + Kafka in Docker during tests (more accurate than H2) |
| 14 | **Fire-and-forget events** | Kafka down? Log the error, payment still succeeds. Never fail a payment for infrastructure issues. |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part08-payment-service-overview.md) | Payment Service Overview |
| **Part 8a** | **Project Setup** (You are here) |
| [Part 8b](./phase4-part08b-payment-entities.md) | Entities |
| [Part 8c](./phase4-part08c-payment-migrations.md) | Flyway Migrations |
| [Part 8d](./phase4-part08d-payment-repositories.md) | Repositories |
| [Part 8e](./phase4-part08e-payment-dtos-mappers.md) | DTOs + Mappers |
| [Part 8f](./phase4-part08f-payment-configs.md) | Config Classes (Redis, Kafka, Feign) |
| [Part 8g](./phase4-part08g-payment-order-refund-services.md) | OrderService + RefundService + Tests |
| [Part 8h](./phase4-part08h-payment-idempotency-events.md) | IdempotencyService + EventPublisher + Tests |
| [Part 8i](./phase4-part08i-payment-engine.md) | PaymentService Core Engine + Tests |
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + ExceptionHandler + Feign + Docker + curl |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | How Everything Connects |

---

*Next: [Part 8b — Entities (Order, Payment, PaymentMethod, Refund)](./phase4-part08b-payment-entities.md) →*
