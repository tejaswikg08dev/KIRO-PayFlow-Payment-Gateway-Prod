# 🏗️ Phase 4 Part 09: Routing Service — Overview & Roadmap

> **"When a customer taps 'Pay', the routing service decides which bank to call, checks for fraud, speaks binary ISO 8583, and sends the message over raw TCP — all in under 5 seconds."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 9 (split into 9a through 9k) |
| **Module** | `routing-service` |
| **Package** | `com.payflow.routing` |
| **Port** | 8084 |
| **Database** | None (no PostgreSQL, no JPA, no Flyway!) |
| **Storage** | DynamoDB (AWS) / In-Memory (dev) — for routing metrics |
| **Also Uses** | Netty (TCP), ISO 8583 (binary protocol), Resilience4j (circuit breaker) |
| **Previous** | [Phase 4 Part 8k — Payment Connections](./phase4-part08k-payment-connections-flows.md) |
| **Next** | [Phase 4 Part 10 — Bank Simulator](./phase4-part10-bank-simulator.md) |

---

## 🎯 What Is the Routing Service?

The Routing Service is the **bridge between PayFlow and the banking world**. While Payment Service manages orders and state machines, Routing Service actually **talks to banks** — in their language (ISO 8583), over their protocol (raw TCP), with fraud protection and intelligent bank selection.

### Real-World Analogy

Think of it like a **travel agent booking flights**:

| Step | Travel Agent | Routing Service |
|---|---|---|
| 1 | Customer wants to fly Delhi → Mumbai | Payment Service wants to authorize ₹1,500 |
| 2 | Agent checks if customer is suspicious (ID verification) | **Fraud Detection** checks velocity, amount, geo-location |
| 3 | Agent picks the best airline (cheapest, most reliable) | **Smart Routing** picks the best bank (highest success rate) |
| 4 | Agent fills the airline's booking form (their format, not yours) | **ISO 8583 Builder** creates a binary message in the bank's format |
| 5 | Agent sends the form via fax (not email — airlines still use fax!) | **Netty TCP Client** sends binary bytes over raw TCP (not HTTP!) |
| 6 | Agent gets "CONFIRMED" or "DENIED" back | Bank returns response code "00" (approved) or "51" (insufficient funds) |
| 7 | Agent records which airline was reliable for future bookings | **Smart Routing** updates bank's success rate for next time |

---

## 🛠️ Tech Stack — What's COMPLETELY NEW

This service introduces **6 technologies you've never seen** in PayFlow before:

| Technology | Version | Purpose | Seen Before? |
|---|---|---|---|
| **Java** | 17 | Language | Same |
| **Spring Boot** | 3.2.5 | Framework | Same |
| **Spring Validation** | (via starter) | Input validation | Same |
| **Spring Cloud Eureka** | 2023.0.1 | Service discovery | Same |
| **Spring Cloud Config** | 2023.0.1 | Centralized config | Same |
| **SpringDoc OpenAPI** | 2.5.0 | Swagger UI | Same |
| **Lombok** | (via parent) | Boilerplate reduction | Same |
| **Netty** | 4.1.108 | Async TCP client for bank communication | 🆕 **COMPLETELY NEW** |
| **ISO 8583** | (custom impl) | Binary financial messaging protocol | 🆕 **COMPLETELY NEW** |
| **Resilience4j** | 2.2.0 | Circuit breaker for bank failures | 🆕 **COMPLETELY NEW** |
| **AWS DynamoDB SDK** | 2.25.27 | Routing metrics storage | 🆕 **COMPLETELY NEW** |
| **Bit Manipulation** | (JDK) | Bitmap encoding for ISO 8583 | 🆕 **COMPLETELY NEW** |
| **Multi-Armed Bandit** | (custom impl) | Epsilon-greedy bank selection algorithm | 🆕 **COMPLETELY NEW** |

### What's MISSING Compared to Previous Services

| Technology | Identity/Merchant/Payment | Routing Service | Why Different |
|---|---|---|---|
| **PostgreSQL** | ✅ Used | ❌ Not used | No relational data — bank metrics go to DynamoDB |
| **JPA / Hibernate** | ✅ Used | ❌ Not used | No SQL database = no ORM needed |
| **Flyway** | ✅ Used | ❌ Not used | No SQL database = no migrations |
| **MapStruct** | ✅ Used | ❌ Not used | Simple DTOs, manual conversion |
| **Redis** | ✅ Payment used | ❌ Not used | No idempotency needed (called internally) |
| **Kafka** | ✅ Payment used | ❌ Not used | Doesn't publish events (Payment does that) |
| **Feign** | ✅ Payment used | ❌ Not used | IS the Feign target (doesn't call others) |
| **Spring Security** | ✅ Used | ❌ Not used | Internal-only service, no auth needed |

**This is a fundamentally different kind of service.** Previous services were "CRUD + business logic over HTTP/JSON". This one is "binary protocol + TCP networking + ML algorithms".

---

## 📐 What We Build — 1 Endpoint, 6 Internal Steps

### The Only Endpoint

| Method | Path | Description | Called By |
|---|---|---|---|
| POST | `/internal/route` | Route a payment to the appropriate bank | Payment Service (via Feign) |

**Just ONE endpoint** — but behind it are 6 complex steps:

```
POST /internal/route
  │
  ├── Step 1: FRAUD DETECTION
  │   └── RuleEngine (velocity + amount + geo) + DecisionTreeScorer (ML features)
  │   └── Combined score: (rules × 0.6) + (ML × 0.4) → APPROVE / REVIEW / DECLINE
  │
  ├── Step 2: SMART ROUTING
  │   └── Epsilon-greedy: 90% exploit (best bank) + 10% explore (random bank)
  │
  ├── Step 3: BUILD ISO 8583 MESSAGE
  │   └── MTI + Bitmap + Fields → binary bytes
  │
  ├── Step 4: SEND VIA NETTY TCP
  │   └── Bootstrap → connect → pipeline (encoder → frame prepender → TCP)
  │   └── Receive: TCP → frame decoder → decoder → response handler → CompletableFuture
  │
  ├── Step 5: PARSE RESPONSE
  │   └── Response code "00" → APPROVED, "51" → Insufficient Funds, etc.
  │
  └── Step 6: RECORD METRICS
      └── Update bank's success rate + latency for future routing decisions
```

---

## 🔄 How Payment Service Calls Routing Service

```
┌─────────────────────────────┐         ┌─────────────────────────────────────┐
│     PAYMENT SERVICE          │         │        ROUTING SERVICE               │
│     (Port 8083)              │         │        (Port 8084)                   │
│                              │         │                                     │
│  PaymentService.authorize()  │         │                                     │
│       │                      │         │                                     │
│       ▼                      │         │                                     │
│  RoutingServiceClient        │  Feign  │  RoutingController                  │
│  @FeignClient("routing-     ─┼────────►│  POST /internal/route               │
│   service")                  │  HTTP   │       │                             │
│  routePayment(request)       │         │       ├── FraudDetectionService     │
│                              │         │       ├── SmartRoutingService       │
│       ▲                      │         │       ├── Iso8583MessageBuilder     │
│       │                      │  HTTP   │       ├── BankNettyClient (TCP)     │
│  receives response ◄─────────┼─────────│       ├── Iso8583MessageParser      │
│  {status, authCode, ...}     │         │       └── recordResult()            │
│                              │         │                                     │
└─────────────────────────────┘         └─────────────────────────────────────┘
```

**KEY INSIGHT:** Payment Service sends JSON over HTTP (via Feign). Routing Service translates that into ISO 8583 binary over TCP, sends it to the bank, translates the binary response back to JSON, and returns it to Payment Service.

---

## 🏗️ Architecture Diagram

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│                       ROUTING SERVICE (Port 8084)                                  │
├───────────────────────────────────────────────────────────────────────────────────┤
│                                                                                   │
│   HTTP from Payment Service (Feign)                                               │
│       │                                                                           │
│       ▼                                                                           │
│   ┌──────────────────────────────────────────────────────────────────┐           │
│   │  RoutingController  POST /internal/route                          │           │
│   │  @CircuitBreaker(name = "bankCommunication")                     │           │
│   └──────┬───────────────────────────────────────────────────────────┘           │
│          │                                                                        │
│   ┌──────┼───────────────────────────────────────────────────────┐              │
│   │      ▼                                                        │              │
│   │  STEP 1: FRAUD CHECK                                          │              │
│   │  ┌────────────┐  ┌──────────────────┐  ┌─────────────────┐  │              │
│   │  │ RuleEngine  │  │FraudFeatureExtra-│  │DecisionTreeScorer│  │              │
│   │  │ •Velocity   │  │ctor (9 features) │  │(7 weighted nodes)│  │              │
│   │  │ •Amount     │  └────────┬─────────┘  └────────┬────────┘  │              │
│   │  │ •Geo-block  │           │                      │           │              │
│   │  └─────┬──────┘           ▼                      ▼           │              │
│   │        │         FraudDetectionService                        │              │
│   │        └────────► (rules×0.6) + (ML×0.4) = FraudResult      │              │
│   │                   Score < 30 → APPROVE                        │              │
│   │                   Score > 70 → DECLINE (stop here!)           │              │
│   └───────────────────────────────────────────────────────────────┘              │
│          │                                                                        │
│   ┌──────┼───────────────────────────────────────────────────────┐              │
│   │      ▼                                                        │              │
│   │  STEP 2: SMART ROUTING (Epsilon-Greedy)                       │              │
│   │  SmartRoutingService                                          │              │
│   │    90% → exploit (pick best bank by success rate)             │              │
│   │    10% → explore (pick random bank to gather data)            │              │
│   │  ┌────────────┐  ┌────────────┐  ┌────────────┐             │              │
│   │  │ Alpha Bank │  │ Beta Bank  │  │ Gamma Bank │  ...        │              │
│   │  │ 95% / 120ms│  │ 88% / 200ms│  │ 92% / 150ms│             │              │
│   │  └────────────┘  └────────────┘  └────────────┘             │              │
│   └───────────────────────────────────────────────────────────────┘              │
│          │                                                                        │
│   ┌──────┼───────────────────────────────────────────────────────┐              │
│   │      ▼                                                        │              │
│   │  STEP 3-4: ISO 8583 + NETTY                                   │              │
│   │  Iso8583MessageBuilder → binary message                       │              │
│   │       │                                                       │              │
│   │       ▼                                                       │              │
│   │  BankNettyClient ──TCP──► Bank Simulator (:9090)             │              │
│   │       │                        │                              │              │
│   │       │   ◄──ISO 8583 response─┘                              │              │
│   │       ▼                                                       │              │
│   │  Iso8583MessageParser → extract response code + auth code    │              │
│   └───────────────────────────────────────────────────────────────┘              │
│          │                                                                        │
│          ▼                                                                        │
│   RoutingResponse → back to Payment Service (JSON over HTTP)                     │
│                                                                                   │
└───────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📄 Sub-Parts — Build Order (9a → 9k)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    BUILD ORDER (9a → 9k)                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  9a  Project Setup        pom.xml, application.yml, main class          │
│   ▼                       "Set up the project with 6 new dependencies"   │
│  9b  ISO 8583 Foundation  Iso8583Field, BitmapUtils, Iso8583Constants   │
│   ▼                       "Define the binary protocol vocabulary"        │
│  9c  ISO 8583 Messages    Iso8583Message, Builder, Parser + tests       │
│   ▼                       "Build and parse binary messages"              │
│  9d  Netty Config+Client  NettyConfig, BankNettyClient                  │
│   ▼                       "Configure TCP networking"                     │
│  9e  Netty Pipeline       Initializer, Encoder, Decoder, Handler        │
│   ▼                       "Wire the message processing chain"            │
│  9f  Fraud Rule Engine    RuleEngine, FraudFeatureExtractor             │
│   ▼                       "Detect fraud with rules + feature extraction" │
│  9g  Fraud ML + Service   DecisionTreeScorer, FraudResult,              │
│   ▼                       FraudDetectionService + tests                 │
│  9h  Smart Routing        BankRoute, RoutingDecision,                   │
│   ▼                       RoutingMetricsRepository,                     │
│                           SmartRoutingService + tests                   │
│  9i  Config Classes       DynamoDbConfig, Resilience4jConfig            │
│   ▼                       "Configure DynamoDB + circuit breaker"         │
│  9j  Controller+Docker    DTOs, RoutingController, Dockerfile, curl     │
│   ▼                       "Wire HTTP endpoint and orchestrate"           │
│  9k  Connections & Flows  How everything connects                       │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Why This Order?

| Step | What | Why It Goes Here |
|---|---|---|
| **9a** | Project Setup | Can't write code without dependencies (Netty, Resilience4j, DynamoDB SDK) |
| **9b** | ISO 8583 Foundation | Field types and bitmap utils are used by everything else in ISO 8583 |
| **9c** | ISO 8583 Messages | Builder/Parser use the foundation from 9b; needed before Netty can send |
| **9d** | Netty Config+Client | Netty EventLoopGroup config needed before building the pipeline |
| **9e** | Netty Pipeline | Pipeline handlers use Encoder/Decoder which use ISO 8583 from 9b/9c |
| **9f** | Fraud Rules | Rule engine is independent; feature extractor prepares data for ML |
| **9g** | Fraud ML + Service | Combines rule engine (9f) + ML scorer; returns FraudResult |
| **9h** | Smart Routing | Bank selection algorithm; uses RoutingMetricsRepository |
| **9i** | Config Classes | DynamoDB provides RoutingMetricsRepository; Resilience4j wraps the controller |
| **9j** | Controller + Docker | Orchestrates fraud (9g) + routing (9h) + ISO 8583 (9c) + Netty (9d/9e) |
| **9k** | Connections | Big picture: how 34 files work together |

---

## 🗂️ Complete File List (34 files)

```
backend/routing-service/
├── pom.xml                                                    ← 9a
├── Dockerfile                                                 ← 9j
└── src/
    ├── main/
    │   ├── java/com/payflow/routing/
    │   │   ├── RoutingServiceApplication.java                 ← 9a
    │   │   ├── config/
    │   │   │   ├── NettyConfig.java                           ← 9d
    │   │   │   ├── DynamoDbConfig.java                        ← 9i
    │   │   │   └── Resilience4jConfig.java                    ← 9i
    │   │   ├── iso8583/
    │   │   │   ├── Iso8583Field.java                          ← 9b
    │   │   │   ├── BitmapUtils.java                           ← 9b
    │   │   │   ├── Iso8583Constants.java                      ← 9b
    │   │   │   ├── Iso8583Message.java                        ← 9c
    │   │   │   ├── Iso8583MessageBuilder.java                 ← 9c
    │   │   │   └── Iso8583MessageParser.java                  ← 9c
    │   │   ├── netty/
    │   │   │   ├── BankChannelInitializer.java                ← 9e
    │   │   │   ├── BankNettyClient.java                       ← 9d
    │   │   │   ├── BankResponseHandler.java                   ← 9e
    │   │   │   ├── Iso8583Decoder.java                        ← 9e
    │   │   │   └── Iso8583Encoder.java                        ← 9e
    │   │   ├── fraud/
    │   │   │   ├── RuleEngine.java                            ← 9f
    │   │   │   ├── FraudFeatureExtractor.java                 ← 9f
    │   │   │   ├── DecisionTreeScorer.java                    ← 9g
    │   │   │   └── FraudResult.java                           ← 9g
    │   │   ├── routing/
    │   │   │   ├── BankRoute.java                             ← 9h
    │   │   │   ├── RoutingDecision.java                       ← 9h
    │   │   │   └── RoutingMetricsRepository.java              ← 9h
    │   │   ├── service/
    │   │   │   ├── FraudDetectionService.java                 ← 9g
    │   │   │   └── SmartRoutingService.java                   ← 9h
    │   │   ├── dto/
    │   │   │   ├── RoutingRequest.java                        ← 9j
    │   │   │   └── RoutingResponse.java                       ← 9j
    │   │   └── controller/
    │   │       └── RoutingController.java                     ← 9j
    │   └── resources/
    │       └── application.yml                                ← 9a
    └── test/java/com/payflow/routing/
        ├── iso8583/
        │   ├── Iso8583MessageBuilderTest.java                 ← 9c
        │   └── Iso8583MessageParserTest.java                  ← 9c
        └── service/
            ├── FraudDetectionServiceTest.java                 ← 9g
            └── SmartRoutingServiceTest.java                   ← 9h
```

---

## 🔐 Key Concepts — The Big 6

### 1. ISO 8583 — The Banking Language

```
WHAT: A binary protocol for financial messages. Every bank in the world understands it.
WHY:  Banks don't speak JSON. They've been using ISO 8583 since 1987.
HOW:  [MTI 4 bytes] + [Bitmap 8 bytes] + [Data Fields variable]

Example — Authorization Request:
  MTI:    "0100"                          → "I want to authorize a payment"
  Bitmap: 0x70300000 00000100             → "Fields 2, 3, 4, 11, 12, 49 are present"
  Field 2: "164111111111111111"           → LLVAR: length=16, PAN=4111...
  Field 3: "000000"                       → Processing code: purchase
  Field 4: "000000150000"                 → Amount: ₹1,500.00 (in paise)
  Field 11: "654321"                      → Trace number (unique per txn)
  Field 12: "143025"                      → Time: 2:30:25 PM
  Field 49: "356"                         → Currency: INR
```

### 2. Netty — TCP Without Thread-Per-Connection

```
WHAT: Async, event-driven network framework (the foundation of most Java financial systems).
WHY:  Banks use raw TCP, not HTTP. Netty handles TCP efficiently with few threads.
HOW:  EventLoop (4 threads) → Channel (TCP connection) → Pipeline (encode → send → receive → decode)

Traditional:  1 thread per connection → 1,000 connections = 1,000 threads (memory explosion)
Netty:        4 threads handle 10,000+ connections (event loop polls: "any channel ready?")
```

### 3. Fraud Detection — Hybrid Scoring

```
WHAT: Two-layer fraud check before routing to bank.
WHY:  Banks charge for every request. Catching fraud BEFORE the bank saves money.
HOW:
  Rule Engine (60% weight):
    • Velocity: >5 txns/minute from same merchant → suspicious
    • Amount: >₹50,000 → elevated risk
    • Geo-blocking: Currency from sanctioned country → block

  ML Scorer (40% weight):
    • 9 features extracted → 7 weighted decision nodes → score 0-100

  Final Score = (rules × 0.6) + (ML × 0.4)
    < 30  → APPROVE (proceed)
    30-70 → REVIEW (flag for human review, still process)
    > 70  → DECLINE (reject immediately, don't call bank)
```

### 4. Smart Routing — Epsilon-Greedy Multi-Armed Bandit

```
WHAT: Algorithm that picks the best bank while still exploring alternatives.
WHY:  HDFC might be 95% today. ICICI might have upgraded to 98%. You need to discover that.
HOW:
  90% of the time → EXPLOIT: Pick bank with highest success rate
  10% of the time → EXPLORE: Pick a random bank (to gather performance data)

  After each transaction → update bank's success rate + average latency
  Over time → the best bank gets 90% of traffic, others get 10% combined

  This is the SAME algorithm used by:
  - Google for A/B testing ad placements
  - Netflix for recommending shows
  - Clinical trials for drug testing
```

### 5. Circuit Breaker — Resilience4j

```
WHAT: Protects against cascading failures when a bank is down.
WHY:  If HDFC is returning errors, don't keep hammering it. Give it time to recover.
HOW:
  CLOSED (normal)     → requests pass through normally
  OPEN (tripped)      → requests immediately return fallback (no bank call)
  HALF-OPEN (testing) → allow a few test requests to check if bank recovered

  Trips when: 50% failure rate in the last 10 requests
  Recovers after: 30 seconds → tries 3 test requests → if OK, back to CLOSED
```

### 6. DynamoDB — NoSQL for Metrics

```
WHAT: AWS NoSQL database for storing bank routing metrics.
WHY:  Bank metrics (success rate, latency) don't need SQL. Key-value is perfect.
HOW:
  In production: DynamoDB on AWS
  In development: InMemoryRoutingMetricsRepository (ConcurrentHashMap)
  Uses @Profile("dev") to switch between them
```

---

## ✅ Prerequisites

1. ✅ **common-lib** built: `mvn install -pl common-lib -am -DskipTests`
2. ✅ **No PostgreSQL needed** — this service doesn't use a database
3. ✅ **Bank Simulator** running on port 9090 (Part 10 — for end-to-end testing)
4. ✅ **LocalStack** (optional) — for DynamoDB in dev (not needed, in-memory fallback exists)

---

## ✅ Final Verification Checklist (After All Parts)

- [ ] Service starts on port 8084
- [ ] Registers with Eureka at http://localhost:8761
- [ ] `POST /internal/route` with valid request → 200 with routing response
- [ ] Fraud score < 30 → APPROVE, routes to bank
- [ ] Fraud score > 70 → DECLINE, no bank call
- [ ] Epsilon-greedy: 90% exploit (best bank), 10% explore (random)
- [ ] ISO 8583 roundtrip: build → encode → parse → all fields preserved
- [ ] Netty connects to bank simulator on localhost:9090
- [ ] Circuit breaker opens after 50% failure rate, recovers after 30s
- [ ] Swagger UI loads at http://localhost:8084/swagger-ui.html
- [ ] All 21 unit tests pass (`mvn test`)
- [ ] Actuator health at http://localhost:8084/actuator/health

---

## 📚 Navigation

| Document | Title |
|---|---|
| **This document** | **Routing Service Overview & Roadmap** |
| [Part 9a](./phase4-part09a-routing-project-setup.md) | Project Setup (pom.xml, yml, main class) |
| [Part 9b](./phase4-part09b-iso8583-foundation.md) | ISO 8583 Foundation (Field, Bitmap, Constants) |
| [Part 9c](./phase4-part09c-iso8583-messages.md) | ISO 8583 Messages (Message, Builder, Parser + Tests) |
| [Part 9d](./phase4-part09d-netty-config-client.md) | Netty Config + Client |
| [Part 9e](./phase4-part09e-netty-pipeline.md) | Netty Pipeline (Initializer, Encoder, Decoder, Handler) |
| [Part 9f](./phase4-part09f-fraud-rule-engine.md) | Fraud Rule Engine + Feature Extractor |
| [Part 9g](./phase4-part09g-fraud-ml-service.md) | Fraud ML Scorer + FraudDetectionService + Tests |
| [Part 9h](./phase4-part09h-smart-routing.md) | Smart Routing (Epsilon-Greedy) + Tests |
| [Part 9i](./phase4-part09i-config-classes.md) | Config Classes (DynamoDB, Resilience4j) |
| [Part 9j](./phase4-part09j-controller-docker.md) | Controller + DTOs + Docker + curl |
| [Part 9k](./phase4-part09k-connections-flows.md) | How Everything Connects |

---

*Start with [Part 9a — Project Setup](./phase4-part09a-routing-project-setup.md) →*
