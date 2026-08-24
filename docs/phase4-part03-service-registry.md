# 🏗️ Phase 4 Part 3: Service Registry (Eureka)

> **"In a microservices world, hardcoded URLs are a liability. Service discovery makes your architecture resilient to scale changes and instance failures."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 3 of 12 |
| **Module** | `service-registry` |
| **Package** | `com.payflow.registry` |
| **Port** | 8761 |
| **Previous** | [Phase 4 Part 2: Common Library](./phase4-part02-common-lib.md) |
| **Next** | [Phase 4 Part 4: Config Server](./phase4-part04-config-server.md) |
| **Technologies** | Spring Cloud Netflix Eureka Server, Spring Boot 3.2 |
| **Dashboard** | http://localhost:8761 |

---

## 📖 Table of Contents

1. [Overview](#1-overview)
2. [Service Discovery Concepts](#2-service-discovery-concepts)
3. [Architecture Diagram](#3-architecture-diagram)
4. [Application Class](#4-application-class)
5. [Configuration (application.yml)](#5-configuration-applicationyml)
6. [POM Dependencies](#6-pom-dependencies)
7. [Dockerfile (Multi-Stage Build)](#7-dockerfile-multi-stage-build)
8. [How Services Register](#8-how-services-register)
9. [Eureka Dashboard](#9-eureka-dashboard)
10. [Self-Preservation Mode](#10-self-preservation-mode)
11. [Health Checks & Heartbeats](#11-health-checks--heartbeats)
12. [Production Considerations](#12-production-considerations)
13. [What You Learned](#13-what-you-learned)
14. [Document Index](#14-document-index)
15. [Next Steps](#15-next-steps)

---

## 1. Overview

The **Service Registry** is the first infrastructure service that must be running before any other PayFlow microservice starts. It provides:

- **Service Registration** — Each microservice registers its hostname, port, and health status on startup
- **Service Discovery** — Services query the registry to find other services by name (e.g., `payment-service`)
- **Load Balancing** — When multiple instances exist, the registry provides all endpoints for client-side load balancing
- **Health Monitoring** — Tracks which instances are alive via periodic heartbeats

In PayFlow, we use **Netflix Eureka** (Spring Cloud Netflix) as our service registry.

---

## 2. Service Discovery Concepts

### Why Service Discovery?

Without service discovery, you'd hardcode URLs like:
```yaml
# ❌ Bad: Hardcoded URLs break when instances change
payment-service:
  url: http://192.168.1.50:8083
```

With service discovery, you reference logical service names:
```yaml
# ✅ Good: Logical names resolved at runtime
spring:
  cloud:
    gateway:
      routes:
        - id: payment-service
          uri: lb://payment-service    # "lb://" = load-balanced via Eureka
```

### Key Concepts

| Concept | Description |
|---------|-------------|
| **Registration** | Service sends its metadata (host, port, status) to Eureka on startup |
| **Heartbeat** | Every 30 seconds, each service sends a "I'm alive" signal to Eureka |
| **Eviction** | If no heartbeat received for 90 seconds, instance is removed from registry |
| **Self-Preservation** | If too many instances stop heartbeating simultaneously, Eureka assumes network issue and stops evictions |
| **Registry Fetch** | Clients fetch the full registry every 30 seconds and cache it locally |
| **Client-Side LB** | Clients (via Spring Cloud LoadBalancer) pick an instance from cached registry |

### Registration Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Service Instance Lifecycle                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. STARTUP        2. REGISTER       3. HEARTBEAT      4. SHUTDOWN         │
│  ─────────        ──────────        ───────────       ──────────           │
│  Service boots →  POST /eureka →   PUT /eureka →    DELETE /eureka         │
│  Loads config     (host, port,      (every 30s)      (graceful) or        │
│                    status: UP)                         evicted (90s)        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Architecture Diagram

```
                     ┌──────────────────────────────────┐
                     │       SERVICE REGISTRY           │
                     │        (Eureka Server)           │
                     │         Port: 8761              │
                     │                                  │
                     │  ┌────────────────────────────┐ │
                     │  │   Registry Table            │ │
                     │  │                            │ │
                     │  │  identity-service → :8081  │ │
                     │  │  payment-service  → :8083  │ │
                     │  │  merchant-service → :8082  │ │
                     │  │  settlement-svc   → :8085  │ │
                     │  │  notification-svc → :8086  │ │
                     │  │  webhook-service  → :8087  │ │
                     │  │  config-server    → :8888  │ │
                     │  │  api-gateway      → :8080  │ │
                     │  └────────────────────────────┘ │
                     └──────────┬───────────────────────┘
                                │
            ┌───────────────────┼───────────────────────┐
            │                   │                       │
            ▼                   ▼                       ▼
    ┌──────────────┐   ┌──────────────┐       ┌──────────────┐
    │  API Gateway │   │   Payment    │       │  Settlement  │
    │   :8080      │   │   Service    │       │   Service    │
    │              │   │    :8083     │       │    :8085     │
    │ Discovers:   │   │ Discovers:   │       │ Discovers:   │
    │ all services │   │ routing-svc  │       │ payment-svc  │
    └──────────────┘   └──────────────┘       └──────────────┘

    ─────────────── ♥ Heartbeat (every 30s) ───────────────►
    ◄─────────────── Registry Fetch (every 30s) ────────────
```

---

## 4. Application Class

The entire Eureka Server is enabled with a single annotation:

```java
package com.payflow.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka Server — Service Discovery for PayFlow microservices.
 * All services register here on startup and discover each other via this registry.
 * Port: 8761
 */
@SpringBootApplication
@EnableEurekaServer  // ← This single annotation transforms this into a Eureka Server
public class ServiceRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceRegistryApplication.class, args);
    }
}
```

**That's it.** The `@EnableEurekaServer` annotation:
- Starts the Eureka REST API on `/eureka/`
- Starts the Eureka Dashboard on the root path `/`
- Handles registration, heartbeats, and eviction automatically

---

## 5. Configuration (application.yml)

```yaml
server:
  port: 8761  # Standard Eureka port

spring:
  application:
    name: service-registry  # How this service identifies itself

eureka:
  instance:
    hostname: localhost
  client:
    register-with-eureka: false    # This IS the registry — don't register with itself
    fetch-registry: false          # No need to fetch from itself
    service-url:
      defaultZone: http://${eureka.instance.hostname}:${server.port}/eureka/
  server:
    enable-self-preservation: false  # Disable in dev (enable in prod!)
    eviction-interval-timer-in-ms: 5000  # Check for dead instances every 5s (default: 60s)
```

### Configuration Explained

| Property | Value | Why |
|----------|-------|-----|
| `register-with-eureka: false` | Don't register | This IS the registry — registering with itself is circular |
| `fetch-registry: false` | Don't fetch | No point fetching its own registry |
| `enable-self-preservation: false` | Disabled in dev | In development, we want fast eviction of dead instances |
| `eviction-interval-timer-in-ms: 5000` | 5 seconds | Faster cleanup in dev (production uses 60s default) |

---

## 6. POM Dependencies

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

    <artifactId>service-registry</artifactId>
    <name>PayFlow Service Registry</name>
    <description>Eureka Server for service discovery</description>

    <dependencies>
        <!-- Eureka Server — provides the full registry (REST API, dashboard, replication) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
        </dependency>
        <!-- Actuator — provides /actuator/health for Docker HEALTHCHECK -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>

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

**Note:** Two dependencies — `eureka-server` provides the full registry (Jersey REST API, dashboard UI, replication logic), and `actuator` provides the `/actuator/health` endpoint for Docker health checks.

---

## 7. Dockerfile (Multi-Stage Build)

```dockerfile
# Stage 1: Build — uses full Maven image to compile
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Copy parent pom and all modules (Maven reactor requires all to be present)
COPY pom.xml .
COPY common-lib ./common-lib
COPY service-registry ./service-registry
COPY config-server ./config-server
COPY api-gateway ./api-gateway
COPY identity-service ./identity-service
COPY merchant-service ./merchant-service
COPY payment-service ./payment-service
COPY routing-service ./routing-service
COPY settlement-service ./settlement-service
COPY webhook-service ./webhook-service
COPY notification-service ./notification-service
COPY bank-simulator ./bank-simulator

# Build only the target service and its dependencies
RUN mvn clean package -pl service-registry -am -DskipTests -B

# Stage 2: Runtime — minimal JRE Alpine image (~180MB vs ~800MB)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow
COPY --from=builder /app/service-registry/target/*.jar app.jar
EXPOSE 8761
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8761/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Note:** The build context is `backend/` (not `backend/service-registry/`). All modules are copied because Maven's parent pom declares them all in its reactor. The `-pl service-registry -am` flag builds only the target service and its dependencies.

### Multi-Stage Build Benefits

| Stage | Image | Size | Purpose |
|-------|-------|------|---------|
| Builder | `maven:3.9-eclipse-temurin-17` | ~800MB | Compile Java, resolve dependencies |
| Runtime | `eclipse-temurin:17-jre-alpine` | ~180MB | Run the application (no compiler, no Maven) |

The final image contains only the JRE and the application JAR — nothing else.

### Build & Run

```bash
# Build the Docker image
docker build -t payflow/service-registry:latest -f backend/service-registry/Dockerfile backend/

# Run the container
docker run -d \
  --name service-registry \
  -p 8761:8761 \
  payflow/service-registry:latest
```

---

## 8. How Services Register

Every other PayFlow service includes `eureka-client` in its dependencies:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

And configures the registry URL:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

**On startup**, each service:
1. Sends a POST to `http://localhost:8761/eureka/apps/{SERVICE-NAME}`
2. Includes metadata: hostname, port, health URL, status (UP)
3. Starts sending heartbeats every 30 seconds
4. Fetches the full registry and caches it locally

**Service-to-service communication** uses `lb://` prefix (load-balanced):

```yaml
# API Gateway route — "lb://" tells Spring Cloud to resolve via Eureka
routes:
  - id: payment-service
    uri: lb://payment-service    # Resolved to http://192.168.x.x:8083
    predicates:
      - Path=/v1/payments/**
```

---

## 9. Eureka Dashboard

The Eureka Dashboard is available at **http://localhost:8761** and provides:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Eureka Dashboard (http://localhost:8761)          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  System Status                                                      │
│  ─────────────                                                      │
│  Environment: test                                                  │
│  Data center: default                                               │
│  Current time: 2024-01-15T10:30:00Z                                │
│  Uptime: 2d 4h 30m                                                 │
│                                                                     │
│  Instances currently registered with Eureka                         │
│  ──────────────────────────────────────────                         │
│  ┌──────────────────────┬─────────────────────────────────────┐    │
│  │ Application          │ Status                              │    │
│  ├──────────────────────┼─────────────────────────────────────┤    │
│  │ API-GATEWAY          │ UP (1) - 192.168.1.10:8080         │    │
│  │ IDENTITY-SERVICE     │ UP (1) - 192.168.1.10:8081         │    │
│  │ MERCHANT-SERVICE     │ UP (1) - 192.168.1.10:8082         │    │
│  │ PAYMENT-SERVICE      │ UP (1) - 192.168.1.10:8083         │    │
│  │ SETTLEMENT-SERVICE   │ UP (1) - 192.168.1.10:8085         │    │
│  │ NOTIFICATION-SERVICE │ UP (1) - 192.168.1.10:8086         │    │
│  │ WEBHOOK-SERVICE      │ UP (1) - 192.168.1.10:8087         │    │
│  │ CONFIG-SERVER        │ UP (1) - 192.168.1.10:8888         │    │
│  └──────────────────────┴─────────────────────────────────────┘    │
│                                                                     │
│  General Info                                                       │
│  ────────────                                                       │
│  Total instances: 8                                                 │
│  Renewals threshold: 12                                             │
│  Renewals (last min): 16                                            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Dashboard Information

| Section | What It Shows |
|---------|--------------|
| **System Status** | Environment, data center, uptime |
| **Instances** | All registered services with IP, port, and status |
| **General Info** | Renewal thresholds, self-preservation status |
| **Instance Info** | Click on an instance for detailed metadata |

---

## 10. Self-Preservation Mode

### What Is Self-Preservation?

If Eureka detects that more than 15% of registered instances have stopped sending heartbeats within the last renewal period, it enters **self-preservation mode**.

**Normal behavior:** Instance stops heartbeating → Eureka evicts it after 90 seconds.

**Self-preservation:** Many instances stop heartbeating → Eureka assumes network partition → Keeps all registrations (even potentially dead ones) rather than evicting healthy instances that just can't reach Eureka.

### When It Activates

```
Renewal Threshold = Number of registered instances × 2 (heartbeats/min) × 0.85
If actual renewals < threshold → SELF-PRESERVATION ON
```

### Configuration

```yaml
eureka:
  server:
    # Production: enable self-preservation (default: true)
    enable-self-preservation: true
    
    # Renewal percent threshold (default: 0.85 = 85%)
    renewal-percent-threshold: 0.85
    
    # Development: disable for fast eviction
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000
```

### Our Decision

| Environment | Self-Preservation | Eviction Interval | Reason |
|-------------|-------------------|-------------------|--------|
| Development | **Disabled** | 5 seconds | Fast feedback when stopping services |
| Production | **Enabled** | 60 seconds (default) | Protect against network partitions |

---

## 11. Health Checks & Heartbeats

### Heartbeat Flow

```
┌──────────────┐         PUT /eureka/apps/PAYMENT-SERVICE/instance-id         ┌──────────────┐
│   Payment    │ ──────────────────────────────────────────────────────────► │   Eureka     │
│   Service    │         (every 30 seconds — "I'm still alive")              │   Server     │
│   :8083      │                                                              │   :8761      │
│              │ ◄────── 200 OK (acknowledged) ────────────────────────────── │              │
└──────────────┘                                                              └──────────────┘
```

### Configuring Client Heartbeats

```yaml
# In each microservice's application.yml
eureka:
  instance:
    lease-renewal-interval-in-seconds: 30   # Send heartbeat every 30s (default)
    lease-expiration-duration-in-seconds: 90 # Evict if no heartbeat for 90s (default)
  client:
    registry-fetch-interval-seconds: 30     # Refresh local cache every 30s
```

### Actuator Health Integration

Eureka integrates with Spring Boot Actuator:

```yaml
eureka:
  instance:
    health-check-url-path: /actuator/health
    status-page-url-path: /actuator/info
```

If `/actuator/health` returns DOWN, Eureka marks the instance as OUT_OF_SERVICE.

---

## 12. Production Considerations

### High Availability (Peer Replication)

In production, run multiple Eureka instances that replicate with each other:

```yaml
# eureka-server-1
eureka:
  instance:
    hostname: eureka1.payflow.internal
  client:
    service-url:
      defaultZone: http://eureka2.payflow.internal:8761/eureka/

# eureka-server-2
eureka:
  instance:
    hostname: eureka2.payflow.internal
  client:
    service-url:
      defaultZone: http://eureka1.payflow.internal:8761/eureka/
```

### Security (Basic Auth)

```yaml
# Add spring-boot-starter-security to service-registry
spring:
  security:
    user:
      name: eureka
      password: ${EUREKA_PASSWORD}
```

### Recommended Production Settings

| Setting | Dev Value | Prod Value |
|---------|-----------|-----------|
| `enable-self-preservation` | false | true |
| `eviction-interval-timer-in-ms` | 5000 | 60000 |
| `lease-renewal-interval-in-seconds` | 30 | 30 |
| `lease-expiration-duration-in-seconds` | 90 | 90 |
| Instances | 1 | 2-3 (peer replicated) |
| Security | None | Basic Auth + TLS |

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | Service Discovery | Services find each other by name, not hardcoded URLs |
| 2 | `@EnableEurekaServer` | Single annotation transforms a Spring Boot app into a full registry |
| 3 | Heartbeats | Every 30 seconds, each instance proves it's alive |
| 4 | Eviction | After 90 seconds of silence, the instance is removed |
| 5 | Self-Preservation | Protects against mass evictions during network partitions |
| 6 | `lb://` prefix | Tells Spring Cloud to resolve service name via Eureka |
| 7 | Registry Fetch | Clients cache the full registry locally (30s refresh) |
| 8 | Multi-stage Docker | Builder stage compiles; runtime stage runs (~180MB final image) |
| 9 | `register-with-eureka: false` | The registry itself should NOT register with itself |
| 10 | Dashboard | http://localhost:8761 shows all registered instances at a glance |
| 11 | Peer Replication | Production Eureka clusters replicate registrations between nodes |
| 12 | Actuator Integration | Eureka uses `/actuator/health` to track instance health |

---

## 📚 Document Index

| Part | Title | Status |
|------|-------|--------|
| 4.01 | Parent POM & Maven Setup | ✅ Complete |
| 4.02 | Common Library | ✅ Complete |
| **4.03** | **Service Registry (Eureka)** | **📍 You are here** |
| 4.04 | Config Server | ⏭️ Next |
| 4.05 | API Gateway | 🔲 Pending |
| 4.06a | Identity Service — Entities | 🔲 Pending |
| 4.06b | Identity Service — Auth Logic | 🔲 Pending |
| 4.07 | Merchant Service | 🔲 Pending |
| 4.08 | Payment Service | 🔲 Pending |
| 4.09 | Settlement Service | 🔲 Pending |
| 4.10 | Notification & Webhook Services | 🔲 Pending |
| 4.11 | Docker Compose & Integration | 🔲 Pending |
| 4.12 | Testing Strategy | 🔲 Pending |

---

## 🚀 Next Steps

In **Phase 4 Part 4**, we build the **Config Server** — a centralized configuration management system that serves YAML configs to all microservices, supporting environment-specific profiles and runtime config refresh.

→ [Continue to Phase 4 Part 4: Config Server](./phase4-part04-config-server.md)
