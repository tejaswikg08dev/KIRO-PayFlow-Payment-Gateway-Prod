# Phase 0 Part 1: Project Overview — What Is PayFlow?

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 0 — Project Overview & Setup |
| **Part** | 1 of 2 |
| **Previous** | None (this is the beginning!) |
| **Next** | [Phase 0 Part 2: Environment Setup](./phase0-part2-environment-setup.md) |
| **Time to Complete** | 1-2 hours (reading + understanding) |
| **Difficulty** | Beginner |
| **Prerequisites** | None — just curiosity |
| **What You'll Learn** | What payment gateways do, how money moves online, what we're building |
| **Git Commit** | N/A (no code in this part) |

---

## Table of Contents
- [What Are We Building?](#what-are-we-building)
- [How Payments Work in Real Life](#how-payments-work-in-real-life)
- [The Payment Ecosystem — 6 Players](#the-payment-ecosystem)
- [What Each Service Does](#what-each-service-does)
- [Technical Challenges We'll Solve](#technical-challenges)
- [Tech Stack](#tech-stack)
- [Architecture Overview](#architecture-overview)
- [What Makes This Production-Grade?](#production-grade)
- [Phase Roadmap](#phase-roadmap)
- [Project Folder Structure](#folder-structure)
- [Next Steps](#next-steps)

---

## What You'll Learn in This Part
- What a payment gateway actually does (in plain English)
- How money moves when you buy something online (the 2-second journey)
- Who are the 6 parties involved in every card payment
- What each of PayFlow's 11 services does (with real-world analogies)
- Why this project teaches more than 90% of tutorial apps
- The complete technology stack and why each tool was chosen

---

<a name="what-are-we-building"></a>
## What Are We Building?

### The One-Paragraph Explanation (Tell Your Parents This)

PayFlow is an **online payment system** — like the invisible software that runs when you tap "Pay" on Amazon, Swiggy, or any website. When you enter your card number and click "Pay ₹500", there's a complex system that talks to your bank, checks if you have money, reserves it, and tells the merchant "Payment successful!" — all in under 2 seconds. **We are building that system from scratch.**

### The Technical Explanation

PayFlow is a **payment gateway platform** — a Stripe/Razorpay clone built with 11 Java microservices. It handles the complete payment lifecycle:

```
Create Order → Authorize Payment → Capture Funds → Settlement → Payout to Merchant
```

Merchants integrate PayFlow into their websites using API keys. Customers pay through a hosted checkout page (card, UPI, or net banking). The system communicates with banks using the **ISO 8583** binary protocol over TCP, detects fraud using AI scoring, routes payments to the fastest bank, and delivers webhooks to merchants.

### Why This Project? (Career Value)

| What You'll Learn | Why Companies Care |
|---|---|
| Microservices architecture (11 services) | Every fintech company uses this pattern |
| Event-driven architecture (Kafka) | Netflix, Uber, Stripe all use Kafka |
| Binary protocol (ISO 8583 + Netty) | Shows you can work beyond REST APIs |
| Distributed transactions & idempotency | The #1 interview topic for senior roles |
| AI/ML in production (fraud scoring) | Applied ML, not just Jupyter notebooks |
| Full DevOps pipeline | Docker → CI/CD → AWS deployment |

**Real companies that use these exact patterns:** Stripe (500+ microservices), Razorpay (ISO 8583 + Kafka), PayPal (fraud ML + smart routing), Square (event-driven settlements).

---

<a name="how-payments-work-in-real-life"></a>
## How Payments Work in Real Life

### The Story: Buying a ₹500 Shirt on Amazon

You found a shirt on Amazon.in for ₹500. You click "Buy Now", enter your HDFC debit card number, and click "Pay". **What happens in the next 2 seconds?**

```
┌──────────┐   ①   ┌──────────┐   ②   ┌──────────────┐   ③   ┌──────────────┐
│ Customer │──────>│ Amazon   │──────>│ PayFlow      │──────>│ HDFC Bank    │
│ (You)    │       │ (Merchant)│       │ (Gateway)    │       │ (Your Bank)  │
│          │       │          │       │              │       │              │
│ Clicks   │       │ Sends    │       │ Checks fraud │       │ Checks       │
│ "Pay"    │       │ payment  │       │ Picks route  │       │ balance:     │
│          │       │ request  │       │ Talks to bank│       │ ₹12,000 > ₹500│
│          │       │ to PayFlow│      │ via ISO 8583 │       │ → APPROVED!  │
└──────────┘       └──────────┘       └──────────────┘       └──────┬───────┘
                                                                      │
     ⑥                    ⑤                     ④                     │
┌──────────┐       ┌──────────┐        ┌──────────────┐              │
│ Customer │<──────│ Amazon   │<───────│ PayFlow      │<─────────────┘
│          │       │          │        │              │
│ Sees:    │       │ Gets:    │        │ Gets:        │
│ "Payment │       │ "Payment │        │ "Approved,   │
│ Success!"│       │ authorized│       │ Auth Code:   │
│          │       │ — ship it"│       │ A12345"      │
└──────────┘       └──────────┘        └──────────────┘
```

**Step by step (all in ~1.5 seconds):**

| Step | What Happens | Time |
|------|-------------|------|
| ① | You click "Pay ₹500" on Amazon | 0ms |
| ② | Amazon sends payment request to PayFlow (our system) | 50ms |
| ③ | PayFlow checks for fraud (score: 15 = safe), picks HDFC as the best route, builds an ISO 8583 message, sends it over TCP to HDFC | 200ms |
| ④ | HDFC checks your balance (₹12,000 > ₹500), reserves ₹500, responds "Approved, auth code A12345" | 800ms |
| ⑤ | PayFlow receives approval, saves the payment record, publishes a "payment.authorized" event to Kafka, returns success to Amazon | 100ms |
| ⑥ | Amazon shows you "Payment Successful! Your shirt is on the way!" | 50ms |

**Total: ~1.2 seconds.** That's what we're building.

### Why Two Steps? (Authorize ≠ Capture)

You might wonder: "Why not just take the money immediately?"

**Real-world examples of why authorize and capture are separate:**

| Scenario | Authorize | Capture |
|----------|-----------|---------|
| Hotel booking | Reserve ₹5000 when you book | Charge actual amount (₹4200) at checkout |
| Petrol pump | Pre-auth ₹2000 when you insert card | Charge exact amount (₹1547) after filling |
| Amazon order | Reserve ₹500 when you order | Charge ₹500 only when item ships |
| Uber ride | Reserve ₹300 (estimated fare) | Charge ₹287 (actual fare) after ride |

**Key insight:** Authorization = "Can this customer pay?" / Capture = "Actually take the money."

If the item is out of stock, Amazon can cancel (void) the authorization without ever charging you.

### What Is Settlement? (The Money Actually Moving)

Authorization and capture happen instantly (customer perspective). But the actual money movement between banks happens later:

```
Day 1 (during the day):
  - 1000 payments captured for Amazon through PayFlow
  - Total: ₹5,00,000

Day 2 (midnight — settlement batch runs):
  - Gross amount: ₹5,00,000
  - Refunds today: ₹25,000
  - MDR fee (2%): ₹9,500
  - GST on MDR (18%): ₹1,710
  - Net payout to Amazon: ₹4,63,790
  
  PayFlow transfers ₹4,63,790 to Amazon's bank account.
  PayFlow keeps ₹11,210 (MDR + GST) as revenue.
```

**MDR (Merchant Discount Rate)** = the fee merchants pay PayFlow for every transaction. This is how payment gateways make money. Razorpay charges 2%, Stripe charges 2.9% + $0.30.

---

<a name="the-payment-ecosystem"></a>
## The Payment Ecosystem — 6 Players

Every time you use a card, **6 parties** are involved:

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                        │
│   ┌────────┐     ┌────────────┐     ┌─────────────────┐              │
│   │Customer│────>│  Merchant  │────>│ Payment Gateway │              │
│   │(You)   │     │ (Amazon)   │     │ (PayFlow — us!) │              │
│   └────────┘     └────────────┘     └────────┬────────┘              │
│                                               │                        │
│                                               ▼                        │
│                                      ┌────────────────┐               │
│                                      │   Acquirer     │               │
│                                      │ (Merchant's    │               │
│                                      │    Bank)       │               │
│                                      └───────┬────────┘               │
│                                              │                         │
│                                              ▼                         │
│                                      ┌────────────────┐               │
│                                      │  Card Network  │               │
│                                      │ (Visa/Master/  │               │
│                                      │  RuPay)        │               │
│                                      └───────┬────────┘               │
│                                              │                         │
│                                              ▼                         │
│                                      ┌────────────────┐               │
│                                      │    Issuer      │               │
│                                      │ (Customer's    │               │
│                                      │    Bank)       │               │
│                                      └────────────────┘               │
│                                                                        │
└──────────────────────────────────────────────────────────────────────┘
```

| Player | Who They Are | Real-World Analogy | Example |
|--------|-------------|-------------------|---------|
| **Customer** | Person buying something | You at a shop | You buying a shirt |
| **Merchant** | Business selling something | The shopkeeper | Amazon, Swiggy, Flipkart |
| **Payment Gateway** | Software connecting merchant to banks | The POS/card machine at the shop | **PayFlow (us!)**, Razorpay, Stripe |
| **Acquirer** | Merchant's bank | The bank that gave the shop its POS machine | HDFC (for Amazon), Axis |
| **Card Network** | Communication highway between banks | The postal service delivering messages between banks | Visa, Mastercard, RuPay |
| **Issuer** | Customer's bank (who gave you the card) | Your personal bank | SBI, ICICI, HDFC (your card-issuing bank) |

**Where PayFlow sits:** We are #3 — the Payment Gateway. We sit between the merchant and the banking system. We handle the complexity so merchants don't have to talk to banks directly.

---

<a name="what-each-service-does"></a>
## What Each Service Does — In Plain English

PayFlow is built as **11 microservices**. Each service does ONE thing well:

| # | Service | Port | Real-World Analogy | What It Actually Does |
|---|---------|------|-------------------|----------------------|
| 1 | **service-registry** | 8761 | 📞 Phone book | Every service registers its address here. When payment-service needs to call routing-service, it looks up the address in this phone book. |
| 2 | **config-server** | 8888 | 📋 Company policy handbook | Stores settings (database passwords, URLs, feature flags) in one place. All services read their config from here at startup. |
| 3 | **api-gateway** | 8080 | 🚪 Security guard at building entrance | ALL requests from the outside world come through here first. It checks your ID (JWT), limits how fast you can make requests (rate limiting), and directs you to the right department (routing). |
| 4 | **identity-service** | 8081 | 🏢 HR department | Manages who can enter the building. Register new users, login (get an ID badge = JWT), refresh expired badges. |
| 5 | **merchant-service** | 8082 | 📝 Sales onboarding team | Registers new merchants (like a shop applying for a POS machine). Generates API keys, configures webhooks, sets fee rates. |
| 6 | **payment-service** | 8083 | 💰 The cashier | THE core service. Handles the actual payment: create order → authorize (ask bank) → capture (charge) → refund (return money). |
| 7 | **routing-service** | 8084 | 🚦 Traffic controller | Decides WHICH bank to send the payment to. Checks for fraud first. Picks the fastest/cheapest route. Speaks ISO 8583 (bank language) over TCP. |
| 8 | **settlement-service** | 8085 | 📊 Accounts department | End of day: counts all successful payments, subtracts fees, calculates how much each merchant gets, triggers bank transfers. |
| 9 | **webhook-service** | 8086 | 📬 Postal service | Delivers status updates to merchants. "Hey Amazon, that ₹500 payment was captured!" Retries if merchant's server is down. |
| 10 | **notification-service** | 8087 | 🔔 Notification bell | Sends emails/SMS to customers: "Your payment of ₹500 to Amazon was successful." |
| 11 | **bank-simulator** | 9000 | 🎯 Practice dummy | A fake bank for testing. We can't connect to real Visa/HDFC during development, so this simulates bank responses (approve 85%, decline 15%). |

---

<a name="technical-challenges"></a>
## Technical Challenges We'll Solve

| # | Challenge | Why It's Hard | Our Solution |
|---|-----------|---------------|--------------|
| 1 | **Zero data loss** | A payment can't just "disappear" — it's real money. If our server crashes mid-payment, we can't lose track. | Idempotency keys + database transactions + Kafka persistence + retry logic |
| 2 | **Sub-second latency** | Customer waiting >3 seconds = they abandon the page (35% drop-off). Bank response must be fast. | Netty TCP (non-blocking I/O) + Redis cache + async event processing |
| 3 | **Duplicate payments** | Network timeout → customer clicks "Pay" again → they get charged twice! | Idempotency key in Redis (same key = same result, never processes twice) |
| 4 | **Bank failures** | Bank is down or responding slowly — what do we tell the customer? | Circuit breaker (Resilience4j) — after 5 failures, stop calling that bank, try another |
| 5 | **Merchant notifications** | Merchant's webhook server is down when we try to deliver "payment successful" | Exponential backoff retry (5min → 30min → 2hr → 24hr) + Dead Letter Queue |
| 6 | **Fraud prevention** | Stolen cards, velocity attacks (100 charges in 1 minute) | Rule engine (velocity, amount, geo checks) + ML decision tree scoring in <10ms |
| 7 | **Scale** | Black Friday: 10x normal traffic for 8 hours | Stateless services (any instance can handle any request) + Kafka partitioning + horizontal scaling |
| 8 | **Consistency** | Payment approved at bank, but our database write fails — now bank says "charged" but we say "failed" | Saga pattern + compensation logic + idempotent retry |
| 9 | **Multi-tenant isolation** | 1000 merchants sharing same system — one merchant's bug shouldn't affect others | Per-merchant rate limiting + API key isolation + separate database per domain |
| 10 | **Card security** | PCI-DSS says: NEVER store full card numbers. But we need them to process payment. | Tokenization: card number only exists in memory during authorization, never persisted |

---

<a name="tech-stack"></a>
## Tech Stack

| Category | Technology | Version | Why We Chose It |
|----------|-----------|---------|-----------------|
| Language | Java | 17 (LTS) | Industry standard for fintech, strong type system, mature ecosystem |
| Framework | Spring Boot | 3.2.5 | Most popular Java framework, auto-configuration, huge community |
| Cloud Framework | Spring Cloud | 2023.0.x | Gateway, Eureka, Config, Feign — microservices patterns built-in |
| Build Tool | Maven | 3.9.x | Multi-module support, dependency management, IDE integration |
| Frontend | React | 18.x | Component-based, large ecosystem, TanStack Query for data fetching |
| Frontend Language | TypeScript | 5.x | Catch errors at compile time, better IDE support |
| Frontend Build | Vite | 5.x | 10x faster than Webpack, instant HMR (Hot Module Replacement) |
| CSS | Tailwind CSS | 3.x | Utility-first, no switching between CSS files, responsive by default |
| SQL Database | PostgreSQL | 15 | ACID transactions (critical for payments), mature, free |
| Cache | Redis | 7 | In-memory speed for rate limiting, idempotency, session cache |
| NoSQL | DynamoDB | Managed | Event logs, webhook records — high-write, eventually consistent |
| Event Streaming | Apache Kafka | 3.7.x | Ordered events, replay capability, high throughput |
| Protocol | ISO 8583 | Binary | Industry standard for bank communication (since 1987) |
| TCP Framework | Netty | 4.1.x | Non-blocking I/O, perfect for binary TCP protocols |
| Auth | JWT (jjwt) | 0.12.x | Stateless authentication, works across microservices |
| Resilience | Resilience4j | 2.x | Circuit breaker, retry, rate limiter — fault tolerance |
| Batch Processing | Spring Batch | 5.x | Built for settlement: read → process → write millions of records |
| DB Migration | Flyway | 9.x | Version-controlled SQL schemas — no manual DDL |
| Object Mapping | MapStruct | 1.5.x | Compile-time DTO↔Entity mapping (no reflection = fast) |
| Boilerplate | Lombok | 1.18.x | @Data, @Builder — eliminate getters/setters/constructors |
| Testing | JUnit 5 + Mockito | Latest | Unit tests + mocking dependencies |
| Containers | Docker | Latest | Consistent environments, one-command setup |
| CI/CD | GitHub Actions | Latest | Free CI/CD, integrates with GitHub |
| Cloud | AWS Free Tier | — | EC2, RDS, S3, CloudFront, DynamoDB, SNS, SQS |
| Monitoring | CloudWatch + Actuator | — | Metrics, logs, alarms |

---

<a name="architecture-overview"></a>
## Architecture Overview — The 6 Layers

PayFlow is organized in **6 layers**, each with a clear responsibility:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ LAYER 1: PRESENTATION                                                        │
│ What users see and interact with                                             │
│                                                                               │
│   Merchant Portal (React)          Hosted Checkout (React)                   │
│   - Dashboard, analytics            - Card/UPI/NetBanking forms              │
│   - API keys, webhooks              - Payment status page                    │
└────────────────────────────────────────┬────────────────────────────────────┘
                                         │ HTTPS
┌────────────────────────────────────────▼────────────────────────────────────┐
│ LAYER 2: GATEWAY                                                             │
│ Single entry point — security, routing, rate limiting                        │
│                                                                               │
│   API Gateway (Spring Cloud Gateway)                                         │
│   - JWT validation       - Rate limiting (Redis)                             │
│   - Request logging      - Route to correct service                          │
│   - CORS handling        - Correlation ID (X-Request-Id)                     │
└────────────────────────────────────────┬────────────────────────────────────┘
                                         │ HTTP (internal)
┌────────────────────────────────────────▼────────────────────────────────────┐
│ LAYER 3: BUSINESS SERVICES                                                   │
│ Each service owns ONE business capability                                    │
│                                                                               │
│   Identity │ Merchant │ Payment │ Routing │ Settlement │ Webhook │ Notify   │
└────────────┬───────────────────────────────────────────────────┬────────────┘
             │ SQL queries                                        │ TCP + Events
┌────────────▼────────────────────────────────────────────────────▼────────────┐
│ LAYER 4: DATA                                                                 │
│ Persistent storage for business data                                          │
│                                                                               │
│   PostgreSQL (4 DBs)    Redis (cache)    DynamoDB (events)    Bank (TCP)     │
└─────────────────────────────────────────────────────────────────────────────┘
                                         │ Events (async)
┌────────────────────────────────────────▼────────────────────────────────────┐
│ LAYER 5: MESSAGING                                                           │
│ Asynchronous communication between services                                  │
│                                                                               │
│   Apache Kafka                                                                │
│   Topics: payment.authorized, payment.captured, settlement.completed, etc.   │
└─────────────────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────────────────┐
│ LAYER 6: INFRASTRUCTURE                                                      │
│ Services that help other services work                                       │
│                                                                               │
│   Eureka (discovery)    Config Server (configuration)                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Why these 6 layers?**
- Each layer can change independently
- Layer 1 (frontend) can be rebuilt in Vue.js without touching backend
- Layer 4 (data) can switch from PostgreSQL to MySQL without changing Layer 3
- Layer 5 (messaging) can switch from Kafka to SQS without changing business logic

---

<a name="production-grade"></a>
## What Makes This Production-Grade?

| Feature | Tutorial/Toy App | Our PayFlow |
|---------|-----------------|-------------|
| Error handling | `try-catch` → return 500 | Circuit breaker, retry with backoff, Dead Letter Queue, compensation |
| Security | Basic auth or hardcoded passwords | JWT + API keys + rate limiting + HMAC webhooks + BCrypt |
| Data integrity | Hope nothing crashes | Idempotency keys, database transactions, Kafka persistence |
| Observability | `System.out.println` | Structured JSON logging, metrics (Micrometer), correlation IDs, health checks |
| Deployment | "Works on my machine" | Docker + CI/CD + AWS + zero-downtime deploys |
| Scalability | Single instance | Stateless services, Kafka partitioning, Redis caching, horizontal scaling |
| Testing | Manual Postman clicks | JUnit 5, Mockito (80+ test methods), Testcontainers for integration |
| Bank communication | REST API mock | ISO 8583 binary protocol over TCP (what real banks use) |
| Fraud detection | None | Rule engine + decision tree ML scoring in <10ms |
| Configuration | Hardcoded in each service | Centralized config server, environment-specific profiles |

---

<a name="phase-roadmap"></a>
## Phase Roadmap

| Phase | What We Do | Outcome | Documents |
|-------|-----------|---------|-----------|
| **0** | Understand the project + set up environment | Tools installed, concepts understood | 2 docs |
| **1** | System design (requirements, APIs, databases) | Complete design document | 1 doc (2500+ lines) |
| **2** | High-level design (architecture, flows) | Sequence diagrams, component design | 1 doc (2500+ lines) |
| **3** | Low-level design (class-level design) | All interfaces, entities, configs designed | 1 doc (3000+ lines) |
| **4** | **Code everything** (the main event!) | 384 source code files, working system | 25 docs |
| **5** | Write tests | 80+ test methods, >80% coverage | 1 doc |
| **6** | Dockerize everything | All services in containers | 3 docs |
| **7** | CI/CD pipeline | Auto-build + auto-deploy on push | 3 docs |
| **8** | Deploy to AWS | Live system on the internet | 6 docs |
| **9** | Monitoring & observability | Dashboards, alerts, metrics | 1 doc |

---

<a name="folder-structure"></a>
## Project Folder Structure

```
payflow-payment-gateway/
│
├── backend/                          ← ALL Java microservices (268 files)
│   ├── pom.xml                       ← Parent POM (controls everything)
│   ├── common-lib/                   ← Shared code (DTOs, exceptions, utils)
│   ├── service-registry/             ← Eureka Server
│   ├── config-server/                ← Configuration management
│   ├── api-gateway/                  ← Single entry point
│   ├── identity-service/             ← Authentication
│   ├── merchant-service/             ← Merchant management
│   ├── payment-service/              ← Core payments
│   ├── routing-service/              ← Bank communication + fraud
│   ├── settlement-service/           ← Daily settlements
│   ├── webhook-service/              ← Event delivery
│   ├── notification-service/         ← Email/SMS
│   └── bank-simulator/              ← Fake bank for testing
│
├── frontend/                         ← React/TypeScript apps (84 files)
│   ├── merchant-portal/              ← Dashboard for merchants
│   └── hosted-checkout/              ← Payment page for customers
│
├── infra/                            ← Infrastructure (25 files)
│   ├── docker/                       ← Docker Compose files
│   ├── scripts/                      ← Start/stop/health-check scripts
│   ├── postman/                      ← API collections for testing
│   └── aws/                          ← Deployment guides
│
├── docs/                             ← Documentation (50 files)
├── .github/workflows/                ← CI/CD pipelines
├── README.md
├── CONTRIBUTING.md
├── PROJECT_PROMPT.md
├── PROJECT_STRUCTURE.md
└── .gitignore
```

**Total: ~436 files** — this is a real enterprise-scale project.

---

<a name="next-steps"></a>
## Next Steps

**What's coming in Part 2 (Environment Setup):**
- Install Java 17, Maven, Docker, Node.js, Git (baby steps with screenshots-style instructions)
- Set up IntelliJ IDEA with all required plugins
- Install and verify every tool with a PowerShell script
- Common installation errors and how to fix them

**Before moving on:**
- [ ] You understand what a payment gateway does
- [ ] You know the 6 parties in a card payment
- [ ] You can explain what each of the 11 services does
- [ ] You understand authorize vs capture vs settlement

---

## Document Index

| Phase | Part | Document | Status |
|-------|------|----------|--------|
| 0 | **1** | **[Project Overview](./phase0-part1-project-overview.md)** | ← You are here |
| 0 | 2 | [Environment Setup](./phase0-part2-environment-setup.md) | ⬜ Next |
| 1 | - | [System Design](./phase1-system-design.md) | ⬜ |
| 2 | - | [High-Level Design](./phase2-high-level-design.md) | ⬜ |
| 3 | - | [Low-Level Design](./phase3-low-level-design.md) | ⬜ |
| - | - | [System Design Guide](./system-design-complete-guide.md) | ⬜ |
| - | - | [Interview Cheatsheet](./system-design-interview-cheatsheet.md) | ⬜ |
| 4 | 1 | [Parent POM & Maven](./phase4-part01-parent-pom-and-maven-setup.md) | ⬜ |
| ... | ... | ... | ... |

---
*End of Phase 0 Part 1 — Project Overview*
*Next: [Phase 0 Part 2 — Environment Setup](./phase0-part2-environment-setup.md)*
