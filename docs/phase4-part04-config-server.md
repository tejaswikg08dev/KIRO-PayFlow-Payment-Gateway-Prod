# 🏗️ Phase 4 Part 4: Config Server

> **"Externalized configuration is not a luxury — it's a necessity. One config change shouldn't require rebuilding and redeploying every microservice."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 4 of 12 |
| **Module** | `config-server` |
| **Package** | `com.payflow.config` |
| **Port** | 8888 |
| **Previous** | [Phase 4 Part 3: Service Registry (Eureka)](./phase4-part03-service-registry.md) |
| **Next** | [Phase 4 Part 5: API Gateway](./phase4-part05-api-gateway.md) |
| **Technologies** | Spring Cloud Config Server, Spring Boot 3.2, Eureka Client |
| **Config Backend** | Native (classpath) — `/configurations/` directory |

---

## 📖 Table of Contents

1. [Overview](#1-overview)
2. [Centralized Configuration Concepts](#2-centralized-configuration-concepts)
3. [Architecture Diagram](#3-architecture-diagram)
4. [Application Class](#4-application-class)
5. [Config Server Configuration](#5-config-server-configuration)
6. [POM Dependencies](#6-pom-dependencies)
7. [Service Configuration Files](#7-service-configuration-files)
8. [Shared Configuration (application.yml)](#8-shared-configuration-applicationyml)
9. [How Services Consume Config](#9-how-services-consume-config)
10. [Config Refresh Mechanism](#10-config-refresh-mechanism)
11. [Native vs Git-Backed Configs](#11-native-vs-git-backed-configs)
12. [Environment Profiles](#12-environment-profiles)
13. [Configuration Encryption](#13-configuration-encryption)
14. [Dockerfile](#14-dockerfile)
15. [Testing the Config Server](#15-testing-the-config-server)
16. [What You Learned](#16-what-you-learned)
17. [Document Index](#17-document-index)
18. [Next Steps](#18-next-steps)

---

## 1. Overview

The **Config Server** provides centralized, versioned configuration for all PayFlow microservices. Instead of each service carrying its own `application.yml` with environment-specific values (database URLs, secrets, feature flags), they fetch configuration from the Config Server at startup.

### Why Centralized Config?

| Problem | Solution |
|---------|----------|
| Database URL changes | Update ONE config file, restart affected services |
| Different configs per environment (dev/staging/prod) | Profile-based configs: `identity-service-docker.yml` |
| Secrets management | Encrypt sensitive values in config files |
| Config drift between services | All configs in one place, easy to audit |
| Rebuilding JARs for config changes | Services fetch config at runtime |

---

## 2. Centralized Configuration Concepts

### How It Works

```
┌─────────────────┐     GET /identity-service/default     ┌─────────────────┐
│  Identity       │ ─────────────────────────────────────► │  Config Server  │
│  Service        │                                        │  :8888          │
│  :8081          │ ◄───── Returns YAML config ─────────── │                 │
│                 │                                        │  Reads from:    │
│  Merges with    │                                        │  /configurations│
│  local config   │                                        │  ├── identity-service.yml
└─────────────────┘                                        │  ├── payment-service.yml
                                                           │  ├── application.yml (shared)
                                                           │  └── ...
                                                           └─────────────────┘
```

### Config Resolution Order (Highest Priority First)

1. **Service-specific profile config** — `identity-service-docker.yml`
2. **Service-specific default config** — `identity-service.yml`
3. **Shared profile config** — `application-docker.yml`
4. **Shared default config** — `application.yml`
5. **Service's local `application.yml`** — Fallback if Config Server unreachable

### URL Pattern

Config Server exposes configs via REST:

```
GET /{application}/{profile}
GET /{application}/{profile}/{label}

Examples:
GET /identity-service/default        → identity-service.yml + application.yml
GET /identity-service/docker         → identity-service-docker.yml + application-docker.yml
GET /payment-service/default         → payment-service.yml + application.yml
```

---

## 3. Architecture Diagram

```
                         ┌─────────────────────────────────────────┐
                         │            CONFIG SERVER                 │
                         │              :8888                       │
                         │                                         │
                         │   ┌───────────────────────────────────┐ │
                         │   │  /configurations/ (classpath)     │ │
                         │   │                                   │ │
                         │   │  application.yml          (shared)│ │
                         │   │  application-docker.yml   (docker)│ │
                         │   │  identity-service.yml     (local) │ │
                         │   │  identity-service-docker.yml      │ │
                         │   │  payment-service.yml              │ │
                         │   │  payment-service-docker.yml       │ │
                         │   │  merchant-service.yml             │ │
                         │   │  merchant-service-docker.yml      │ │
                         │   │  settlement-service.yml           │ │
                         │   │  notification-service.yml         │ │
                         │   │  webhook-service.yml              │ │
                         │   │  routing-service.yml              │ │
                         │   │  api-gateway.yml                  │ │
                         │   └───────────────────────────────────┘ │
                         └────────┬──────────┬──────────┬──────────┘
                                  │          │          │
               ┌──────────────────┘          │          └─────────────────┐
               ▼                             ▼                            ▼
    ┌────────────────────┐       ┌────────────────────┐       ┌────────────────────┐
    │  Identity Service  │       │  Payment Service   │       │  API Gateway       │
    │  :8081             │       │  :8083             │       │  :8080             │
    │                    │       │                    │       │                    │
    │  spring.config.    │       │  spring.config.    │       │  spring.config.    │
    │  import:           │       │  import:           │       │  import:           │
    │  configserver:     │       │  configserver:     │       │  configserver:     │
    │  http://localhost  │       │  http://localhost  │       │  http://localhost  │
    │  :8888             │       │  :8888             │       │  :8888             │
    └────────────────────┘       └────────────────────┘       └────────────────────┘
```

---

## 4. Application Class

```java
package com.payflow.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Spring Cloud Config Server — Centralized configuration for all PayFlow services.
 * Serves YAML configurations from local filesystem (native profile).
 * Port: 8888
 */
@SpringBootApplication
@EnableConfigServer  // ← Enables the Config Server REST API
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
```

Like Eureka, a single annotation (`@EnableConfigServer`) transforms a basic Spring Boot app into a full-featured configuration server.

---

## 5. Config Server Configuration

```yaml
server:
  port: 8888  # Standard Spring Cloud Config Server port

spring:
  application:
    name: config-server
  profiles:
    active: native  # Use local filesystem instead of Git
  cloud:
    config:
      server:
        native:
          # Where config files live — classpath means they're inside the JAR
          search-locations: classpath:/configurations

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/  # Register with Eureka
```

### Key Configuration Decisions

| Property | Value | Why |
|----------|-------|-----|
| `profiles.active: native` | Local filesystem | Simpler for development; Git for production |
| `search-locations: classpath:/configurations` | Inside JAR | All configs packaged with the server |
| `eureka.client.service-url` | Eureka URL | Config Server registers itself for discovery |
| `server.port: 8888` | Standard port | Convention for Spring Cloud Config Server |

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

    <artifactId>config-server</artifactId>
    <name>PayFlow Config Server</name>
    <description>Centralized configuration server for all services</description>

    <dependencies>
        <!-- Spring Cloud Config Server — serves configurations -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-config-server</artifactId>
        </dependency>

        <!-- Eureka Client — register with service registry -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
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

---

## 7. Service Configuration Files

All config files live in `src/main/resources/configurations/`:

### 7.1 identity-service.yml

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_identity
    username: payflow
    password: payflow123
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    baseline-on-migrate: true

jwt:
  secret: payflow-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256
  access-token-expiration: 900000      # 15 minutes in milliseconds
  refresh-token-expiration: 604800000  # 7 days in milliseconds

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

### 7.2 payment-service.yml

```yaml
server:
  port: 8083

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_payment
    username: payflow
    password: payflow123
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  flyway:
    enabled: true
    baseline-on-migrate: true
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

idempotency:
  ttl-seconds: 86400  # 24 hours — idempotency keys expire after this

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

### 7.3 api-gateway.yml

```yaml
server:
  port: 8080

spring:
  cloud:
    gateway:
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin
      routes:
        - id: identity-service
          uri: lb://identity-service
          predicates:
            - Path=/v1/auth/**
        - id: merchant-service
          uri: lb://merchant-service
          predicates:
            - Path=/v1/merchants/**
        - id: payment-service-orders
          uri: lb://payment-service
          predicates:
            - Path=/v1/orders/**
        - id: payment-service-payments
          uri: lb://payment-service
          predicates:
            - Path=/v1/payments/**
        - id: payment-service-refunds
          uri: lb://payment-service
          predicates:
            - Path=/v1/refunds/**
        - id: settlement-service
          uri: lb://settlement-service
          predicates:
            - Path=/v1/settlements/**
  data:
    redis:
      host: localhost
      port: 6379

rate-limiter:
  requests-per-second: 10
  burst-capacity: 20

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

---

## 8. Shared Configuration (application.yml)

This config is applied to ALL services (merged with service-specific configs):

```yaml
# Shared configuration — applied to ALL services
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always

logging:
  level:
    com.payflow: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"

spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false  # Use ISO-8601 date format
    default-property-inclusion: non_null  # Omit null fields
```

### What's Shared vs Service-Specific

| Shared (application.yml) | Service-Specific |
|--------------------------|-----------------|
| Logging patterns | Database URLs |
| Actuator exposure | Server ports |
| Jackson serialization | Kafka config |
| Eureka defaults | JWT secrets |
| Health check config | Redis config |

---

## 9. How Services Consume Config

Each microservice includes the Config Client dependency and a minimal local config:

### POM Dependency

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-config</artifactId>
</dependency>
```

### Local application.yml (Minimal)

```yaml
spring:
  application:
    name: identity-service  # This name maps to identity-service.yml on Config Server
  config:
    import: optional:configserver:http://localhost:8888
```

### How `optional:` Works

The `optional:` prefix means:
- If Config Server is **available** → fetch config from it (highest priority)
- If Config Server is **unavailable** → fall back to local `application.yml` values
- The service **won't crash** if Config Server is down at startup

### Startup Sequence

```
1. Service starts, reads local application.yml
2. Sees "spring.config.import: configserver:http://localhost:8888"
3. Calls GET http://localhost:8888/identity-service/default
4. Config Server returns identity-service.yml + application.yml (shared)
5. Service merges remote config with local config (remote wins on conflicts)
6. Service finishes booting with complete configuration
```

---

## 10. Config Refresh Mechanism

### Using Spring Cloud Bus (Kafka)

For production, use Spring Cloud Bus to broadcast config changes to all services:

```yaml
# Add to each service
spring:
  cloud:
    bus:
      enabled: true
  kafka:
    bootstrap-servers: localhost:9092

# Trigger refresh for all services
POST /actuator/busrefresh
```

### Manual Refresh (Single Service)

```bash
# Refresh config for a single identity-service instance
curl -X POST http://localhost:8081/actuator/refresh

# Response shows which properties changed
["jwt.access-token-expiration", "logging.level.com.payflow"]
```

### @RefreshScope

Beans annotated with `@RefreshScope` are recreated when config refreshes:

```java
@Configuration
@RefreshScope  // ← Bean recreated on /actuator/refresh
public class JwtConfig {
    
    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;  // ← Picks up new value on refresh
}
```

---

## 11. Native vs Git-Backed Configs

### Comparison

| Feature | Native (Classpath) | Git-Backed |
|---------|-------------------|-----------|
| **Storage** | Inside JAR or filesystem | Git repository (GitHub, GitLab) |
| **Versioning** | Maven/Docker build versions | Full Git history |
| **Change Process** | Rebuild & redeploy Config Server | Git commit + refresh |
| **Audit Trail** | Docker image tags | Git log |
| **Best For** | Development, simple deployments | Production, team environments |

### PayFlow's Strategy

| Environment | Backend | Reason |
|-------------|---------|--------|
| **Local Dev** | Native (classpath) | Simple, everything in one repo |
| **Docker Compose** | Native (classpath) | Config packaged in image |
| **Production** | Git-backed | Version control, audit trail, no rebuild needed |

### Git-Backed Configuration (Production)

```yaml
spring:
  cloud:
    config:
      server:
        git:
          uri: https://github.com/payflow/config-repo.git
          default-label: main
          search-paths: '{application}'  # Subfolder per service
          clone-on-start: true
          username: ${GIT_USERNAME}
          password: ${GIT_TOKEN}
```

---

## 12. Environment Profiles

### Docker Profile Example (identity-service-docker.yml)

Overrides localhost URLs with Docker service names:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/payflow_identity  # "postgres" = Docker service name
    username: payflow
    password: payflow123

eureka:
  client:
    service-url:
      defaultZone: http://service-registry:8761/eureka/  # Docker service name
```

### Activating Profiles

```bash
# Via environment variable (Docker Compose)
environment:
  - SPRING_PROFILES_ACTIVE=docker

# Via command line
java -jar app.jar --spring.profiles.active=docker

# Via Docker run
docker run -e SPRING_PROFILES_ACTIVE=docker payflow/identity-service:latest
```

### Available Profiles

| Profile | Use Case | Config File Suffix |
|---------|----------|-------------------|
| `default` | Local development | `identity-service.yml` |
| `docker` | Docker Compose | `identity-service-docker.yml` |
| `test` | Integration tests | `identity-service-test.yml` |
| `prod` | Production | `identity-service-prod.yml` |

---

## 13. Configuration Encryption

### Encrypting Sensitive Values

Spring Cloud Config supports encrypting properties:

```yaml
# Encrypted value in config file
spring:
  datasource:
    password: '{cipher}AQBz7...(encrypted base64)...'
```

### Setting Up Encryption

```yaml
# In config-server's application.yml
encrypt:
  key: ${CONFIG_ENCRYPTION_KEY}  # Symmetric key from environment variable
```

### Encrypting/Decrypting Values

```bash
# Encrypt a value
curl -X POST http://localhost:8888/encrypt -d "payflow123"
# Returns: AQBz7...encrypted...

# Decrypt a value
curl -X POST http://localhost:8888/decrypt -d "AQBz7...encrypted..."
# Returns: payflow123
```

### What to Encrypt

| Encrypt | Don't Encrypt |
|---------|--------------|
| Database passwords | Server ports |
| JWT secrets | Logging levels |
| API keys | Eureka URLs |
| Kafka passwords | Jackson settings |
| Redis passwords | Actuator config |

---

## 14. Dockerfile

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY common-lib ./common-lib
COPY config-server ./config-server
RUN mvn clean package -pl config-server -am -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/config-server/target/*.jar app.jar
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Build & Run

```bash
# Build
docker build -t payflow/config-server:latest -f backend/config-server/Dockerfile backend/

# Run (standalone)
docker run -d \
  --name config-server \
  -p 8888:8888 \
  -e SPRING_PROFILES_ACTIVE=native \
  payflow/config-server:latest
```

---

## 15. Testing the Config Server

### Verify Config Server is Running

```bash
curl http://localhost:8888/actuator/health
# {"status":"UP"}
```

### Fetch Service Configuration

```bash
# Fetch identity-service config (default profile)
curl http://localhost:8888/identity-service/default | jq .

# Response:
{
  "name": "identity-service",
  "profiles": ["default"],
  "propertySources": [
    {
      "name": "classpath:/configurations/identity-service.yml",
      "source": {
        "server.port": 8081,
        "spring.datasource.url": "jdbc:postgresql://localhost:5432/payflow_identity",
        "jwt.access-token-expiration": 900000
      }
    },
    {
      "name": "classpath:/configurations/application.yml",
      "source": {
        "management.endpoints.web.exposure.include": "health,info,metrics,prometheus",
        "logging.level.com.payflow": "DEBUG"
      }
    }
  ]
}
```

### Fetch Docker Profile

```bash
curl http://localhost:8888/identity-service/docker | jq .
# Returns identity-service-docker.yml + application-docker.yml + defaults
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | Centralized Config | One server provides config to all microservices — no more scattered YAML files |
| 2 | `@EnableConfigServer` | Single annotation creates a full config server |
| 3 | Native Profile | `spring.profiles.active: native` reads from classpath (simple for dev) |
| 4 | Config Resolution | Service-specific overrides shared; profile-specific overrides default |
| 5 | `optional:configserver:` | Services don't crash if Config Server is unavailable |
| 6 | Config Refresh | `/actuator/refresh` reloads config without restart (with `@RefreshScope`) |
| 7 | Git-Backed (Production) | Version-controlled configs with full audit trail |
| 8 | Environment Profiles | `-docker.yml` suffix overrides localhost URLs with Docker service names |
| 9 | Encryption | `{cipher}` prefix tells Config Server to decrypt before serving |
| 10 | Shared application.yml | Common config (logging, Jackson, actuator) applied to ALL services |
| 11 | Startup Sequence | Service boots → fetches remote config → merges → finishes initialization |
| 12 | Spring Cloud Bus | Broadcast config changes to all services simultaneously via Kafka |

---

## 📚 Document Index

| Part | Title | Status |
|------|-------|--------|
| 4.01 | Parent POM & Maven Setup | ✅ Complete |
| 4.02 | Common Library | ✅ Complete |
| 4.03 | Service Registry (Eureka) | ✅ Complete |
| **4.04** | **Config Server** | **📍 You are here** |
| 4.05 | API Gateway | ⏭️ Next |
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

In **Phase 4 Part 5**, we build the **API Gateway** — the single entry point for all client requests, handling routing, JWT validation, rate limiting, CORS, and request logging via reactive filters.

→ [Continue to Phase 4 Part 5: API Gateway](./phase4-part05-api-gateway.md)
