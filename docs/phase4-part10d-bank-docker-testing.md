# 🏗️ Phase 4 Part 10d: Bank Simulator — Dockerfile + Testing + Connections

> **"Two ports in one container. HTTP 9000 for health checks. TCP 9090 for bank transactions. The Dockerfile exposes both."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 10d — Dockerfile + Testing + Connections |
| **What You Build** | Dockerfile, curl commands, connection verification |
| **Previous** | [Part 10c — Request Handler + Decision Logic](./phase4-part10c-bank-handler-logic.md) |
| **Next** | [Phase 4 Part 11 — Settlement Service](./phase4-part11-settlement-service.md) |

---

## 📖 Table of Contents

1. [Step-by-Step: Dockerfile](#1-step-by-step-dockerfile)
2. [How to Run the Bank Simulator](#2-how-to-run-the-bank-simulator)
3. [Testing with curl (HTTP Health)](#3-testing-with-curl-http-health)
4. [How Routing Service Connects](#4-how-routing-service-connects)
5. [What Would Break If You Removed Each File](#5-what-would-break-if-you-removed-each-file)
6. [The Complete Mental Model](#6-the-complete-mental-model)
7. [What You Learned](#7-what-you-learned)

---

## 1. Step-by-Step: Dockerfile

**File:** `backend/bank-simulator/Dockerfile`

```dockerfile
# Stage 1: Build
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
RUN mvn clean package -pl bank-simulator -am -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow
COPY --from=builder /app/bank-simulator/target/*.jar app.jar
EXPOSE 9000 9090
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:9000/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**SAME MULTI-STAGE PATTERN as all other services.** Key differences:

### 🆕 `EXPOSE 9000 9090` — Two Ports

```
Previous services:
  EXPOSE 8081          (Identity — HTTP only)
  EXPOSE 8082          (Merchant — HTTP only)
  EXPOSE 8083          (Payment — HTTP only)
  EXPOSE 8084          (Routing — HTTP only, TCP is outbound not inbound)

Bank Simulator:
  EXPOSE 9000 9090     (HTTP + TCP)
         ↑       ↑
         │       └── TCP: Netty ISO 8583 server (bank authorization)
         └────────── HTTP: Spring Boot Tomcat (actuator health/metrics)
```

**WHY TWO PORTS?** The bank simulator runs TWO servers simultaneously:
- **HTTP 9000** — Spring Boot's embedded Tomcat (for `/actuator/health` — Docker needs this)
- **TCP 9090** — Netty's ServerBootstrap (for ISO 8583 — routing service connects here)

Docker needs BOTH ports exposed:
- Port 9000 for the `HEALTHCHECK` command
- Port 9090 for routing-service TCP connections

### HEALTHCHECK on Port 9000 (NOT 9090)

```dockerfile
HEALTHCHECK ... CMD wget -qO- http://localhost:9000/actuator/health || exit 1
```

**HTTP, not TCP.** Docker's `HEALTHCHECK` uses `wget` which speaks HTTP. The TCP port 9090 speaks ISO 8583 binary — `wget` can't talk to it. So the health check uses the HTTP actuator endpoint.

**BUT:** The health check only proves Spring Boot is running. It doesn't guarantee the TCP server on 9090 is listening. If the TCP server fails to start (port conflict), the HTTP health check still passes.

### `-pl bank-simulator -am`

Maven: build only `bank-simulator` module and its dependencies (`-am` = also make dependencies). Since the POM uses PayFlow parent, Maven needs the entire reactor structure present (all COPY commands) but only compiles bank-simulator.

---

## 2. How to Run the Bank Simulator

### Option A: Maven (Local Development)

```powershell
# From backend/ directory
cd payflow-payment-gateway/backend

# Build (first time only — or after code changes)
mvn clean package -pl bank-simulator -am -DskipTests

# Run
mvn spring-boot:run -pl bank-simulator

# Expected output:
# Started BankSimulatorApplication in 2.1s
# Starting Bank Simulator TCP server...
# Bank Simulator TCP server started on port 9090
# Success rate: 85%, Latency: 50ms-500ms
```

### Option B: Java JAR

```powershell
cd payflow-payment-gateway/backend/bank-simulator/target
java -jar bank-simulator-1.0.0-SNAPSHOT.jar

# Override settings:
java -jar bank-simulator-1.0.0-SNAPSHOT.jar --simulator.success-rate-percent=100
java -jar bank-simulator-1.0.0-SNAPSHOT.jar --simulator.tcp-port=9091
```

### Option C: Docker

```powershell
# Build from backend/ directory
docker build -f bank-simulator/Dockerfile -t payflow/bank-simulator .

# Run
docker run -p 9000:9000 -p 9090:9090 payflow/bank-simulator

# Run with custom settings
docker run -p 9000:9000 -p 9090:9090 -e "SIMULATOR_SUCCESS_RATE_PERCENT=100" payflow/bank-simulator
```

---

## 3. Testing with curl (HTTP Health)

**The bank simulator's TCP port (9090) can't be tested with curl** — it speaks binary/string ISO 8583, not HTTP. But the HTTP actuator on port 9000 can be tested:

```powershell
# 1. Health Check
curl http://localhost:9000/actuator/health

# Expected:
# {"status":"UP"}

# 2. Application Info
curl http://localhost:9000/actuator/info

# 3. Available Metrics
curl http://localhost:9000/actuator/metrics

# 4. Verify TCP port is listening (PowerShell)
Test-NetConnection -ComputerName localhost -Port 9090

# Expected:
# TcpTestSucceeded : True
```

**TO TEST THE ACTUAL ISO 8583 BEHAVIOR:** Use the routing service. Start both:
1. Bank simulator on port 9090
2. Routing service on port 8084
3. Send a request to routing service → it connects to bank simulator → you see the response

```powershell
# Send a transaction through routing service (which calls bank simulator)
curl -X POST http://localhost:8084/internal/route `
  -H "Content-Type: application/json" `
  -d '{\"merchantId\":\"merchant-001\",\"amount\":1500.00,\"currency\":\"INR\",\"paymentMethod\":\"CARD\",\"cardBin\":\"411111\",\"cardNumber\":\"4111111111111111\"}'

# Expected: {"success":true,"authorizationCode":"...","responseCode":"00",...}
# Because card 4111 always approves in bank simulator!
```

---

## 4. How Routing Service Connects

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                  THE COMPLETE TCP CONNECTION FLOW                                │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  PAYMENT SERVICE (Port 8083)                                                   │
│  PaymentService.authorize()                                                    │
│       │                                                                         │
│       ▼ Feign HTTP POST                                                        │
│                                                                                 │
│  ROUTING SERVICE (Port 8084)                                                   │
│  RoutingController.routeTransaction()                                          │
│       │                                                                         │
│       ├── Step 1: FraudDetectionService → APPROVE                              │
│       ├── Step 2: SmartRoutingService → select Alpha Bank                     │
│       ├── Step 3: buildIso8583Request() → Iso8583Message                      │
│       │                                                                         │
│       ▼ Step 4: BankNettyClient.sendMessage(isoRequest)                        │
│                                                                                 │
│  ┌─── TCP Connection ─────────────────────────────────────────────────┐        │
│  │                                                                     │        │
│  │  Routing Service (CLIENT)          Bank Simulator (SERVER)          │        │
│  │  Bootstrap                         ServerBootstrap                  │        │
│  │  connect("localhost", 9090)  ───►  bind(9090) accepts connection   │        │
│  │                                                                     │        │
│  │  OUTBOUND:                         INBOUND:                         │        │
│  │  Iso8583Encoder                    LengthFieldBasedFrameDecoder    │        │
│  │  → binary bytes                    → strip 2-byte length           │        │
│  │  LengthFieldPrepender (4-byte)     StringDecoder (UTF-8)          │        │
│  │  → add length header               → String message               │        │
│  │  TCP ──────────────────────►       Iso8583RequestHandler           │        │
│  │                                     → parse MTI/PAN/Amount/RRN    │        │
│  │                                     → simulateLatency (50-500ms)  │        │
│  │                                     → ResponseGenerator           │        │
│  │                                       → CardBinRules (4111→00)    │        │
│  │                                     → build response string       │        │
│  │                                                                     │        │
│  │  INBOUND:                          OUTBOUND:                        │        │
│  │  LengthFieldBasedFrameDecoder      StringEncoder (UTF-8)          │        │
│  │  Iso8583Decoder                    LengthFieldPrepender (2-byte)  │        │
│  │  BankResponseHandler               TCP ◄────────────────────      │        │
│  │  → complete CompletableFuture                                      │        │
│  │                                                                     │        │
│  └─────────────────────────────────────────────────────────────────────┘        │
│                                                                                 │
│       ▼ Step 5: Parse response code "00" + auth code                           │
│       ▼ Step 6: recordResult() (update bank metrics)                           │
│                                                                                 │
│  ROUTING SERVICE returns RoutingResponse (JSON) to Payment Service             │
│                                                                                 │
│  PAYMENT SERVICE                                                               │
│  → Payment status → AUTHORIZED                                                │
│  → Kafka event published                                                       │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. What Would Break If You Removed Each File

| Remove | What Breaks | Error |
|---|---|---|
| `pom.xml` | Everything | Build fails |
| `application.yml` | TCP port defaults to 9090, HTTP to 8080 | Still works but on wrong HTTP port |
| `BankSimulatorApplication` | No main class | Startup fails |
| `SimulatorConfig` | No config bean | `NoSuchBeanDefinitionException` in BankSimulatorServer |
| `BankSimulatorServer` | No TCP server | Port 9090 never opens, routing service gets "Connection refused" |
| `BankChannelInitializer` | No pipeline | `NullPointerException` in ServerBootstrap |
| `Iso8583RequestHandler` | No request processing | Connection opens but no responses sent (routing times out) |
| `ResponseGenerator` | No decision logic | `NoSuchBeanDefinitionException` in Iso8583RequestHandler |
| `CardBinRules` | No deterministic test cards | `NoSuchBeanDefinitionException` in ResponseGenerator |
| `AmountRules` | No amount rules | `NoSuchBeanDefinitionException` in ResponseGenerator |
| `Dockerfile` | Can't containerize | Still works locally with `mvn spring-boot:run` |

---

## 6. The Complete Mental Model

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  THE BANK SIMULATOR IN ONE PICTURE:                                            │
│                                                                                 │
│  WHAT IT IS:                                                                   │
│    A fake bank for testing. Speaks ISO 8583 over TCP on port 9090.            │
│    Simulates approve/decline decisions with configurable rules.               │
│                                                                                 │
│  2 SERVERS IN 1 PROCESS:                                                       │
│    HTTP :9000 (Spring Boot Tomcat — actuator health/metrics)                  │
│    TCP  :9090 (Netty ServerBootstrap — ISO 8583 authorization)                │
│                                                                                 │
│  3 DECISION PRIORITIES:                                                        │
│    1. CardBinRules   → 5 deterministic test cards (4111=approve, 4000=decline)│
│    2. AmountRules    → 3 amount rules (>₹1L decline, ends-13 decline, 0 err) │
│    3. Random         → 85% approve, 15% decline (configurable)               │
│                                                                                 │
│  WHAT DEPENDS ON IT:                                                           │
│    Routing Service → BankNettyClient connects to TCP :9090                    │
│    Payment Service → indirectly (calls routing, which calls bank sim)         │
│                                                                                 │
│  WHAT IT DEPENDS ON:                                                           │
│    Nothing! Standalone. No database, no Eureka, no other services.            │
│                                                                                 │
│  KEY PATTERNS:                                                                 │
│    ServerBootstrap    → boss(1 thread) accepts + worker(N threads) handles    │
│    @Sharable handler  → one instance shared across all connections            │
│    CommandLineRunner  → starts blocking TCP server on separate thread         │
│    @ConfigurationProperties → type-safe config binding                        │
│    Decision cascade   → BIN rules → Amount rules → Random (first match wins) │
│    String-based codec → simplified ISO 8583 (not full binary)                │
│                                                                                 │
│  NUMBERS:                                                                      │
│    8 source files (smallest module in PayFlow)                                │
│    3 dependencies (web, actuator, netty)                                      │
│    2 ports (HTTP 9000, TCP 9090)                                              │
│    5 test cards (2 approve, 3 decline)                                        │
│    3 amount rules + 1 error handler                                           │
│    85% default success rate                                                   │
│    50-500ms simulated latency                                                 │
│    0 tests (test tool doesn't test itself)                                    │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **`EXPOSE 9000 9090`** | Two ports in one container: HTTP for health, TCP for ISO 8583 |
| 2 | **HEALTHCHECK on HTTP** | Docker health checks use HTTP (`wget`), not TCP binary protocol |
| 3 | **Can't curl TCP 9090** | ISO 8583/string protocol — not HTTP. Use routing service to test end-to-end |
| 4 | **`Test-NetConnection`** | PowerShell command to verify TCP port is listening |
| 5 | **Override config via CLI** | `--simulator.success-rate-percent=100` or `-e SIMULATOR_SUCCESS_RATE_PERCENT=100` in Docker |
| 6 | **Complete TCP flow** | Payment → Routing (Feign HTTP) → Bank Sim (Netty TCP) → Response → back up the chain |
| 7 | **Standalone module** | No dependencies on other services. Start it alone, test it alone |
| 8 | **0 tests by design** | A test tool doesn't need its own tests — it IS the test infrastructure |

---

## 🎉 Bank Simulator Complete!

You've built the entire Bank Simulator across 4 parts:

| Part | What You Built | Key Concepts |
|---|---|---|
| **10a** | pom.xml, yml, main class, config | CommandLineRunner, @ConfigurationProperties, 2 ports |
| **10b** | BankSimulatorServer, BankChannelInitializer | ServerBootstrap, boss/worker groups, String codec pipeline |
| **10c** | Handler, ResponseGenerator, CardBinRules, AmountRules | @Sharable, decision cascade, test cards, amount rules |
| **10d** | Dockerfile, testing, connections | 2-port EXPOSE, end-to-end TCP flow, mental model |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part10-bank-simulator-overview.md) | Bank Simulator Overview |
| [Part 10a](./phase4-part10a-bank-project-setup.md) | Project Setup |
| [Part 10b](./phase4-part10b-bank-netty-server.md) | Netty TCP Server |
| [Part 10c](./phase4-part10c-bank-handler-logic.md) | Request Handler + Decision Logic |
| **Part 10d** | **Dockerfile + Testing + Connections** (You are here) |

---

*Next service: [Phase 4 Part 11 — Settlement Service](./phase4-part11-settlement-service.md) →*
