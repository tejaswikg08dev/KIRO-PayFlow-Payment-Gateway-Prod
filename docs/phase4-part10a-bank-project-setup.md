# 🏗️ Phase 4 Part 10a: Bank Simulator — Project Setup

> **"Three dependencies. Two ports. One job: pretend to be a bank."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 10a — Project Setup |
| **What You Build** | pom.xml, application.yml, BankSimulatorApplication.java, SimulatorConfig.java |
| **Previous** | [Part 10 Overview](./phase4-part10-bank-simulator-overview.md) |
| **Next** | [Part 10b — Netty TCP Server](./phase4-part10b-bank-netty-server.md) |

---

## 📖 Table of Contents

1. [What We Build and Why](#1-what-we-build-and-why)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: pom.xml](#3-step-by-step-pomxml)
4. [Step-by-Step: application.yml](#4-step-by-step-applicationyml)
5. [Step-by-Step: BankSimulatorApplication.java](#5-step-by-step-banksimulatorapplicationjava)
6. [Step-by-Step: SimulatorConfig.java](#6-step-by-step-simulatorconfigjava)
7. [How These 4 Files Work Together](#7-how-these-4-files-work-together)
8. [What You Learned](#8-what-you-learned)

---

## 1. What We Build and Why

| File | What It Does | Different from Routing Service? |
|---|---|---|
| `pom.xml` | Dependencies — just 3 (web, actuator, netty) | Back to PayFlow parent (routing used spring-boot-starter-parent) |
| `application.yml` | Two ports: HTTP 9000 + TCP 9090, simulator config | No database, no Eureka, no fraud, no routing config |
| `BankSimulatorApplication.java` | Starts Spring Boot + TCP server on separate thread | `CommandLineRunner` pattern (new!) |
| `SimulatorConfig.java` | `@ConfigurationProperties` for simulator settings | Typed config binding (new pattern!) |

---

## 2. Folder Structure After This Part

```
backend/bank-simulator/
├── pom.xml                                              ← YOU CREATE THIS
└── src/main/
    ├── java/com/payflow/bank/
    │   ├── BankSimulatorApplication.java                ← YOU CREATE THIS
    │   └── config/
    │       └── SimulatorConfig.java                     ← YOU CREATE THIS
    └── resources/
        └── application.yml                              ← YOU CREATE THIS
```

---

## 3. Step-by-Step: pom.xml

**File:** `backend/bank-simulator/pom.xml`

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

    <artifactId>bank-simulator</artifactId>
    <name>PayFlow Bank Simulator</name>
    <description>Bank/acquirer simulator with ISO 8583 TCP interface and configurable response rules</description>
```

**BACK TO PAYFLOW PARENT.** Routing service used `spring-boot-starter-parent` directly. Bank simulator is back in the PayFlow multi-module reactor — versions are inherited from the parent POM.

```xml
    <dependencies>
        <!-- Spring Boot Web (REST endpoints for management) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring Boot Actuator (health, metrics) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Netty (TCP server for ISO 8583) -->
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-all</artifactId>
        </dependency>
    </dependencies>
```

**ONLY 3 DEPENDENCIES — the leanest POM in PayFlow:**

| Dependency | Why |
|---|---|
| `spring-boot-starter-web` | HTTP server on port 9000 for health/actuator endpoints |
| `spring-boot-starter-actuator` | `/actuator/health` — Docker uses this to check if simulator is alive |
| `netty-all` | TCP server that listens on port 9090 for ISO 8583 messages |

**WHAT'S NOT HERE:**

| Missing | Why Not Needed |
|---|---|
| `common-lib` | Bank simulator doesn't use PayFlow shared code (ApiResponse, exceptions, etc.) |
| `spring-boot-starter-data-jpa` | No database |
| `spring-cloud-starter-eureka-client` | Not a discoverable microservice — routing connects directly via TCP |
| `spring-boot-starter-security` | No auth needed — it's a test tool |
| `spring-boot-starter-validation` | No `@Valid` DTOs — simplified string-based messages |
| `resilience4j` | Server doesn't need circuit breakers (that's the client's job) |

**NO `<version>` TAGS** on any dependency — all inherited from parent POM. Compare to routing service which needed explicit `<properties>` for every version.

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

Same build plugin — creates executable fat JAR.

---

## 4. Step-by-Step: application.yml

**File:** `backend/bank-simulator/src/main/resources/application.yml`

```yaml
server:
  port: 9000
```

**HTTP PORT 9000** — for Spring Boot's embedded Tomcat. Only serves actuator endpoints (health, metrics). NOT for ISO 8583 — that's on TCP 9090.

**WHY 9000 AND NOT 8085?** The bank simulator is NOT part of the PayFlow microservice cluster (8080-8085). It simulates an EXTERNAL system. Port 9000 makes this distinction clear.

```yaml
spring:
  application:
    name: bank-simulator
```

**NO `config.import`** — the bank simulator doesn't use Config Server. It's a standalone tool.

**NO `eureka`** — the bank simulator doesn't register with Eureka. Routing service connects directly to `localhost:9090`.

### 🆕 Simulator Configuration

```yaml
# Bank Simulator Configuration
simulator:
  tcp-port: 9090
  success-rate-percent: 85
  min-latency-ms: 50
  max-latency-ms: 500
  log-full-messages: false
```

**CUSTOM CONFIG PREFIX `simulator:`** — bound to `SimulatorConfig.java` via `@ConfigurationProperties`:

| Property | Default | Meaning |
|---|---|---|
| `tcp-port` | 9090 | TCP port for the Netty ISO 8583 server |
| `success-rate-percent` | 85 | When no rule matches, 85% of transactions are approved |
| `min-latency-ms` | 50 | Minimum simulated bank processing time |
| `max-latency-ms` | 500 | Maximum simulated bank processing time |
| `log-full-messages` | false | Don't log full ISO messages (PAN security) |

**WHY 85% SUCCESS RATE?** Real banks approve ~85-95% of transactions. 15% decline for various reasons (insufficient funds, expired card, fraud, limits). 85% gives realistic test behavior.

**WHY 50-500ms LATENCY?** Real banks respond in 100-2000ms depending on load. 50-500ms is fast enough for development but slow enough to test timeout handling.

### Actuator + Logging

```yaml
# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always

# Logging
logging:
  level:
    com.payflow.bank: INFO
    com.payflow.bank.server: DEBUG
    io.netty: WARN
```

**`com.payflow.bank.server: DEBUG`** — verbose logging for the TCP server (connection events, message parsing). You'll see every incoming request and outgoing response.

**`io.netty: WARN`** — Netty is chatty at INFO/DEBUG level. Only show warnings and errors.

---

## 5. Step-by-Step: BankSimulatorApplication.java

**File:** `src/main/java/com/payflow/bank/BankSimulatorApplication.java`

```java
package com.payflow.bank;

import com.payflow.bank.server.BankSimulatorServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@Slf4j
@SpringBootApplication
public class BankSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankSimulatorApplication.class, args);
    }
```

**PACKAGE: `com.payflow.bank`** — NOT `com.payflow.banksimulator`. Component scan covers `com.payflow.bank.*`.

**SIMPLEST MAIN CLASS IN PAYFLOW:**
- No `@EnableDiscoveryClient` (no Eureka)
- No `@EnableFeignClients` (no Feign)
- No `@EnableScheduling` (no scheduled tasks)
- Just `@SpringBootApplication` + one `@Bean`

### 🆕 CommandLineRunner — Starting TCP on a Separate Thread

```java
    @Bean
    public CommandLineRunner startTcpServer(BankSimulatorServer server) {
        return args -> {
            log.info("Starting Bank Simulator TCP server...");
            new Thread(() -> {
                try {
                    server.start();
                } catch (Exception e) {
                    log.error("Failed to start TCP server: {}", e.getMessage(), e);
                }
            }, "bank-tcp-server").start();
        };
    }
}
```

**🆕 `CommandLineRunner` — NEW PATTERN (never seen in previous services):**

| Pattern | Used In | What It Does |
|---|---|---|
| `@PostConstruct` | Routing service's Netty client | Runs after bean creation (in Spring's thread) |
| **`CommandLineRunner`** | **Bank simulator** | Runs AFTER Spring Boot is fully started (all beans ready) |

**WHY `CommandLineRunner` AND NOT `@PostConstruct`?**
```
@PostConstruct:
  Runs during bean initialization
  If it blocks → Spring Boot startup HANGS
  Bad for: server.start() which blocks on closeFuture().sync()

CommandLineRunner:
  Runs AFTER Spring context is fully initialized
  All beans are ready (SimulatorConfig, BankChannelInitializer, etc.)
  We can safely start the TCP server here
```

**WHY A SEPARATE THREAD?**

```java
new Thread(() -> {
    server.start();  // This call BLOCKS (closeFuture().sync())
}, "bank-tcp-server").start();
```

`BankSimulatorServer.start()` calls `future.channel().closeFuture().sync()` which **blocks forever** (until the server is shut down). If we ran this on the main thread, Spring Boot would never finish starting — the HTTP server on port 9000 would never become available.

By running on a separate named thread (`"bank-tcp-server"`), both servers run simultaneously:
- **Main thread:** Spring Boot HTTP server on port 9000 (actuator)
- **`bank-tcp-server` thread:** Netty TCP server on port 9090 (ISO 8583)

**THREAD NAMING: `"bank-tcp-server"`** — visible in logs and thread dumps. Helps debugging:
```
[bank-tcp-server] INFO  BankSimulatorServer - Bank Simulator TCP server started on port 9090
[main]            INFO  BankSimulatorApplication - Started BankSimulatorApplication in 2.3s
```

**ERROR HANDLING:** If the TCP server fails (port already in use, permission denied), the error is logged but Spring Boot continues running. The actuator health endpoint will still work — useful for diagnosing the port conflict.

### Comparison: Entry Points Across All Services

| Service | Annotations | Special Beans |
|---|---|---|
| Identity | `@SpringBootApplication` | None |
| Merchant | `@SpringBootApplication` `@EnableDiscoveryClient` | None |
| Payment | `@SpringBootApplication` `@EnableFeignClients` | None |
| Routing | `@SpringBootApplication` `@EnableDiscoveryClient` `@EnableScheduling` | None |
| **Bank Sim** | `@SpringBootApplication` `@Slf4j` | **`CommandLineRunner`** (starts TCP server) |

---

## 6. Step-by-Step: SimulatorConfig.java

**File:** `src/main/java/com/payflow/bank/config/SimulatorConfig.java`

```java
package com.payflow.bank.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the bank simulator.
 * Controls success rate, latency simulation, and TCP port.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "simulator")
public class SimulatorConfig {
```

**🆕 `@ConfigurationProperties(prefix = "simulator")` — NEW PATTERN:**

Previous services used `@Value("${property.name}")` for individual properties. This is the **type-safe** alternative:

| `@Value` approach (routing service) | `@ConfigurationProperties` approach (bank simulator) |
|---|---|
| `@Value("${fraud.rules.velocity-threshold:5}") private int velocityThreshold;` | All properties in one class with getters/setters |
| One `@Value` per field, scattered across classes | Single source of truth for all config |
| String-based (typo = runtime error) | Type-safe (Java compiler catches errors) |
| No autocomplete in IDE | IDE autocomplete for all properties |

**HOW IT WORKS:**
```
application.yml:              Java class:
simulator:                    @ConfigurationProperties(prefix = "simulator")
  tcp-port: 9090              private int tcpPort = 9090;
  success-rate-percent: 85    private int successRatePercent = 85;
  min-latency-ms: 50          private int minLatencyMs = 50;
```

**NAMING CONVENTION: `tcp-port` → `tcpPort`**

Spring Boot automatically converts kebab-case YAML keys to camelCase Java fields:
- `tcp-port` → `tcpPort`
- `success-rate-percent` → `successRatePercent`
- `min-latency-ms` → `minLatencyMs`
- `max-latency-ms` → `maxLatencyMs`
- `log-full-messages` → `logFullMessages`

**`@Data`** — Lombok generates getters/setters. `@ConfigurationProperties` requires setters to bind values.

**`@Configuration`** — makes this class a Spring bean. Other classes inject it via constructor:
```java
public class BankSimulatorServer {
    private final SimulatorConfig config; // Injected
    // config.getTcpPort() → 9090
    // config.getSuccessRatePercent() → 85
}
```

### The 5 Properties

```java
    /**
     * TCP port for the ISO 8583 server (default: 9090).
     */
    private int tcpPort = 9090;

    /**
     * Percentage of transactions that succeed when no specific rule matches (default: 85%).
     */
    private int successRatePercent = 85;

    /**
     * Minimum simulated latency in milliseconds (default: 50ms).
     */
    private int minLatencyMs = 50;

    /**
     * Maximum simulated latency in milliseconds (default: 500ms).
     */
    private int maxLatencyMs = 500;

    /**
     * Whether to log full ISO 8583 messages (default: false for security).
     */
    private boolean logFullMessages = false;
}
```

**EACH PROPERTY EXPLAINED:**

| Property | Type | Default | Used By | Purpose |
|---|---|---|---|---|
| `tcpPort` | int | 9090 | BankSimulatorServer | Which port to listen on for TCP |
| `successRatePercent` | int | 85 | ResponseGenerator | Random approve rate when no rule matches |
| `minLatencyMs` | int | 50 | Iso8583RequestHandler | Minimum simulated bank processing delay |
| `maxLatencyMs` | int | 500 | Iso8583RequestHandler | Maximum simulated bank processing delay |
| `logFullMessages` | boolean | false | (future use) | Security: don't log card numbers |

**WHY DEFAULT VALUES IN JAVA?** If `application.yml` is missing a property, the Java default is used. This means the simulator works out-of-the-box with sensible defaults even with an empty YAML file.

**WHY `logFullMessages = false`?** ISO 8583 messages contain card numbers (PAN). Logging them is a PCI compliance violation. Default is OFF for safety. Set to `true` only during debugging.

---

## 7. How These 4 Files Work Together

```
┌───────────────────────────────────────────────────────────────────────────┐
│                    BANK SIMULATOR STARTUP                                  │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  1. pom.xml → Maven downloads: Web + Actuator + Netty (just 3!)         │
│     NOTE: No JPA, no Eureka, no common-lib, no security!                 │
│                                                                           │
│  2. BankSimulatorApplication.main()                                      │
│     @SpringBootApplication → scan com.payflow.bank.*                     │
│                                                                           │
│  3. application.yml is read:                                              │
│     HTTP port=9000, simulator.tcp-port=9090                              │
│                                                                           │
│  4. Spring creates beans:                                                │
│     SimulatorConfig → reads simulator.* properties                       │
│     CardBinRules, AmountRules → @Component (auto-scanned)               │
│     ResponseGenerator → injects CardBinRules + AmountRules + Config      │
│     Iso8583RequestHandler → injects ResponseGenerator + Config           │
│     BankChannelInitializer → injects Iso8583RequestHandler               │
│     BankSimulatorServer → injects Config + BankChannelInitializer        │
│                                                                           │
│  5. Tomcat starts on HTTP port 9000                                      │
│     /actuator/health available                                           │
│                                                                           │
│  6. CommandLineRunner executes:                                          │
│     new Thread("bank-tcp-server") → server.start()                      │
│     → ServerBootstrap binds to TCP port 9090                            │
│     → Blocks on closeFuture().sync() (runs forever on this thread)      │
│                                                                           │
│  7. BOTH SERVERS RUNNING:                                                │
│     [main thread]            → HTTP :9000 (actuator)                    │
│     [bank-tcp-server thread] → TCP :9090 (ISO 8583)                     │
│                                                                           │
│  READY: Accepting ISO 8583 on :9090                                      │
│         Health check on :9000/actuator/health                            │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Leanest POM** | Only 3 dependencies: web, actuator, netty. No JPA, no Eureka, no security |
| 2 | **Back to PayFlow parent** | Routing used spring-boot-starter-parent. Bank sim is back in the multi-module reactor |
| 3 | **Two ports** | HTTP 9000 (actuator) + TCP 9090 (ISO 8583) — different protocols, different ports |
| 4 | **No Eureka** | Bank simulator is NOT a discoverable microservice — routing connects via direct TCP |
| 5 | **`CommandLineRunner`** | Runs AFTER Spring Boot is fully started — safe to start blocking TCP server |
| 6 | **Separate thread for TCP** | `server.start()` blocks forever on `closeFuture().sync()` — needs its own thread |
| 7 | **Thread naming** | `"bank-tcp-server"` — visible in logs and thread dumps for debugging |
| 8 | **`@ConfigurationProperties`** | Type-safe config binding — all `simulator.*` properties in one class |
| 9 | **Kebab to camelCase** | `tcp-port` in YAML → `tcpPort` in Java (Spring auto-converts) |
| 10 | **Java defaults** | `private int tcpPort = 9090` — works even without YAML property |
| 11 | **`logFullMessages = false`** | PCI security — don't log card numbers by default |
| 12 | **No `@EnableDiscoveryClient`** | External simulator, not a PayFlow microservice |
| 13 | **`@Data` + `@ConfigurationProperties`** | Lombok generates getters/setters needed for property binding |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part10-bank-simulator-overview.md) | Bank Simulator Overview |
| **Part 10a** | **Project Setup** (You are here) |
| [Part 10b](./phase4-part10b-bank-netty-server.md) | Netty TCP Server |
| [Part 10c](./phase4-part10c-bank-handler-logic.md) | Request Handler + Decision Logic |
| [Part 10d](./phase4-part10d-bank-docker-testing.md) | Dockerfile + Testing + Connections |

---

*Next: [Part 10b — Netty TCP Server (ServerBootstrap, Pipeline)](./phase4-part10b-bank-netty-server.md) →*
