# 🏗️ Phase 4 Part 9a: Routing Service — Project Setup

> **"This POM is different. No JPA, no Flyway, no PostgreSQL. Instead: Netty, Resilience4j, DynamoDB, and a completely different parent."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9a — Project Setup |
| **What You Build** | pom.xml, application.yml, RoutingServiceApplication.java |
| **Previous** | [Part 9 Overview](./phase4-part09-routing-service-overview.md) |
| **Next** | [Part 9b — ISO 8583 Foundation](./phase4-part09b-iso8583-foundation.md) |

---

## 📖 Table of Contents

1. [What We Build and Why](#1-what-we-build-and-why)
2. [What's COMPLETELY Different from Previous Services](#2-whats-completely-different-from-previous-services)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: pom.xml](#4-step-by-step-pomxml)
5. [Step-by-Step: application.yml](#5-step-by-step-applicationyml)
6. [Step-by-Step: RoutingServiceApplication.java](#6-step-by-step-routingserviceapplicationjava)
7. [How These 3 Files Work Together](#7-how-these-3-files-work-together)
8. [What You Learned](#8-what-you-learned)

---

## 1. What We Build and Why

Same foundation pattern (pom.xml + yml + main class) but with **completely different content**:

| File | What's Different from Payment Service |
|---|---|
| `pom.xml` | Different parent, no JPA/Flyway/Redis/Kafka/Feign/Security. Added: Netty, Resilience4j, DynamoDB SDK |
| `application.yml` | No datasource, no HikariCP, no Redis, no Kafka. Added: bank simulator, Netty threads, fraud thresholds, circuit breaker config |
| `RoutingServiceApplication.java` | `@EnableScheduling` (new!), `@EnableDiscoveryClient` (same as merchant), no `@EnableFeignClients` |

---

## 2. What's COMPLETELY Different from Previous Services

| Feature | Identity/Merchant/Payment | Routing Service |
|---|---|---|
| **Parent POM** | `com.payflow:payflow-payment-gateway` | `org.springframework.boot:spring-boot-starter-parent` |
| **Version management** | Parent manages all versions | Own `<properties>` with explicit versions |
| **Database** | PostgreSQL + JPA + Flyway | None (DynamoDB for metrics, in-memory for dev) |
| **Security** | `spring-boot-starter-security` + SecurityConfig | No security at all (internal-only service) |
| **DTO mapping** | MapStruct (compile-time) | Manual (simple DTOs, 2 classes) |
| **HTTP client** | Feign (payment calls routing) | IS the Feign target (doesn't call others) |
| **Network protocol** | HTTP/JSON | **TCP/Binary** (ISO 8583 via Netty) |
| **Resilience** | None | **Circuit breaker** (Resilience4j) |

**WHY DIFFERENT PARENT?** The routing service was developed as a standalone module with its own dependency management, not inheriting from the multi-module parent POM. This means it declares ALL versions explicitly in `<properties>` and manages its own `<dependencyManagement>` for Spring Cloud.

---

## 3. Folder Structure After This Part

```
backend/routing-service/
├── pom.xml                                              ← YOU CREATE THIS
└── src/main/
    ├── java/com/payflow/routing/
    │   └── RoutingServiceApplication.java               ← YOU CREATE THIS
    └── resources/
        └── application.yml                              ← YOU CREATE THIS
```

**NOTE:** No `config/SecurityConfig.java` — this service doesn't use Spring Security at all.

---

## 4. Step-by-Step: pom.xml

**File:** `backend/routing-service/pom.xml`

### Parent — Spring Boot Directly (NOT PayFlow Parent)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>
```

**🆕 MAJOR DIFFERENCE: Parent is `spring-boot-starter-parent`, NOT `payflow-payment-gateway`.**

| Previous Services (Identity, Merchant, Payment) | Routing Service |
|---|---|
| `<parent>com.payflow:payflow-payment-gateway</parent>` | `<parent>org.springframework.boot:spring-boot-starter-parent</parent>` |
| Versions inherited from parent POM | Versions managed locally in `<properties>` |
| Part of the multi-module Maven reactor | Standalone module with own BOM |

**WHY?** The routing service was developed independently — it doesn't share the same version management. `spring-boot-starter-parent` provides Spring Boot's dependency management (starter versions, plugin defaults) but nothing PayFlow-specific.

**`<relativePath/>`** — tells Maven "don't look for the parent POM locally, fetch it from Maven Central." Without this, Maven would look for a parent POM in the parent directory.

### Identity

```xml
    <groupId>com.payflow</groupId>
    <artifactId>routing-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>routing-service</name>
    <description>PayFlow Routing Service - ISO 8583, Netty, Smart Routing, Fraud Detection</description>
```

**NOTE:** Explicit `<groupId>` and `<version>` — because there's no PayFlow parent to inherit from.

### Properties — Explicit Version Management

```xml
    <properties>
        <java.version>17</java.version>
        <spring-cloud.version>2023.0.1</spring-cloud.version>
        <netty.version>4.1.108.Final</netty.version>
        <resilience4j.version>2.2.0</resilience4j.version>
        <aws-sdk.version>2.25.27</aws-sdk.version>
        <springdoc.version>2.5.0</springdoc.version>
    </properties>
```

**🆕 Previous services had NO `<properties>` block** — the parent POM managed everything.

| Property | Version | What It Controls |
|---|---|---|
| `java.version` | 17 | Java compiler target |
| `spring-cloud.version` | 2023.0.1 | Eureka Client, Config Client |
| `netty.version` | 4.1.108.Final | Netty TCP framework |
| `resilience4j.version` | 2.2.0 | Circuit breaker |
| `aws-sdk.version` | 2.25.27 | AWS DynamoDB client |
| `springdoc.version` | 2.5.0 | Swagger UI |

**WHY EXPLICIT VERSIONS?** Without a PayFlow parent managing versions, each dependency needs its version declared here. `${netty.version}` in the `<dependency>` references this property.

---

### Dependencies — What's Same

```xml
    <dependencies>
        <!-- PayFlow Common Library -->
        <dependency>
            <groupId>com.payflow</groupId>
            <artifactId>common-lib</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>
```

**NOTE:** Explicit `<version>1.0.0-SNAPSHOT</version>` — previous services didn't need this because the parent POM managed it.

```xml
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
```

Same starters as before — web (REST), actuator (health), validation (`@NotBlank`, `@DecimalMin`).

```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
```

Same — register with Eureka, fetch config from Config Server.

---

### 🆕 NEW Dependency #1: Netty

```xml
        <!-- Netty -->
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-all</artifactId>
            <version>${netty.version}</version>
        </dependency>
```

**WHAT:** Netty — an asynchronous, event-driven network framework for building high-performance protocol clients and servers.

**WHY ROUTING SERVICE NEEDS IT:**
```
Banks don't speak HTTP. They speak ISO 8583 over raw TCP.

HTTP (what Spring Boot uses):
  POST /api/authorize HTTP/1.1       ← text headers
  Content-Type: application/json     ← text
  {"pan":"4111...","amount":50000}   ← JSON text
  → Overhead: ~300 bytes of headers + JSON

TCP with ISO 8583 (what banks use):
  [0x02][0x00][bitmap][fields]       ← raw binary
  → Message: ~80 bytes total (no headers, no JSON)
  → Persistent connection (no connect/disconnect per request)
```

**WHAT IS `netty-all`?** A convenience "uber" dependency that includes ALL Netty modules:
- `netty-transport` — Channel, EventLoop, Bootstrap
- `netty-codec` — LengthFieldBasedFrameDecoder, encoders/decoders
- `netty-handler` — IdleStateHandler, ReadTimeoutHandler
- `netty-buffer` — ByteBuf (efficient byte manipulation)
- `netty-common` — Utilities

**WHY `netty-all` INSTEAD OF INDIVIDUAL MODULES?** Simpler to manage. In production, you'd import only the modules you need to reduce JAR size. For learning, `netty-all` avoids "missing module" errors.

---

### 🆕 NEW Dependency #2: Resilience4j

```xml
        <!-- Resilience4j -->
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-circuitbreaker</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>
```

**WHAT:** Resilience4j — a lightweight fault tolerance library for Java.

**WHY TWO DEPENDENCIES?**

| Dependency | What It Provides |
|---|---|
| `resilience4j-spring-boot3` | Spring Boot 3 auto-configuration: `@CircuitBreaker` annotation, actuator integration, YAML config |
| `resilience4j-circuitbreaker` | Core circuit breaker implementation: `CircuitBreakerConfig`, `CircuitBreakerRegistry` |

**WHY ROUTING SERVICE NEEDS IT:**
```
WITHOUT circuit breaker:
  Bank is down → every request waits 30 seconds → times out → 1000 requests pile up
  → Thread pool exhausted → routing service becomes unresponsive
  → Payment service waits → API Gateway times out → EVERYTHING GOES DOWN

WITH circuit breaker:
  Bank returns errors 5 times → circuit OPENS → next requests return fallback INSTANTLY
  → No thread pool exhaustion → routing service stays healthy
  → After 30 seconds → circuit tries 3 test requests → bank recovered? → CLOSE circuit
```

**REAL-WORLD ANALOGY:** A circuit breaker in your house. If too much current flows (bank errors), the breaker TRIPS (opens) to protect the house (your service). You reset it (half-open) after the problem is fixed.

---

### 🆕 NEW Dependency #3: AWS DynamoDB SDK

```xml
        <!-- AWS DynamoDB SDK -->
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>dynamodb</artifactId>
            <version>${aws-sdk.version}</version>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>dynamodb-enhanced</artifactId>
            <version>${aws-sdk.version}</version>
        </dependency>
```

**WHAT:** AWS SDK v2 for DynamoDB — Amazon's NoSQL database.

| Dependency | What It Provides |
|---|---|
| `dynamodb` | Low-level client: `DynamoDbClient`, raw `PutItem`/`GetItem` operations |
| `dynamodb-enhanced` | High-level client: annotated Java classes → DynamoDB tables (like JPA but for DynamoDB) |

**WHY DYNAMODB (NOT POSTGRESQL)?**
```
Bank routing metrics are:
  • Key-value: bankId → {successRate, avgLatency, totalCount}
  • Write-heavy: updated EVERY transaction (thousands per second)
  • No relationships: no JOINs, no foreign keys
  • No transactions: eventual consistency is fine

PostgreSQL: Designed for relational data, ACID transactions, complex queries
DynamoDB: Designed for key-value, high throughput, single-digit-ms latency

For this use case, DynamoDB is better. But in development, we use an in-memory
ConcurrentHashMap (no AWS needed locally).
```

---

### Remaining Dependencies

```xml
        <!-- SpringDoc OpenAPI -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```

Same as before. Note Lombok has `<optional>true</optional>` — it's a compile-time-only tool, not needed by consumers of this library.

---

### 🆕 DependencyManagement — Spring Cloud BOM

```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

**🆕 Previous services didn't have this** — the PayFlow parent POM handled it.

**WHAT IS A BOM (Bill of Materials)?**
```
Problem: Spring Cloud has 20+ modules. Each has its own version.
  spring-cloud-starter-netflix-eureka-client → which version?
  spring-cloud-starter-config → which version?
  If versions mismatch → "NoSuchMethodError" at runtime!

Solution: Import the Spring Cloud BOM.
  It says: "For Spring Cloud 2023.0.1, use Eureka 4.1.0, Config 4.1.0, etc."
  You just declare the starter — the BOM provides compatible versions.
```

**`<type>pom</type>` + `<scope>import</scope>`** — this special combination means "import this POM's `<dependencyManagement>` into mine." It's NOT a regular dependency — it doesn't add any JARs. It only provides version numbers.

---

### Build Section

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

**`<excludes>` for Lombok** — don't package Lombok into the fat JAR. Lombok generates code at compile time — it's not needed at runtime.

**PREVIOUS SERVICES DIDN'T HAVE THIS** because the PayFlow parent POM's `spring-boot-maven-plugin` didn't configure Lombok exclusion (Lombok was managed differently there).

---

### What's NOT Here (Compared to Payment Service)

| Payment Service Had | Routing Service Does NOT | Why |
|---|---|---|
| `spring-boot-starter-data-jpa` | ❌ | No SQL database |
| `spring-boot-starter-security` | ❌ | Internal-only service, no auth |
| `spring-boot-starter-data-redis` | ❌ | No idempotency needed |
| `spring-kafka` | ❌ | Doesn't publish events |
| `spring-cloud-starter-openfeign` | ❌ | IS the Feign target |
| `postgresql` | ❌ | No relational database |
| `flyway-core` | ❌ | No migrations |
| `mapstruct` | ❌ | Simple DTOs, manual mapping |
| `testcontainers` | ❌ | No Docker-based integration tests |

**This is the LEANEST service POM** — only what's needed for TCP networking, fraud detection, and routing.

---

## 5. Step-by-Step: application.yml

**File:** `backend/routing-service/src/main/resources/application.yml`

### Server + Spring Basics

```yaml
server:
  port: 8084
```

| Service | Port |
|---|---|
| API Gateway | 8080 |
| Identity | 8081 |
| Merchant | 8082 |
| Payment | 8083 |
| **Routing** | **8084** |

```yaml
spring:
  application:
    name: routing-service
  profiles:
    active: dev
  config:
    import: optional:configserver:http://localhost:8888
```

**`profiles.active: dev`** — 🆕 First time we see an explicit active profile. This activates the `dev` profile, which triggers `@Profile({"dev", "default", "docker"})` beans in `DynamoDbConfig` (the in-memory metrics repository). Production would use `@Profile("prod")` with real DynamoDB.

**NO `datasource` section** — there's no database. Previous services all had `spring.datasource.url`, `spring.jpa.*`, `spring.flyway.*`. None of that here.

### 🆕 Bank Simulator Connection

```yaml
# Bank Simulator Connection
bank:
  simulator:
    host: localhost
    port: 9090
    connect-timeout-ms: 5000
    response-timeout-seconds: 30
```

**COMPLETELY NEW configuration section.** No previous service had anything like this.

| Property | Value | Meaning |
|---|---|---|
| `host: localhost` | Bank simulator address | In production: `hdfc-bank-gateway.example.com` |
| `port: 9090` | Bank simulator TCP port | Banks use TCP ports, not HTTP ports |
| `connect-timeout-ms: 5000` | 5 seconds | Max time to establish TCP connection |
| `response-timeout-seconds: 30` | 30 seconds | Max time to wait for bank to respond |

**THESE ARE TCP SETTINGS, NOT HTTP.** Previous services configured HTTP timeouts (Feign connect/read). This configures raw TCP socket timeouts.

**WHY 30 SECONDS RESPONSE TIMEOUT?** Banks can be slow:
```
Fast response: Card present (POS terminal) → 1-2 seconds
Normal response: Card not present (online) → 2-5 seconds
Slow response: International transaction → 5-15 seconds
Very slow: Bank maintenance window → 15-30 seconds
```

### 🆕 Smart Routing Configuration

```yaml
# Smart Routing Configuration
routing:
  epsilon: 0.10  # 10% exploration rate
```

**THE EPSILON-GREEDY PARAMETER.** This single number controls the explore/exploit balance:

| Epsilon Value | Behavior | Use Case |
|---|---|---|
| `0.00` | 100% exploit (always pick best bank) | Never discover improvements |
| `0.10` | 90% exploit, 10% explore | **Our choice — balanced** |
| `0.50` | 50/50 | Too much exploration, wastes good routing |
| `1.00` | 100% explore (random bank every time) | No intelligence at all |

**WHY 10%?** Industry standard for multi-armed bandit. Enough exploration to discover if a bank improved, but sends 90% of traffic to the known-best bank.

### 🆕 Fraud Detection Thresholds

```yaml
# Fraud Detection Thresholds
fraud:
  rules:
    velocity-threshold: 5        # Max transactions per minute
    amount-threshold: 50000      # Amount threshold in base currency
    velocity-window-ms: 60000    # 1 minute window
  weight:
    rules: 0.6                   # 60% weight for rule engine
    model: 0.4                   # 40% weight for ML model
```

| Property | Value | Meaning |
|---|---|---|
| `velocity-threshold: 5` | 5 txns/min | More than 5 transactions per minute from the same merchant → suspicious |
| `amount-threshold: 50000` | ₹50,000 | Transactions above this trigger elevated fraud scoring |
| `velocity-window-ms: 60000` | 60 seconds | Time window for counting transactions (1 minute) |
| `weight.rules: 0.6` | 60% | Rule engine contributes 60% of final fraud score |
| `weight.model: 0.4` | 40% | ML model contributes 40% of final fraud score |

**WHY 60/40 SPLIT?**
```
Rules (60%): Fast, deterministic, zero false negatives for KNOWN fraud patterns
  → "5 transactions in 1 minute from same merchant" = definitely suspicious
  → Rules NEVER miss this (they check it explicitly)

ML (40%): Adaptive, catches NOVEL patterns rules can't express
  → "Night transaction + round amount + new card + prepaid" = suspicious COMBINATION
  → No single rule would catch this, but the pattern is suspicious

Together: Better precision (fewer false positives) AND recall (fewer missed fraud)
         than either approach alone
```

### 🆕 Netty Configuration

```yaml
# Netty Configuration
netty:
  worker-threads: 4
  connection-pool:
    max-connections: 10
    max-idle-time-seconds: 60
```

| Property | Value | Meaning |
|---|---|---|
| `worker-threads: 4` | 4 threads | Netty event loop thread count (handles ALL TCP I/O) |
| `max-connections: 10` | 10 connections | Max simultaneous TCP connections to banks |
| `max-idle-time-seconds: 60` | 60 seconds | Close idle connections after 1 minute |

**WHY ONLY 4 THREADS?**
```
Traditional Java: 1 thread per TCP connection
  → 10 connections = 10 threads
  → 1,000 connections = 1,000 threads (memory: ~1GB just for stacks!)

Netty (NIO): 4 threads handle ALL connections using event loop
  → Thread 1 polls: "Any data on connections 1-250?"
  → Thread 2 polls: "Any data on connections 251-500?"
  → 4 threads can handle 10,000+ connections

For routing service: 4 threads is plenty (we connect to ~4 banks)
```

### 🆕 AWS DynamoDB Configuration

```yaml
# AWS DynamoDB Configuration
aws:
  dynamodb:
    endpoint: http://localhost:4566  # LocalStack endpoint
  region: us-east-1
  access-key: localstack
  secret-key: localstack
```

| Property | Value | Meaning |
|---|---|---|
| `endpoint: http://localhost:4566` | LocalStack URL | LocalStack emulates AWS services locally |
| `region: us-east-1` | AWS region | Required by AWS SDK even with LocalStack |
| `access-key: localstack` | Fake credentials | LocalStack accepts any credentials |
| `secret-key: localstack` | Fake credentials | Not real AWS keys! |

**WHAT IS LOCALSTACK?** A tool that emulates AWS services (DynamoDB, S3, SQS, etc.) on your local machine. You don't need an AWS account for development.

**IN PRACTICE:** The `InMemoryRoutingMetricsRepository` (activated by `@Profile("dev")`) means DynamoDB isn't even used locally. These settings are here for when you want to test with LocalStack.

### 🆕 Resilience4j Circuit Breaker

```yaml
# Resilience4j Circuit Breaker
resilience4j:
  circuitbreaker:
    failure-rate-threshold: 50
    slow-call-rate-threshold: 80
    slow-call-duration-threshold-seconds: 5
    sliding-window-size: 10
    minimum-number-of-calls: 5
    wait-duration-in-open-state-seconds: 30
    permitted-calls-in-half-open: 3
    instances:
      bankCommunication:
        registerHealthIndicator: true
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
        slidingWindowType: COUNT_BASED
        minimumNumberOfCalls: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        slowCallDurationThreshold: 5s
        slowCallRateThreshold: 80
```

**THIS IS THE MOST COMPLEX YAML CONFIG IN THE PROJECT.** Let's break it down:

| Property | Value | What It Means |
|---|---|---|
| `failureRateThreshold: 50` | 50% | If 50%+ of requests FAIL → open the circuit |
| `slowCallRateThreshold: 80` | 80% | If 80%+ of requests are SLOW → open the circuit |
| `slowCallDurationThreshold: 5s` | 5 seconds | A call taking >5s is considered "slow" |
| `slidingWindowSize: 10` | 10 calls | Evaluate the LAST 10 calls (not all-time) |
| `slidingWindowType: COUNT_BASED` | Count | Window is last N calls (not last N seconds) |
| `minimumNumberOfCalls: 5` | 5 calls | Don't evaluate until at least 5 calls happened |
| `waitDurationInOpenState: 30s` | 30 seconds | Stay OPEN for 30s before trying HALF-OPEN |
| `permittedNumberOfCallsInHalfOpenState: 3` | 3 calls | In HALF-OPEN, allow 3 test calls |
| `automaticTransitionFromOpenToHalfOpenEnabled: true` | Auto | Automatically try HALF-OPEN after 30s |
| `registerHealthIndicator: true` | Health | Expose circuit state in `/actuator/health` |

**Circuit Breaker State Machine:**
```
  ┌────────────────────────────────────────────────────────────────────┐
  │                                                                     │
  │  CLOSED ──────────────── 50% failures ──────────────→ OPEN         │
  │  (normal)                in last 10 calls              (blocking)   │
  │                                                          │          │
  │     ▲                                              wait 30s        │
  │     │                                                    │          │
  │     │                                                    ▼          │
  │     └──── success rate OK ◄──── HALF-OPEN                          │
  │           (3 test calls)        (testing)                           │
  │                                    │                                │
  │                              still failing?                         │
  │                                    │                                │
  │                                    └──────────→ back to OPEN       │
  │                                                                     │
  └────────────────────────────────────────────────────────────────────┘
```

**`instances.bankCommunication`** — this is the named instance used by `@CircuitBreaker(name = "bankCommunication")` in the controller. You can have multiple circuit breakers with different configs.

### Eureka + Actuator + Logging + Swagger

```yaml
# Eureka Client
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

Same as previous services. `fetch-registry: true` because routing-service is FOUND by payment-service via Eureka (Payment's Feign client looks up "routing-service").

```yaml
# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers,circuitbreakerevents
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
```

**🆕 NEW actuator endpoints:**

| Endpoint | What It Shows |
|---|---|
| `circuitbreakers` | Current state of all circuit breakers (CLOSED/OPEN/HALF_OPEN) |
| `circuitbreakerevents` | History of state transitions and calls |
| `show-details: always` | Show full health details (not just UP/DOWN) |
| `health.circuitbreakers.enabled: true` | Include circuit breaker status in `/actuator/health` |

Previous services only exposed `health,info,metrics,prometheus`. Routing adds circuit breaker monitoring.

```yaml
# Logging
logging:
  level:
    com.payflow.routing: DEBUG
    io.netty: INFO
    io.github.resilience4j: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
```

| Logger | Level | Why |
|---|---|---|
| `com.payflow.routing: DEBUG` | Verbose | See fraud scores, routing decisions, ISO 8583 messages |
| `io.netty: INFO` | Normal | Netty is verbose at DEBUG (channel events, buffer operations) |
| `io.github.resilience4j: INFO` | Normal | See circuit breaker state transitions |

**`pattern.console`** — 🆕 Custom log format. Previous services used Spring Boot's default. This adds thread name (useful for debugging Netty's multi-threaded I/O).

```yaml
# SpringDoc OpenAPI
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
```

Same as before — Swagger at `http://localhost:8084/swagger-ui.html`.

---

## 6. Step-by-Step: RoutingServiceApplication.java

**File:** `src/main/java/com/payflow/routing/RoutingServiceApplication.java`

```java
package com.payflow.routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
```

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class RoutingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoutingServiceApplication.class, args);
    }
}
```

### Annotation Comparison — All 4 Services

| Service | @SpringBootApplication | @EnableDiscoveryClient | @EnableFeignClients | @EnableScheduling |
|---|---|---|---|---|
| Identity | ✅ | ❌ (auto) | ❌ | ❌ |
| Merchant | ✅ | ✅ | ❌ | ❌ |
| Payment | ✅ | ❌ (auto) | ✅ | ❌ |
| **Routing** | ✅ | ✅ | ❌ | ✅ |

### 🆕 `@EnableScheduling`

**WHAT:** "Spring, enable `@Scheduled` annotation support. I want to run methods on a timer."

**WHY:** Future use — scheduled tasks for:
- Periodically checking bank health (ping with MTI 0800 network management message)
- Cleaning up stale routing metrics
- Refreshing bank route configurations from DynamoDB

**WITHOUT THIS:**
```java
@Scheduled(fixedRate = 60000)  // Run every 60 seconds
public void checkBankHealth() { ... }
// → This annotation is IGNORED without @EnableScheduling on the main class
```

**NOTE:** Currently no `@Scheduled` methods exist in the codebase. The annotation is preparation for future features. It doesn't harm anything — it just enables the infrastructure.

### `@EnableDiscoveryClient`

Same as Merchant Service — explicitly registers with Eureka. Payment Service used auto-detection instead. Both approaches work.

### No `@EnableFeignClients`

Routing service is the **target** of Feign calls, not the caller. Payment Service's `@FeignClient(name = "routing-service")` calls THIS service. Routing doesn't call anyone via Feign.

### No SecurityConfig

**This is the only service WITHOUT a SecurityConfig.java.** All endpoints are accessible without authentication because:
1. This service is internal-only (not exposed through API Gateway)
2. Only payment-service calls it (via Feign within the cluster)
3. In production, network-level security (Kubernetes network policies, VPC) protects it

---

## 7. How These 3 Files Work Together

```
┌───────────────────────────────────────────────────────────────────────────┐
│                    ROUTING SERVICE STARTUP                                 │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  1. pom.xml → Maven downloads: Web + Netty + Resilience4j + DynamoDB    │
│     NOTE: No JPA, no Flyway, no PostgreSQL driver!                       │
│                                                                           │
│  2. RoutingServiceApplication.main()                                     │
│     @SpringBootApplication → scan + auto-configure                       │
│     @EnableDiscoveryClient → register with Eureka                        │
│     @EnableScheduling → enable @Scheduled methods (future use)           │
│                                                                           │
│  3. application.yml is read:                                              │
│     port=8084, profile=dev, bank simulator at localhost:9090             │
│                                                                           │
│  4. Auto-configuration kicks in:                                          │
│     spring-web → Embedded Tomcat (for REST endpoint)                     │
│     NO JPA auto-config (no spring-data-jpa in pom)                      │
│     NO Flyway auto-config (no flyway-core in pom)                       │
│     Resilience4j → CircuitBreakerRegistry from YAML config              │
│                                                                           │
│  5. Component scanning finds:                                            │
│     NettyConfig → creates EventLoopGroup (4 threads)                    │
│     DynamoDbConfig → creates DynamoDbClient + InMemoryMetricsRepo       │
│     Resilience4jConfig → creates CircuitBreakerRegistry                 │
│     BankNettyClient → TCP client for bank communication                 │
│     FraudDetectionService → combines RuleEngine + DecisionTreeScorer    │
│     SmartRoutingService → epsilon-greedy bank selection                  │
│     RoutingController → POST /internal/route                            │
│                                                                           │
│  6. NO Flyway migrations (no database!)                                  │
│  7. NO Hibernate validation (no JPA entities!)                           │
│  8. Tomcat starts on 8084                                                │
│  9. Registers with Eureka: "I'm routing-service at :8084"               │
│                                                                           │
│  READY: Accepting HTTP on :8084                                          │
│         Netty ready to connect to bank on :9090 (on-demand)             │
│         No persistent connections yet (connects when first request comes)│
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Different POM parent** | Routing uses `spring-boot-starter-parent` directly, not PayFlow parent — manages own versions |
| 2 | **`<properties>` for versions** | When no parent manages versions, declare them in `<properties>` and reference with `${}` |
| 3 | **`<dependencyManagement>` BOM import** | Spring Cloud BOM provides compatible versions for Eureka/Config without explicit version per dependency |
| 4 | **`netty-all`** | Uber dependency containing all Netty modules — TCP, codecs, handlers, buffers |
| 5 | **Resilience4j (2 deps)** | `spring-boot3` for annotation support + `circuitbreaker` for core implementation |
| 6 | **AWS DynamoDB SDK (2 deps)** | `dynamodb` for low-level + `dynamodb-enhanced` for annotation-based table mapping |
| 7 | **No JPA/Flyway/Security** | Routing doesn't need SQL, doesn't need auth — only TCP + binary protocol |
| 8 | **`spring.profiles.active: dev`** | Activates dev profile → in-memory repositories instead of real DynamoDB |
| 9 | **Bank simulator TCP config** | Host/port/timeouts for raw TCP connection (not HTTP) |
| 10 | **`routing.epsilon: 0.10`** | The explore/exploit ratio — 10% random, 90% best bank |
| 11 | **Fraud weight config** | `rules: 0.6` + `model: 0.4` — configurable scoring weights |
| 12 | **Circuit breaker YAML** | 10+ properties controlling CLOSED→OPEN→HALF_OPEN transitions |
| 13 | **`@EnableScheduling`** | Enables `@Scheduled` for future timed tasks (bank health checks) |
| 14 | **No SecurityConfig** | Internal-only service — network security replaces application security |
| 15 | **Lombok exclusion in build** | `<excludes>` in spring-boot-maven-plugin — don't package compile-time-only tools |
| 16 | **Actuator circuit breaker endpoints** | `circuitbreakers` + `circuitbreakerevents` — monitor circuit state via HTTP |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part09-routing-service-overview.md) | Routing Service Overview |
| **Part 9a** | **Project Setup** (You are here) |
| [Part 9b](./phase4-part09b-iso8583-foundation.md) | ISO 8583 Foundation |
| [Part 9c](./phase4-part09c-iso8583-messages.md) | ISO 8583 Messages + Tests |
| [Part 9d](./phase4-part09d-netty-config-client.md) | Netty Config + Client |
| [Part 9e](./phase4-part09e-netty-pipeline.md) | Netty Pipeline |
| [Part 9f](./phase4-part09f-fraud-rule-engine.md) | Fraud Rule Engine |
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML + Service + Tests |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Next: [Part 9b — ISO 8583 Foundation (Field, Bitmap, Constants)](./phase4-part09b-iso8583-foundation.md) →*
