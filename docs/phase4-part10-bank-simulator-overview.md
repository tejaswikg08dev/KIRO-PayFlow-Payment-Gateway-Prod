# 🏗️ Phase 4 Part 10: Bank Simulator — Overview & Roadmap

> **"You can't test a payment gateway against a real bank — every test costs real money. The bank simulator speaks the same protocol, on the same port, with configurable approve/decline rules."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 10 (split into 10a through 10d) |
| **Module** | `bank-simulator` |
| **Package** | `com.payflow.bank` |
| **HTTP Port** | 9000 (Spring Boot management / actuator) |
| **TCP Port** | 9090 (Netty ISO 8583 server) |
| **Database** | None |
| **Previous** | [Phase 4 Part 9k — Routing Connections](./phase4-part09k-connections-flows.md) |
| **Next** | [Phase 4 Part 11 — Settlement Service](./phase4-part11-settlement-service.md) |

---

## 🎯 What Is the Bank Simulator?

The bank simulator is a **fake bank** that speaks ISO 8583 over TCP. It replaces real banks (Visa, HDFC, SBI) during development and testing.

### Why We Need It

```
WITHOUT Simulator:
  PayFlow → Visa (PRODUCTION) → Real money moves! 💸
  Problems:
    • Every test costs real money (bank charges per authorization)
    • Can't simulate failures (banks don't fail on demand)
    • Can't run tests offline (need bank VPN + certification)
    • Takes months to get bank test credentials

WITH Simulator:
  PayFlow → Bank Simulator (LOCAL) → Fake response 🎭
  Benefits:
    • Free unlimited testing
    • Simulate any failure (decline, timeout, error)
    • Works offline on your laptop
    • Instant — no certification needed
    • Configurable: 85% approve, 15% decline (realistic)
```

### Real-World Analogy

Think of it like a **flight simulator** for pilots:
- A real plane costs $10,000/hour to fly
- A simulator costs nothing and you can practice emergencies
- The controls are identical — same instruments, same responses
- The bank simulator speaks the SAME protocol (ISO 8583 over TCP) on the SAME port (9090)

---

## 🛠️ Tech Stack

| Technology | Purpose | Same as Routing Service? |
|---|---|---|
| **Java 17** | Language | Same |
| **Spring Boot 3.2.5** | Application framework | Same |
| **Netty** | TCP **server** (accepts connections) | Routing used Netty TCP **client** |
| **Spring Actuator** | Health endpoint on HTTP port 9000 | Same |
| **Lombok** | `@Slf4j`, `@Data` | Same |

### What's NOT Here (Compared to Routing Service)

| Routing Service Has | Bank Simulator Does NOT | Why |
|---|---|---|
| ISO 8583 Builder/Parser | ❌ | Simplified string-based message handling |
| Fraud Detection | ❌ | Bank doesn't do fraud (that's the gateway's job) |
| Smart Routing | ❌ | Bank doesn't route (it IS the destination) |
| Resilience4j | ❌ | No circuit breaker needed on the server side |
| DynamoDB | ❌ | No metrics to store |
| Eureka | ❌ | Not a discoverable microservice — direct TCP connection |

**THIS IS THE SIMPLEST MODULE IN PAYFLOW** — just 8 source files, 3 dependencies, and one job: receive ISO 8583 requests, decide approve/decline, send response.

---

## 🔄 Server vs Client — The Mirror

The bank simulator is the **mirror image** of the routing service's Netty client:

```
┌─────────────────────────────┐         ┌─────────────────────────────┐
│     ROUTING SERVICE          │   TCP   │     BANK SIMULATOR           │
│     (Part 9 — Client)       │ ◄─────► │     (Part 10 — Server)       │
│                              │         │                              │
│  Bootstrap (client)          │         │  ServerBootstrap (server)    │
│  NioSocketChannel            │         │  NioServerSocketChannel      │
│  1 EventLoopGroup (worker)   │         │  2 EventLoopGroups           │
│                              │         │    (boss + worker)           │
│  CONNECTS to :9090           │         │  LISTENS on :9090            │
│                              │         │                              │
│  Sends: ISO 8583 request     │  ────►  │  Receives: ISO 8583 request  │
│  Receives: ISO 8583 response │  ◄────  │  Sends: ISO 8583 response    │
│                              │         │                              │
│  Iso8583Encoder (binary)     │         │  StringDecoder (text)        │
│  4-byte length prefix        │         │  2-byte length prefix        │
│  CompletableFuture response  │         │  Immediate ctx.writeAndFlush │
└─────────────────────────────┘         └─────────────────────────────┘
```

| Aspect | Routing Service (Client) | Bank Simulator (Server) |
|---|---|---|
| Netty class | `Bootstrap` | `ServerBootstrap` |
| Channel type | `NioSocketChannel` | `NioServerSocketChannel` |
| EventLoopGroups | 1 (worker only) | 2 (boss accepts + worker handles) |
| Who initiates? | Client CONNECTS | Server ACCEPTS |
| Port | Random outgoing | Fixed: 9090 |
| Message format | Binary Iso8583Message | Simplified String-based |
| Pipeline codec | Iso8583Encoder/Decoder | StringEncoder/Decoder |

---

## 📐 What We Build — 2 Ports, 1 Decision Pipeline

### Ports

| Port | Protocol | Purpose |
|---|---|---|
| **9000** | HTTP | Spring Boot web server (actuator health/metrics) |
| **9090** | TCP | Netty ISO 8583 server (bank authorization) |

### Decision Pipeline

```
ISO 8583 Request arrives on TCP :9090
  │
  ▼
Iso8583RequestHandler
  ├── simulateLatency() → random 50-500ms sleep
  ├── Parse fields: MTI, PAN, Amount, RRN
  │
  ▼
ResponseGenerator.generateResponse(pan, amount)
  │
  ├── Priority 1: CardBinRules.evaluate(pan)
  │   4111 → "00" (approve)
  │   4000 → "05" (decline)
  │   5500 → "00" (approve)
  │   5400 → "14" (decline)
  │   4917 → "51" (insufficient funds)
  │   null → continue to next rule
  │
  ├── Priority 2: AmountRules.evaluate(amount)
  │   ≤ 0     → "12" (invalid)
  │   > 100K  → "61" (exceeds limit)
  │   ends 13 → "05" (decline)
  │   null → continue to random
  │
  └── Priority 3: Random (85% success rate)
      random < 85 → "00" (approve)
      random ≥ 85 → random("05","51","14")
  │
  ▼
Response: "0110" + PAN + Amount + RRN + ResponseCode
  → sent back through TCP pipeline
```

---

## 📄 Sub-Parts — Build Order (10a → 10d)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    BUILD ORDER (10a → 10d)                                │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  10a  Project Setup         pom.xml, application.yml, main class,       │
│   ▼                         SimulatorConfig                              │
│  10b  Netty TCP Server      BankSimulatorServer, BankChannelInitializer │
│   ▼                         "Accept TCP connections, set up pipeline"    │
│  10c  Handler + Logic       Iso8583RequestHandler, ResponseGenerator,   │
│   ▼                         CardBinRules, AmountRules                   │
│                              "Process requests, decide approve/decline"  │
│  10d  Docker + Testing      Dockerfile, curl, connections to routing    │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 🗂️ Complete File List (8 source files)

```
backend/bank-simulator/
├── pom.xml                                                    ← 10a
├── Dockerfile                                                 ← 10d
└── src/main/
    ├── java/com/payflow/bank/
    │   ├── BankSimulatorApplication.java                      ← 10a
    │   ├── config/
    │   │   └── SimulatorConfig.java                           ← 10a
    │   ├── server/
    │   │   ├── BankSimulatorServer.java                       ← 10b
    │   │   ├── BankChannelInitializer.java                    ← 10b
    │   │   └── Iso8583RequestHandler.java                     ← 10c
    │   └── logic/
    │       ├── ResponseGenerator.java                         ← 10c
    │       ├── CardBinRules.java                              ← 10c
    │       └── AmountRules.java                               ← 10c
    └── resources/
        └── application.yml                                    ← 10a
```

**8 SOURCE FILES — the smallest service in PayFlow.** Compare:
- Identity: ~15 files
- Merchant: ~22 files
- Payment: ~45 files
- Routing: ~34 files
- **Bank Simulator: 8 files**

---

## 🔐 Test Cards — Quick Reference

| Card Number | BIN | Result | Response Code |
|---|---|---|---|
| `4111111111111111` | 4111 | ✅ Always approve | `"00"` |
| `5500000000000004` | 5500 | ✅ Always approve | `"00"` |
| `4000000000000002` | 4000 | ❌ Always decline | `"05"` (Do Not Honor) |
| `5400000000000001` | 5400 | ❌ Always decline | `"14"` (Invalid Card) |
| `4917000000000000` | 4917 | ❌ Always decline | `"51"` (Insufficient Funds) |
| Any other card | — | 🎲 85% approve, 15% decline | Random |

---

## ✅ Prerequisites

1. ✅ **No database needed** — pure computation + networking
2. ✅ **No Eureka needed** — direct TCP connection (not service discovery)
3. ✅ **No common-lib needed** — bank simulator doesn't use PayFlow shared code (only Netty)

---

## ✅ Final Verification Checklist

- [ ] HTTP health: `http://localhost:9000/actuator/health` → `{"status":"UP"}`
- [ ] TCP server listening on port 9090
- [ ] Test card 4111... → always approved ("00")
- [ ] Test card 4000... → always declined ("05")
- [ ] Amount > ₹1,00,000 → declined ("61")
- [ ] Random cards get ~85% approval rate
- [ ] Latency: 50-500ms per response (simulated)
- [ ] Routing service can connect and get responses

---

## 📚 Navigation

| Document | Title |
|---|---|
| **This document** | **Bank Simulator Overview & Roadmap** |
| [Part 10a](./phase4-part10a-bank-project-setup.md) | Project Setup (pom.xml, yml, main class, config) |
| [Part 10b](./phase4-part10b-bank-netty-server.md) | Netty TCP Server (ServerBootstrap, pipeline) |
| [Part 10c](./phase4-part10c-bank-handler-logic.md) | Request Handler + Decision Logic |
| [Part 10d](./phase4-part10d-bank-docker-testing.md) | Dockerfile + Testing + Connections |

---

*Start with [Part 10a — Project Setup](./phase4-part10a-bank-project-setup.md) →*
