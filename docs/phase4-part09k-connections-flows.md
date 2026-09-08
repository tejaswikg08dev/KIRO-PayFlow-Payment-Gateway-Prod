# 🏗️ Phase 4 Part 9k: Routing Service — How Everything Connects

> **"34 files, 6 technologies, 1 endpoint. This document shows you the building — not the bricks."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9k — Connections, Flows & Big Picture |
| **Previous** | [Part 9j — Controller + Docker](./phase4-part09j-controller-docker.md) |
| **Next** | [Phase 4 Part 10 — Bank Simulator](./phase4-part10-bank-simulator.md) |

---

## 📖 Table of Contents

1. [How the 34 Files Connect to Each Other](#1-how-the-34-files-connect-to-each-other)
2. [Transaction Lifecycle — End to End Through Every File](#2-transaction-lifecycle--end-to-end-through-every-file)
3. [How Routing Service Connects to Infrastructure](#3-how-routing-service-connects-to-infrastructure)
4. [How Routing Service Connects to Other Microservices](#4-how-routing-service-connects-to-other-microservices)
5. [What Would Break If You Removed Each File](#5-what-would-break-if-you-removed-each-file)
6. [Summary — The Complete Mental Model](#6-summary--the-complete-mental-model)

---

## 1. How the 34 Files Connect to Each Other

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    ROUTING SERVICE — INTERNAL WIRING                             │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  pom.xml ──────► ALL FILES (Netty, Resilience4j, DynamoDB SDK, Validation)     │
│  application.yml ► Startup (bank host:9090, epsilon:0.10, fraud thresholds,    │
│                    Netty threads:4, circuit breaker config, Eureka)             │
│  RoutingServiceApplication ► @EnableDiscoveryClient + @EnableScheduling        │
│                                                                                 │
│  CONTROLLER (HTTP entry) ──────────────► ALL DOMAINS                           │
│  ┌──────────────────────────────────────────────────────────────────┐          │
│  │ RoutingController  POST /internal/route                          │          │
│  │ @CircuitBreaker(name="bankCommunication")                        │          │
│  │                                                                   │          │
│  │ Step 1 ─► FraudDetectionService                                  │          │
│  │            ├── RuleEngine (velocity + amount + geo)               │          │
│  │            ├── FraudFeatureExtractor (9 features)                │          │
│  │            ├── DecisionTreeScorer (7 weighted nodes)             │          │
│  │            └── FraudResult (score + action + reasons)            │          │
│  │                                                                   │          │
│  │ Step 2 ─► SmartRoutingService                                    │          │
│  │            ├── RoutingMetricsRepository (interface)               │          │
│  │            │   └── InMemoryRoutingMetricsRepository (4 banks)    │          │
│  │            ├── BankRoute (mutable POJO with metrics)             │          │
│  │            └── RoutingDecision (record: bank + isExplore)        │          │
│  │                                                                   │          │
│  │ Step 3 ─► Iso8583MessageBuilder                                  │          │
│  │            ├── Iso8583Constants (MTI codes, field numbers)       │          │
│  │            ├── Iso8583Field (record: type + maxLength)           │          │
│  │            ├── BitmapUtils (bit manipulation)                    │          │
│  │            └── Iso8583Message (MTI + bitmap + fields)            │          │
│  │                                                                   │          │
│  │ Step 4 ─► BankNettyClient                                        │          │
│  │            ├── NettyConfig (EventLoopGroup bean)                 │          │
│  │            ├── BankChannelInitializer (pipeline setup)           │          │
│  │            ├── Iso8583Encoder (Java → binary)                    │          │
│  │            ├── LengthFieldPrepender (add 4-byte header)          │          │
│  │            │         ─── TCP to bank:9090 ───                    │          │
│  │            ├── LengthFieldBasedFrameDecoder (strip header)       │          │
│  │            ├── Iso8583Decoder (binary → Java)                    │          │
│  │            │   └── Iso8583MessageParser (byte[] → message)       │          │
│  │            └── BankResponseHandler (complete CompletableFuture)  │          │
│  │                                                                   │          │
│  │ Step 5 ─► Parse response code + auth code from Iso8583Message   │          │
│  │ Step 6 ─► SmartRoutingService.recordResult()                    │          │
│  │                                                                   │          │
│  │ Return RoutingResponse (JSON) to Payment Service                │          │
│  └──────────────────────────────────────────────────────────────────┘          │
│                                                                                 │
│  Resilience4jConfig ► CircuitBreakerRegistry (protects Step 4)                 │
│  DynamoDbConfig ► InMemoryRoutingMetricsRepository (provides bank data)        │
│                                                                                 │
│  RoutingRequest/RoutingResponse ► DTOs for HTTP JSON I/O                       │
│  Dockerfile ► Container packaging (port 8084)                                  │
│                                                                                 │
│  TESTS:                                                                         │
│  Iso8583MessageBuilderTest (6) + Iso8583MessageParserTest (5)                  │
│  FraudDetectionServiceTest (5) + SmartRoutingServiceTest (5)                   │
│  = 21 total unit tests                                                          │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Transaction Lifecycle — End to End Through Every File

### A Complete Authorization: Payment Service → Routing → Bank → Response

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│ T0: PAYMENT SERVICE CALLS ROUTING SERVICE (via Feign)                           │
│ ═══════════════════════════════════════════════════                              │
│                                                                                 │
│ PaymentService.authorize() → RoutingServiceClient.routePayment(Map)            │
│ → Feign resolves "routing-service" via Eureka → 192.168.x.x:8084              │
│ → HTTP POST /internal/route                                                    │
│ → JSON: {"merchantId":"mer-001","amount":1500,"currency":"INR",                │
│          "paymentMethod":"CARD","cardBin":"411111","cardNumber":"4111..."}      │
│                                                                                 │
│ FILES TOUCHED:                                                                 │
│ 1. RoutingRequest          → Jackson deserializes, @Valid validates             │
│ 2. RoutingController       → @PostMapping("/route") matches                    │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ STEP 1: FRAUD DETECTION                                                         │
│ ═══════════════════════                                                          │
│                                                                                 │
│ 3. FraudDetectionService   → analyze(request)                                  │
│ 4. RuleEngine              → evaluate(request)                                 │
│    → checkVelocity("mer-001") → 1 txn in window → score=0                    │
│    → checkAmountThreshold(1500) → 1500 < 50000 → score=0                     │
│    → checkGeoBlocking("INR") → not blocked → score=0                          │
│    → RuleResult{score:0, violations:[]}                                        │
│ 5. FraudFeatureExtractor   → extractFeatures(request) → 9 features            │
│ 6. DecisionTreeScorer      → score(features) → ~18 points                     │
│ 7. FraudResult             → FraudResult.of(7, []) → APPROVE                  │
│    → (0 × 0.6) + (18 × 0.4) = 7 → APPROVE (< 30)                            │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ STEP 2: SMART ROUTING                                                           │
│ ═════════════════════                                                            │
│                                                                                 │
│ 8. SmartRoutingService     → selectRoute()                                     │
│ 9. RoutingMetricsRepository → getActiveBankRoutes() → 4 banks                 │
│    (from InMemoryRoutingMetricsRepository in DynamoDbConfig)                   │
│ 10. ThreadLocalRandom      → 0.73 (> 0.10) → EXPLOIT                          │
│ 11. Comparator chain       → Alpha Bank (95% / 120ms) = best                  │
│ 12. RoutingDecision        → exploit(Alpha Bank)                               │
│ 13. BankRoute              → {bankId:"bank-alpha", successRate:0.95, ...}      │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ STEP 3: BUILD ISO 8583 MESSAGE                                                  │
│ ═══════════════════════════════                                                  │
│                                                                                 │
│ 14. RoutingController      → buildIso8583Request(request)                      │
│ 15. Iso8583MessageBuilder  → .setMti("0100").setPan("4111...")                 │
│                               .setProcessingCode("000000")                     │
│                               .setAmount("000000150000")                        │
│                               .setTraceNumber("654321")                        │
│                               .setTime("143025")                               │
│                               .setCurrencyCode("356")                          │
│                               .build()                                          │
│ 16. BitmapUtils            → setFieldPresent for fields 2,3,4,11,12,49        │
│ 17. Iso8583Constants       → MTI_AUTH_REQUEST="0100", PROC_CODE_PURCHASE       │
│ 18. Iso8583Field           → field definitions for encoding                    │
│ 19. Iso8583Message         → {mti:"0100", fields:{2:...,4:...}, bitmap:[...]} │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ STEP 4: SEND VIA NETTY TCP                                                      │
│ ═══════════════════════════                                                      │
│                                                                                 │
│ 20. BankNettyClient        → sendMessage(isoRequest)                           │
│ 21. CompletableFuture      → responseFuture = new CompletableFuture<>()        │
│ 22. BankChannelInitializer → creates pipeline (6 handlers)                     │
│ 23. Bootstrap              → .connect("localhost", 9090)                       │
│     → TCP 3-way handshake: SYN → SYN-ACK → ACK                               │
│                                                                                 │
│ OUTBOUND (sending to bank):                                                     │
│ 24. Iso8583Encoder         → Iso8583Message → binary bytes (~45 bytes)         │
│ 25. LengthFieldPrepender   → adds 4-byte length header → 49 bytes total       │
│ 26. TCP Socket             → bytes sent to bank:9090                           │
│ 27. NettyConfig            → EventLoopGroup (4 threads) manages the I/O        │
│                                                                                 │
│     ═══ Bank processes authorization (1-5 seconds) ═══                          │
│                                                                                 │
│ INBOUND (receiving from bank):                                                  │
│ 28. TCP Socket             → response bytes received                           │
│ 29. LengthFieldBasedFrameDecoder → strip 4-byte header, wait for complete msg │
│ 30. ReadTimeoutHandler     → no timeout (response arrived in time)             │
│ 31. Iso8583Decoder         → binary bytes → Iso8583Message                     │
│     └── Iso8583MessageParser → parse(data) → MTI + bitmap + fields            │
│ 32. BankResponseHandler    → responseFuture.complete(isoResponse)              │
│     → CompletableFuture COMPLETED → controller's .join() UNBLOCKS             │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ STEP 5: PARSE RESPONSE                                                          │
│ ══════════════════════                                                           │
│                                                                                 │
│ 33. Iso8583Constants       → FIELD_RESPONSE_CODE=39, FIELD_AUTH_CODE=38        │
│     → responseCode = "00" (APPROVED!)                                          │
│     → authCode = "A12345"                                                      │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ STEP 6: RECORD METRICS                                                          │
│ ══════════════════════                                                           │
│                                                                                 │
│ 34. SmartRoutingService    → recordResult("bank-alpha", true, 1200ms)          │
│ 35. RoutingMetricsRepository → update successRate + avgLatency                 │
│     Alpha Bank: successRate = 1/1 = 1.00, avgLatency = 1200ms                 │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│ RETURN RESPONSE                                                                 │
│ ═══════════════                                                                  │
│                                                                                 │
│ 36. RoutingResponse        → .approved("A12345", "bank-alpha", 1200)           │
│ 37. RoutingController      → ResponseEntity.ok(response)                       │
│     → JSON: {"success":true,"authorizationCode":"A12345",                      │
│              "responseCode":"00","responseMessage":"Approved",                  │
│              "bankId":"bank-alpha","latencyMs":1200}                            │
│                                                                                 │
│ → HTTP response back to Payment Service via Feign                              │
│ → PaymentService reads: success=true, authCode="A12345"                        │
│ → Payment status → AUTHORIZED                                                  │
│                                                                                 │
│ = 37 files touched for ONE routing request                                     │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. How Routing Service Connects to Infrastructure

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  ROUTING SERVICE (Port 8084)                                                   │
│       │          │          │          │                                        │
│       ▼          ▼          ▼          ▼                                        │
│                                                                                 │
│  Bank Simulator  DynamoDB   Eureka     Config Server                           │
│  :9090 (TCP)     :4566      :8761      :8888                                   │
│                  (LocalStack)                                                  │
│                                                                                 │
│  BANK SIMULATOR (TCP, ISO 8583)                                                │
│  ──────────────────────────────                                                │
│  WHAT: Simulates a bank's authorization gateway                                │
│  HOW: Netty TCP → ISO 8583 binary messages                                    │
│  FILES: NettyConfig, BankNettyClient, BankChannelInitializer,                 │
│         Iso8583Encoder, Iso8583Decoder, BankResponseHandler                   │
│  IF DOWN: @CircuitBreaker opens → fallback returns error response             │
│                                                                                 │
│  DYNAMODB (Routing Metrics — LocalStack)                                       │
│  ──────────────────────────────────────                                        │
│  WHAT: Stores bank performance metrics (success rate, latency)                 │
│  HOW: In dev: InMemoryRoutingMetricsRepository (ConcurrentHashMap)            │
│       In prod: Real DynamoDB via AWS SDK                                       │
│  FILES: DynamoDbConfig, RoutingMetricsRepository, BankRoute                   │
│  IF DOWN (prod): SmartRoutingService can't get bank list → 500 error          │
│  IF DOWN (dev): N/A — in-memory, no external dependency                       │
│                                                                                 │
│  EUREKA (Service Registry)                                                     │
│  ────────────────────────                                                      │
│  WHAT: Routing registers itself for Payment Service to discover                │
│  HOW: @EnableDiscoveryClient + eureka.client config                           │
│  FILES: application.yml, RoutingServiceApplication                            │
│  IF DOWN: Payment Service's Feign can't find routing-service → 503            │
│                                                                                 │
│  NO POSTGRESQL / NO REDIS / NO KAFKA                                           │
│  ─────────────────────────────────────                                         │
│  This is the only PayFlow service without a relational database.              │
│  It doesn't cache (no Redis), doesn't publish events (no Kafka).              │
│  It's a pure COMPUTATION + NETWORKING service.                                │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. How Routing Service Connects to Other Microservices

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │ PAYMENT SERVICE (Port 8083) — THE CALLER                      │             │
│  │                                                               │             │
│  │ HOW: RoutingServiceClient (@FeignClient) → POST /internal/route│            │
│  │ SENDS: {merchantId, amount, currency, paymentMethod, cardBin} │             │
│  │ RECEIVES: {success, authorizationCode, responseCode, bankId}  │             │
│  │                                                               │             │
│  │ IF ROUTING IS DOWN: PaymentService catches FeignException     │             │
│  │ → Payment status → FAILED, reason = "ROUTING_ERROR"           │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │ BANK SIMULATOR (Port 9090) — THE DOWNSTREAM                   │             │
│  │                                                               │             │
│  │ HOW: Netty TCP + ISO 8583 binary                             │             │
│  │ RECEIVES: Authorization request (MTI 0100)                    │             │
│  │ RETURNS: Authorization response (MTI 0110)                    │             │
│  │ SIMULATES: HDFC, SBI, ICICI — configurable response codes    │             │
│  │                                                               │             │
│  │ Routing → Bank Simulator = TCP (not HTTP!)                    │             │
│  │ Payment → Routing = HTTP/JSON (via Feign)                     │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │ API GATEWAY (Port 8080)                                       │             │
│  │                                                               │             │
│  │ Does NOT route to routing-service.                            │             │
│  │ /internal/** is not exposed externally.                       │             │
│  │ Only payment-service calls routing-service (service-to-service)│            │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. What Would Break If You Removed Each File

### Infrastructure Files

| Remove | What Breaks | Error |
|---|---|---|
| `pom.xml` | Everything | Build fails |
| `application.yml` | Startup | Missing bank.simulator, fraud, routing config |
| `RoutingServiceApplication` | Startup | No main class |

### ISO 8583 (Binary Protocol)

| Remove | What Breaks | Error |
|---|---|---|
| `Iso8583Field` | Constants + Encoder + Parser | Can't define field types |
| `BitmapUtils` | Message + Builder + Encoder | Can't manipulate bitmap |
| `Iso8583Constants` | Builder + Encoder + Decoder + Controller | No field definitions, no MTI codes |
| `Iso8583Message` | Builder + Parser + Encoder + Decoder | No message container |
| `Iso8583MessageBuilder` | Controller | Can't build ISO 8583 request |
| `Iso8583MessageParser` | Decoder | Can't parse bank response |

### Netty (TCP Networking)

| Remove | What Breaks | Error |
|---|---|---|
| `NettyConfig` | BankNettyClient | No EventLoopGroup bean |
| `BankNettyClient` | Controller | Can't send to bank |
| `BankChannelInitializer` | BankNettyClient | No pipeline → can't encode/decode |
| `Iso8583Encoder` | Pipeline | Can't send messages to bank |
| `Iso8583Decoder` | Pipeline | Can't read bank responses |
| `BankResponseHandler` | Pipeline | Response never delivered to CompletableFuture |

### Fraud Detection

| Remove | What Breaks | Error |
|---|---|---|
| `RuleEngine` | FraudDetectionService | No qualifying bean |
| `FraudFeatureExtractor` | FraudDetectionService | No qualifying bean |
| `DecisionTreeScorer` | FraudDetectionService | No qualifying bean |
| `FraudResult` | FraudDetectionService + Controller | No FraudResult class |
| `FraudDetectionService` | Controller | No qualifying bean |

### Smart Routing

| Remove | What Breaks | Error |
|---|---|---|
| `BankRoute` | Everything in routing | No bank data model |
| `RoutingDecision` | SmartRoutingService | No decision container |
| `RoutingMetricsRepository` | SmartRoutingService | No qualifying bean |
| `SmartRoutingService` | Controller | No qualifying bean |

### Config + Controller

| Remove | What Breaks | Error |
|---|---|---|
| `DynamoDbConfig` | SmartRoutingService | No RoutingMetricsRepository bean |
| `Resilience4jConfig` | @CircuitBreaker | No CircuitBreakerRegistry bean |
| `RoutingRequest` | Controller | Can't deserialize request |
| `RoutingResponse` | Controller | Can't serialize response |
| `RoutingController` | No `/internal/route` | 404 on all requests |
| `Dockerfile` | Can't containerize | Still works locally |

---

## 6. Summary — The Complete Mental Model

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  THE ROUTING SERVICE IN ONE PICTURE:                                           │
│                                                                                 │
│  WHAT IT IS:                                                                   │
│    The bridge between PayFlow and the banking world.                           │
│    Translates JSON → binary. HTTP → TCP. Application → bank.                  │
│                                                                                 │
│  6 TECHNOLOGY DOMAINS:                                                         │
│    ISO 8583 (binary protocol) + Netty (TCP networking) +                      │
│    Fraud Detection (rules + ML) + Smart Routing (epsilon-greedy) +            │
│    Resilience4j (circuit breaker) + DynamoDB (metrics storage)               │
│                                                                                 │
│  WHAT IT DEPENDS ON (built before it):                                         │
│    common-lib     → shared utilities                                          │
│    Eureka         → service registration (Payment finds us)                  │
│    Config Server  → centralized config (optional locally)                    │
│    Bank Simulator → TCP server on port 9090 (Part 10)                        │
│                                                                                 │
│  WHAT DEPENDS ON IT:                                                           │
│    Payment Service → calls us via Feign for bank authorization               │
│                                                                                 │
│  KEY PATTERNS:                                                                 │
│    Epsilon-Greedy  → 90% best bank, 10% random (discovers improvements)      │
│    Circuit Breaker → CLOSED → OPEN → HALF_OPEN (protects against failures)   │
│    Hybrid Fraud    → (rules × 0.6) + (ML × 0.4) → APPROVE/REVIEW/DECLINE   │
│    Binary Protocol → ISO 8583 bitmap + typed fields (not JSON)               │
│    Event Loop I/O  → 4 Netty threads handle all TCP connections             │
│    Pipeline        → Encoder → Prepender → TCP → Decoder → Handler           │
│                                                                                 │
│  HOW IT'S DIFFERENT FROM ALL OTHER PAYFLOW SERVICES:                          │
│    ❌ No PostgreSQL, no JPA, no Flyway, no SQL at all                        │
│    ❌ No Spring Security (internal-only service)                              │
│    ❌ No Redis, no Kafka, no Feign client                                     │
│    ❌ No MapStruct, no @Entity, no @Repository (JPA)                          │
│    ✅ Raw TCP networking (Netty)                                              │
│    ✅ Binary protocol (ISO 8583)                                              │
│    ✅ ML-inspired algorithms (decision tree, multi-armed bandit)              │
│    ✅ Circuit breaker (Resilience4j)                                          │
│    ✅ AWS SDK (DynamoDB)                                                      │
│    ✅ Bit manipulation (bitmap encoding)                                      │
│                                                                                 │
│  NUMBERS:                                                                      │
│    34 source files + 4 test files                                             │
│    1 endpoint (POST /internal/route)                                          │
│    6 steps per request (fraud → route → build → send → parse → record)       │
│    21 unit tests                                                               │
│    4 pre-configured banks (Alpha 95%, Beta 88%, Gamma 92%, Delta 85%)        │
│    7 fraud decision nodes + 3 fraud rules + 9 features                       │
│    6 Netty pipeline handlers                                                  │
│    10 ISO 8583 field definitions                                              │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part09-routing-service-overview.md) | Routing Service Overview |
| [Part 9a](./phase4-part09a-routing-project-setup.md) | Project Setup |
| [Part 9b](./phase4-part09b-iso8583-foundation.md) | ISO 8583 Foundation |
| [Part 9c](./phase4-part09c-iso8583-messages.md) | ISO 8583 Messages + Tests |
| [Part 9d](./phase4-part09d-netty-config-client.md) | Netty Config + Client |
| [Part 9e](./phase4-part09e-netty-pipeline.md) | Netty Pipeline |
| [Part 9f](./phase4-part09f-fraud-rule-engine.md) | Fraud Rule Engine |
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML + Service + Tests |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| **Part 9k** | **How Everything Connects** (You are here) |

---

## 🎉 Routing Service Complete!

You've built the most technically advanced service in PayFlow across 11 parts:

| Part | What You Built | New Concepts |
|---|---|---|
| **9a** | pom.xml, application.yml, main class | Different parent POM, 6 new dependencies, circuit breaker YAML |
| **9b** | Iso8583Field, BitmapUtils, Constants | Java records, bit manipulation (AND/OR/NOT/shift), binary protocol |
| **9c** | Message, Builder, Parser + tests | Builder pattern, ByteBuffer, roundtrip testing |
| **9d** | NettyConfig, BankNettyClient | NioEventLoopGroup, Bootstrap, CompletableFuture, non-blocking I/O |
| **9e** | Initializer, Encoder, Decoder, Handler | Pipeline pattern, frame decoders, MessageToByteEncoder |
| **9f** | RuleEngine, FraudFeatureExtractor | Velocity check, sliding window, feature extraction, BIN risk |
| **9g** | DecisionTreeScorer, FraudResult, Service + tests | Weighted scoring, hybrid ML, ReflectionTestUtils |
| **9h** | BankRoute, RoutingDecision, Repository, Service + tests | Epsilon-greedy, multi-armed bandit, Comparator chaining |
| **9i** | DynamoDbConfig, Resilience4jConfig | AWS SDK, @Profile beans, circuit breaker event listeners |
| **9j** | DTOs, Controller, Dockerfile, curl | 6-step orchestration, @CircuitBreaker, JSON→ISO 8583 translation |
| **9k** | Connections & Flows | 37-file transaction trace, complete mental model |

---

*Next service: [Phase 4 Part 10 — Bank Simulator](./phase4-part10-bank-simulator.md) →*
