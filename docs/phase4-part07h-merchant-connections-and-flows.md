# 🏗️ Phase 4 Part 7h: Merchant Service — How Everything Connects

> **"Individual files are bricks. This document shows you the building."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7h — Connections, Flows & Big Picture |
| **Previous** | [Part 7g — Controller + Docker](./phase4-part07g-merchant-controller-docker.md) |
| **Next** | [Phase 4 Part 8a — Payment Service Entities](./phase4-part08a-payment-entities.md) |

---

## 📖 Table of Contents

1. [How the 22 Files Connect to Each Other](#1-how-the-22-files-connect-to-each-other)
2. [The Dependency Chain — Who Needs Whom](#2-the-dependency-chain--who-needs-whom)
3. [Request Journey — Step by Step Through Every File](#3-request-journey--step-by-step-through-every-file)
4. [How Merchant Service Connects to Infrastructure](#4-how-merchant-service-connects-to-infrastructure)
5. [How Merchant Service Connects to Other Microservices](#5-how-merchant-service-connects-to-other-microservices)
6. [The Full System — All Services Working Together](#6-the-full-system--all-services-working-together)
7. [Startup Sequence — What Happens When You Run the App](#7-startup-sequence--what-happens-when-you-run-the-app)
8. [Docker & Docker Compose — How It All Deploys](#8-docker--docker-compose--how-it-all-deploys)
9. [Data Flow Diagram — From Browser to Database and Back](#9-data-flow-diagram--from-browser-to-database-and-back)
10. [Concepts Map — Every Concept Connected to Every File](#10-concepts-map--every-concept-connected-to-every-file)
11. [What Would Break If You Removed Each File](#11-what-would-break-if-you-removed-each-file)
12. [Summary — The Complete Mental Model](#12-summary--the-complete-mental-model)

---

## 1. How the 22 Files Connect to Each Other

Every file has a purpose and connects to at least one other file. Here's the complete wiring diagram:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    MERCHANT SERVICE — INTERNAL WIRING                            │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  pom.xml ──────────────────► ALL FILES                                         │
│  (provides libraries)        (every file uses dependencies from pom.xml)       │
│                                                                                 │
│  application.yml ──────────► MerchantServiceApplication                        │
│  (config values)             (Spring reads this on startup)                    │
│                              ├── SecurityConfig (reads nothing directly,       │
│                              │    but Spring Security auto-configures from it) │
│                              ├── DataSource (DB connection from yml)           │
│                              └── Flyway (migration config from yml)            │
│                                                                                 │
│  MerchantServiceApplication ─► Component Scan finds:                           │
│  (@SpringBootApplication)      ├── SecurityConfig (@Configuration)             │
│                                ├── MerchantController (@RestController)        │
│                                ├── MerchantService (@Service)                  │
│                                ├── ApiKeyService (@Service)                    │
│                                ├── WebhookConfigService (@Service)             │
│                                ├── MerchantRepository (@Repository)            │
│                                ├── ApiKeyRepository (@Repository)              │
│                                ├── WebhookConfigRepository (@Repository)       │
│                                └── MerchantMapper (@Mapper → Spring bean)      │
│                                                                                 │
│  Entities define shapes:       Migrations create tables:                       │
│  Merchant.java ◄──────────────► V1__create_merchants_table.sql                 │
│  ApiKey.java ◄────────────────► V2__create_api_keys_table.sql                  │
│  WebhookConfig.java ◄────────► V3__create_webhook_configs_table.sql            │
│  FeeConfig.java ◄─────────────► V4__create_fee_configs_table.sql               │
│  (Java shape)                  (SQL shape — MUST match!)                       │
│                                                                                 │
│  Repositories use Entities:                                                    │
│  MerchantRepository ──────────► Merchant.java (JpaRepository<Merchant, UUID>)  │
│  ApiKeyRepository ────────────► ApiKey.java                                    │
│  WebhookConfigRepository ─────► WebhookConfig.java                             │
│                                                                                 │
│  DTOs define contracts:        Mapper converts between:                        │
│  MerchantRegisterRequest ◄────► MerchantMapper ◄────► Merchant (entity)       │
│  MerchantResponse ◄───────────► MerchantMapper ◄────► Merchant (entity)       │
│  ApiKeyResponse (manual mapping in ApiKeyService — no mapper)                  │
│  WebhookConfigRequest (used directly in WebhookConfigService)                  │
│                                                                                 │
│  Services use Repositories + DTOs + Mapper:                                    │
│  MerchantService ─────► MerchantRepository + MerchantMapper                    │
│  ApiKeyService ───────► ApiKeyRepository + MerchantRepository                  │
│  WebhookConfigService ► WebhookConfigRepository + MerchantRepository           │
│                                                                                 │
│  Controller uses Services:                                                     │
│  MerchantController ──► MerchantService                                        │
│                    ──► ApiKeyService                                            │
│                    ──► WebhookConfigService                                    │
│                                                                                 │
│  Tests mock Services' dependencies:                                            │
│  MerchantServiceTest ──► Mocks: MerchantRepository, MerchantMapper             │
│  ApiKeyServiceTest ────► Mocks: ApiKeyRepository, MerchantRepository           │
│                                                                                 │
│  Dockerfile packages everything:                                               │
│  Dockerfile ──► pom.xml (mvn package) ──► app.jar ──► Docker image             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. The Dependency Chain — Who Needs Whom

If you remove or break any file, here's what stops working:

```
pom.xml
  └── EVERYTHING (no libraries = nothing compiles)

application.yml
  └── App can't start (no port, no DB connection, no Eureka URL)

MerchantServiceApplication.java
  └── No entry point (nothing starts)

SecurityConfig.java
  └── All requests return 401 (Spring Security default blocks everything)

Merchant.java
  └── MerchantRepository breaks (no entity to query)
       └── MerchantService breaks (no repository)
            └── MerchantController breaks (no service)

V1__create_merchants_table.sql
  └── Hibernate validation fails ("Table 'merchants' doesn't exist")
       └── App crashes on startup

MerchantRepository.java
  └── MerchantService breaks (can't access data)

MerchantRegisterRequest.java
  └── Controller can't deserialize POST body
       └── 400 Bad Request on registration

MerchantMapper.java
  └── MerchantService breaks (can't convert DTO ↔ Entity)

MerchantService.java
  └── Controller's registerMerchant/getMerchant/etc. have nothing to call

MerchantController.java
  └── No HTTP endpoints exist (service runs but doesn't accept requests)

Dockerfile
  └── Can't create Docker image (service runs locally but can't be containerized)
```

---

## 3. Request Journey — Step by Step Through Every File

### Journey: `POST /v1/merchants` (Register a New Merchant)

Here's every file that's touched, in order:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  STEP 1: HTTP arrives at Tomcat (embedded via spring-boot-starter-web)         │
│  ─────────────────────────────────────────────────────────────────────          │
│  File involved: pom.xml (provided the Tomcat dependency)                       │
│  File involved: application.yml (configured port 8082)                         │
│                                                                                 │
│  STEP 2: Spring Security filter chain runs                                     │
│  ─────────────────────────────────────────                                     │
│  File involved: SecurityConfig.java                                            │
│  What happens:                                                                 │
│    Path /v1/merchants/** → permitAll() → request passes through ✓             │
│    CSRF check → disabled → passes ✓                                           │
│    Session → STATELESS → no session created ✓                                 │
│                                                                                 │
│  STEP 3: Spring MVC dispatches to controller                                  │
│  ────────────────────────────────────────────                                  │
│  File involved: MerchantController.java                                        │
│  What happens:                                                                 │
│    POST /v1/merchants → matches @PostMapping on registerMerchant()            │
│                                                                                 │
│  STEP 4: Jackson deserializes JSON body                                       │
│  ────────────────────────────────────────                                      │
│  File involved: MerchantRegisterRequest.java                                   │
│  What happens:                                                                 │
│    {"name":"Shop","email":"x@y.com","businessType":"RETAIL","mdrRate":2.0}    │
│    → Creates MerchantRegisterRequest object with these values                 │
│    Uses @NoArgsConstructor (empty constructor) + setters from @Data            │
│                                                                                 │
│  STEP 5: @Valid triggers validation                                            │
│  ──────────────────────────────────                                            │
│  File involved: MerchantRegisterRequest.java (validation annotations)          │
│  What happens:                                                                 │
│    @NotBlank on name → "Shop" → passes ✓                                     │
│    @NotBlank + @Email on email → "x@y.com" → passes ✓                        │
│    @NotBlank on businessType → "RETAIL" → passes ✓                            │
│    @Positive on mdrRate → 2.0 > 0 → passes ✓                                │
│    If ANY fails → MethodArgumentNotValidException → 400 response              │
│    Controller body NEVER runs for invalid input                               │
│                                                                                 │
│  STEP 6: Controller calls service                                              │
│  ────────────────────────────────                                              │
│  File involved: MerchantController.java                                        │
│  Code: merchantService.registerMerchant(request)                               │
│  Just one line — controller is thin                                            │
│                                                                                 │
│  STEP 7: Service checks duplicate email                                        │
│  ──────────────────────────────────────                                        │
│  File involved: MerchantService.java                                           │
│  File involved: MerchantRepository.java                                        │
│  Code: merchantRepository.existsByEmail("x@y.com")                             │
│  SQL generated: SELECT COUNT(*) > 0 FROM merchants WHERE email = 'x@y.com'   │
│  File involved: V1__create_merchants_table.sql (created the table + index)     │
│  File involved: Merchant.java (defines the entity Hibernate queries)           │
│  Result: false → email not taken → continue                                   │
│                                                                                 │
│  STEP 8: Mapper converts DTO → Entity                                         │
│  ────────────────────────────────────                                          │
│  File involved: MerchantMapper.java                                            │
│  Code: merchantMapper.toEntity(request)                                        │
│  What happens:                                                                 │
│    MapStruct (generated at compile time) creates:                              │
│    Merchant { id=null, name="Shop", email="x@y.com",                          │
│               businessType="RETAIL", mdrRate=2.0, active=true }               │
│  File involved: Merchant.java (defines the entity being created)               │
│  File involved: MerchantRegisterRequest.java (source of the data)              │
│                                                                                 │
│  STEP 9: Repository saves to database                                          │
│  ────────────────────────────────────                                          │
│  File involved: MerchantRepository.java                                        │
│  Code: merchantRepository.save(merchant)                                       │
│  File involved: Merchant.java (Hibernate reads @Entity, @Table, @Column)       │
│  File involved: V1__create_merchants_table.sql (table must exist)              │
│  SQL generated:                                                                │
│    INSERT INTO merchants (id, name, email, business_type, mdr_rate, active,   │
│                           created_at, updated_at)                              │
│    VALUES (gen_random_uuid(), 'Shop', 'x@y.com', 'RETAIL', 2.0, true,       │
│            NOW(), NOW())                                                       │
│  After save: merchant.id is now populated (Hibernate fills it)                │
│  @CreationTimestamp fills createdAt, @UpdateTimestamp fills updatedAt          │
│                                                                                 │
│  STEP 10: Mapper converts Entity → Response DTO                               │
│  ───────────────────────────────────────────────                               │
│  File involved: MerchantMapper.java                                            │
│  Code: merchantMapper.toResponse(saved)                                        │
│  File involved: MerchantResponse.java (target DTO)                             │
│  Creates: MerchantResponse { id=uuid, name="Shop", email="x@y.com",          │
│           businessType="RETAIL", mdrRate=2.0, active=true,                    │
│           createdAt=..., updatedAt=... }                                      │
│                                                                                 │
│  STEP 11: Controller wraps response                                            │
│  ──────────────────────────────────                                            │
│  File involved: MerchantController.java                                        │
│  Code: ResponseEntity.status(201).body(ApiResponse.success(response))          │
│  File involved: ApiResponse.java (from common-lib via pom.xml)                │
│                                                                                 │
│  STEP 12: Jackson serializes to JSON                                           │
│  ───────────────────────────────────                                           │
│  File involved: MerchantResponse.java (Jackson reads @Data getters)            │
│  Output: {"success":true,"data":{"id":"uuid","name":"Shop",...}}              │
│                                                                                 │
│  STEP 13: HTTP response sent back                                              │
│  ────────────────────────────────                                              │
│  Status: 201 Created                                                           │
│  Content-Type: application/json                                                │
│  Body: the JSON above                                                          │
│                                                                                 │
│  FILES TOUCHED IN THIS ONE REQUEST:                                            │
│  pom.xml, application.yml, SecurityConfig, MerchantController,                │
│  MerchantRegisterRequest, MerchantService, MerchantRepository,                │
│  MerchantMapper, Merchant (entity), MerchantResponse,                         │
│  V1 migration (table must exist), ApiResponse (common-lib)                    │
│  = 12 files for ONE request                                                   │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. How Merchant Service Connects to Infrastructure

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    MERCHANT SERVICE ↔ INFRASTRUCTURE                             │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  ┌──────────────────────────────────────────────────┐                          │
│  │           MERCHANT SERVICE (Port 8082)            │                          │
│  └─────┬──────────┬──────────┬──────────┬───────────┘                          │
│        │          │          │          │                                        │
│        ▼          ▼          ▼          ▼                                        │
│                                                                                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐                     │
│  │PostgreSQL│ │  Eureka  │ │  Config  │ │ API Gateway  │                     │
│  │  :5432   │ │  :8761   │ │  Server  │ │    :8080     │                     │
│  └──────────┘ └──────────┘ │  :8888   │ └──────────────┘                     │
│       │            │       └──────────┘        │                               │
│       │            │            │               │                               │
│       ▼            ▼            ▼               ▼                               │
│                                                                                 │
│  PostgreSQL (payflow_merchant database)                                        │
│  ──────────────────────────────────────                                        │
│  WHAT: Stores all merchant data permanently                                    │
│  HOW:  application.yml has jdbc:postgresql://localhost:5432/payflow_merchant   │
│  WHEN: Every save(), findById(), findByEmail() call                            │
│  FILES: application.yml (connection config)                                    │
│         V1-V4 migrations (create tables on startup)                            │
│         Repositories (generate SQL queries)                                    │
│         Entities (define table structure)                                       │
│                                                                                 │
│  Eureka — Service Registry (Port 8761)                                         │
│  ──────────────────────────────────────                                        │
│  WHAT: Merchant service registers itself so others can find it                 │
│  HOW:  application.yml has eureka.client.service-url.defaultZone              │
│         @EnableDiscoveryClient in MerchantServiceApplication.java             │
│  WHEN: On startup → POST "I'm merchant-service at 192.168.x.x:8082"          │
│         Every 30s → heartbeat "I'm still alive"                               │
│  WHY:  API Gateway uses lb://merchant-service to find us                      │
│                                                                                 │
│  Config Server (Port 8888)                                                     │
│  ──────────────────────────                                                    │
│  WHAT: Provides configuration at startup (DB passwords, feature flags)         │
│  HOW:  application.yml has spring.config.import: optional:configserver:...    │
│  WHEN: On startup → GET http://localhost:8888/merchant-service/default         │
│  WHAT IT RETURNS: DB URL, credentials, logging levels, etc.                   │
│  IF DOWN: "optional:" prefix → service uses local application.yml values      │
│                                                                                 │
│  API Gateway (Port 8080)                                                       │
│  ────────────────────────                                                      │
│  WHAT: The ONLY way external clients reach us                                  │
│  HOW:  Gateway has route: /v1/merchants/** → lb://merchant-service            │
│  FLOW: Client → Gateway:8080 → (Eureka lookup) → Merchant:8082               │
│  WHAT GATEWAY ADDS:                                                            │
│    X-Request-Id: uuid (for distributed tracing)                               │
│    X-User-Id: user-uuid (from JWT validation)                                 │
│    X-User-Role: MERCHANT (from JWT claims)                                    │
│  WHAT GATEWAY BLOCKS:                                                          │
│    Invalid/expired JWT → 401 at gateway (never reaches us)                    │
│    Rate limit exceeded → 429 at gateway (never reaches us)                    │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. How Merchant Service Connects to Other Microservices

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│             MERCHANT SERVICE ↔ OTHER MICROSERVICES                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │                    IDENTITY SERVICE (Port 8081)                │             │
│  │                                                               │             │
│  │  RELATIONSHIP: User registers as MERCHANT → then creates      │             │
│  │               merchant profile via Merchant Service           │             │
│  │                                                               │             │
│  │  FLOW:                                                        │             │
│  │  1. User registers: POST /v1/auth/register {role:"MERCHANT"} │             │
│  │     → Gets JWT access token                                  │             │
│  │  2. User creates merchant: POST /v1/merchants                │             │
│  │     → Authorization: Bearer <JWT from step 1>                │             │
│  │     → Gateway validates JWT, adds X-User-Id header           │             │
│  │     → Merchant Service receives the request                  │             │
│  │                                                               │             │
│  │  DEPENDENCY: Merchant Service does NOT call Identity Service  │             │
│  │  directly. They're decoupled via JWT tokens.                 │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │                   PAYMENT SERVICE (Port 8083)                  │             │
│  │                                                               │             │
│  │  RELATIONSHIP: Payment Service CALLS Merchant Service         │             │
│  │               to validate API keys                            │             │
│  │                                                               │             │
│  │  FLOW:                                                        │             │
│  │  1. Merchant's frontend sends: POST /v1/orders               │             │
│  │     with header: X-API-Key: pk_YWJjZGVm...                  │             │
│  │  2. Gateway forwards X-API-Key to Payment Service            │             │
│  │  3. Payment Service calls Merchant Service:                  │             │
│  │     POST /v1/merchants/api-keys/validate?key=pk_YWJjZGVm... │             │
│  │  4. Merchant Service: hash key → lookup → return merchantId  │             │
│  │  5. Payment Service now knows which merchant this order is for│             │
│  │                                                               │             │
│  │  THIS IS WHY ApiKeyService.validateApiKey() EXISTS            │             │
│  │  THIS IS WHY idx_api_keys_key_hash INDEX IS CRITICAL         │             │
│  │  (called on EVERY payment request)                           │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │                  WEBHOOK SERVICE (Port 8087)                   │             │
│  │                                                               │             │
│  │  RELATIONSHIP: Webhook Service READS Merchant Service's data  │             │
│  │               to know where to send notifications             │             │
│  │                                                               │             │
│  │  FLOW:                                                        │             │
│  │  1. Payment gets captured → Kafka event published            │             │
│  │  2. Webhook Service consumes event                           │             │
│  │  3. Webhook Service calls Merchant Service:                  │             │
│  │     GET /v1/merchants/{id}/webhooks (active ones only)       │             │
│  │  4. For each active webhook config:                          │             │
│  │     → Read the signing secret (whsec_...)                   │             │
│  │     → Compute HMAC-SHA256 signature on payload              │             │
│  │     → POST to merchant's webhook URL with signature header  │             │
│  │                                                               │             │
│  │  THIS IS WHY WebhookConfig stores the signing secret         │             │
│  │  THIS IS WHY findByMerchantIdAndActiveTrue() EXISTS          │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────┐             │
│  │                SETTLEMENT SERVICE (Port 8085)                  │             │
│  │                                                               │             │
│  │  RELATIONSHIP: Settlement Service READS FeeConfig             │             │
│  │               to calculate merchant payouts                   │             │
│  │                                                               │             │
│  │  FLOW:                                                        │             │
│  │  1. Daily settlement batch runs                              │             │
│  │  2. For each merchant's captured payments:                   │             │
│  │     → Read FeeConfig (mdrPercent, gstPercent)               │             │
│  │     → Calculate: MDR = amount × mdrPercent / 100            │             │
│  │     → Calculate: GST = MDR × gstPercent / 100               │             │
│  │     → Merchant receives: amount - MDR - GST                 │             │
│  │                                                               │             │
│  │  THIS IS WHY FeeConfig entity and V4 migration EXIST         │             │
│  │  (even though we haven't built FeeConfigRepository yet)      │             │
│  └───────────────────────────────────────────────────────────────┘             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. The Full System — All Services Working Together

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│   Merchant's Browser (localhost:3000)                                           │
│       │                                                                         │
│       │ POST /v1/merchants (register business)                                 │
│       │ Authorization: Bearer <JWT from Identity Service>                      │
│       │                                                                         │
│       ▼                                                                         │
│   ┌─────────────────────────────────────────────────────────┐                  │
│   │  API GATEWAY (Port 8080)                                 │                  │
│   │  1. RateLimitFilter → check Redis token bucket          │                  │
│   │  2. RequestLoggingFilter → assign X-Request-Id          │                  │
│   │  3. JwtValidationFilter → validate JWT → add headers    │                  │
│   │  4. Route: /v1/merchants/** → lb://merchant-service     │                  │
│   │  5. Eureka lookup → 192.168.x.x:8082                   │                  │
│   └─────────────────────┬───────────────────────────────────┘                  │
│                         │                                                       │
│                         ▼                                                       │
│   ┌─────────────────────────────────────────────────────────┐                  │
│   │  MERCHANT SERVICE (Port 8082)                            │                  │
│   │  SecurityConfig → permitAll ✓                           │                  │
│   │  MerchantController.registerMerchant()                  │                  │
│   │    → @Valid validates MerchantRegisterRequest            │                  │
│   │    → MerchantService.registerMerchant()                 │                  │
│   │      → MerchantRepository.existsByEmail() → SQL         │                  │
│   │      → MerchantMapper.toEntity()                        │                  │
│   │      → MerchantRepository.save() → SQL INSERT           │                  │
│   │      → MerchantMapper.toResponse()                      │                  │
│   │    → ResponseEntity.status(201)                         │                  │
│   └─────────────────────┬───────────────────────────────────┘                  │
│                         │                                                       │
│                         ▼                                                       │
│   ┌─────────────────────────────────────────────────────────┐                  │
│   │  POSTGRESQL (Port 5432 — payflow_merchant database)      │                  │
│   │  Tables created by Flyway V1-V4:                        │                  │
│   │  • merchants (stores the merchant profile)              │                  │
│   │  • api_keys (stores hashed API keys)                    │                  │
│   │  • webhook_configs (stores webhook URLs + secrets)      │                  │
│   │  • fee_configs (stores MDR + GST percentages)           │                  │
│   └─────────────────────────────────────────────────────────┘                  │
│                                                                                 │
│   ┌─────────────────────────────────────────────────────────┐                  │
│   │  EUREKA (Port 8761) — Behind the scenes                  │                  │
│   │  Merchant Service registered as:                        │                  │
│   │    Name: MERCHANT-SERVICE                               │                  │
│   │    Address: 192.168.x.x:8082                            │                  │
│   │    Status: UP                                           │                  │
│   │  Gateway reads this to know where to route              │                  │
│   └─────────────────────────────────────────────────────────┘                  │
│                                                                                 │
│   ┌─────────────────────────────────────────────────────────┐                  │
│   │  CONFIG SERVER (Port 8888) — At startup only             │                  │
│   │  Merchant Service fetches:                              │                  │
│   │    GET /merchant-service/default                        │                  │
│   │  Returns: DB URL, credentials, Eureka URL, etc.         │                  │
│   │  (optional — falls back to local yml if unavailable)    │                  │
│   └─────────────────────────────────────────────────────────┘                  │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Startup Sequence — What Happens When You Run the App

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  $ mvn spring-boot:run                                                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  SECOND 0-1: JVM starts, calls main()                                          │
│  FILE: MerchantServiceApplication.java                                         │
│  → SpringApplication.run() begins                                              │
│                                                                                 │
│  SECOND 1-2: Read configuration                                                │
│  FILE: application.yml                                                          │
│  → port=8082, DB=payflow_merchant, eureka=localhost:8761                       │
│  FILE: pom.xml (already resolved by Maven — libraries on classpath)            │
│                                                                                 │
│  SECOND 2-3: (Optional) Contact Config Server                                  │
│  FILE: application.yml (spring.config.import: optional:configserver:...)       │
│  → GET http://localhost:8888/merchant-service/default                          │
│  → If available: merge remote config. If not: skip.                           │
│                                                                                 │
│  SECOND 3-4: Component scanning                                                │
│  FILE: MerchantServiceApplication.java (@SpringBootApplication triggers scan)  │
│  → Finds 10 annotated classes:                                                │
│    SecurityConfig, MerchantController, MerchantService, ApiKeyService,        │
│    WebhookConfigService, MerchantRepository, ApiKeyRepository,                │
│    WebhookConfigRepository, MerchantMapper (generated), + auto-configs        │
│                                                                                 │
│  SECOND 4-5: Auto-configuration                                               │
│  FILE: pom.xml (dependencies trigger auto-config)                              │
│  → spring-data-jpa found → configure Hibernate + EntityManager                │
│  → postgresql found → configure DataSource (HikariCP pool)                    │
│  → spring-security found → apply SecurityConfig                               │
│  → flyway-core found → configure Flyway migration runner                      │
│  → springdoc found → configure Swagger UI endpoint                            │
│                                                                                 │
│  SECOND 5-6: Bean creation (dependency order)                                  │
│  FILES: SecurityConfig → DataSource → EntityManager → Repositories →          │
│         MerchantMapper → Services → Controller                                │
│  Spring creates ONE instance of each (singleton) and wires them together      │
│                                                                                 │
│  SECOND 6-7: Flyway runs migrations                                           │
│  FILES: V1, V2, V3, V4 SQL files (in order)                                  │
│  → Creates: merchants, api_keys, webhook_configs, fee_configs tables          │
│  → Records in flyway_schema_history: "V1 done, V2 done, V3 done, V4 done"   │
│                                                                                 │
│  SECOND 7-8: Hibernate validation                                              │
│  FILES: Merchant.java, ApiKey.java, WebhookConfig.java, FeeConfig.java        │
│  → Checks: entity fields match DB columns? ✓                                 │
│  → If mismatch → startup FAILS with clear error message                      │
│                                                                                 │
│  SECOND 8-9: Start Tomcat on port 8082                                         │
│  → MerchantController endpoints registered:                                    │
│    POST /v1/merchants, GET /v1/merchants/{id}, etc. (14 total)               │
│                                                                                 │
│  SECOND 9-10: Register with Eureka                                             │
│  FILE: application.yml (eureka config) + @EnableDiscoveryClient               │
│  → POST to http://localhost:8761/eureka/apps/MERCHANT-SERVICE                 │
│  → "I'm merchant-service at 192.168.x.x:8082, status: UP"                   │
│                                                                                 │
│  SECOND 10: READY                                                              │
│  → Console: "Started MerchantServiceApplication in 4.2 seconds"              │
│  → Swagger UI: http://localhost:8082/swagger-ui.html                          │
│  → Eureka dashboard shows: MERCHANT-SERVICE UP                                │
│  → Accepting HTTP requests                                                    │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Docker & Docker Compose — How It All Deploys

### Single Service (Dockerfile)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    DOCKERFILE → DOCKER IMAGE → DOCKER CONTAINER                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  Dockerfile                docker build              docker run                │
│  (instructions)    ──────►  (creates image)  ──────►  (runs container)         │
│                                                                                 │
│  Stage 1 (builder):                                                            │
│  FROM maven:3.9            800MB image with Maven + JDK                       │
│  COPY all modules          Copy source code into image                        │
│  RUN mvn package           Compile → produce app.jar                          │
│                                                                                 │
│  Stage 2 (runtime):                                                            │
│  FROM temurin:17-jre       180MB image with JRE only                          │
│  COPY app.jar              Copy ONLY the jar from stage 1                     │
│  USER payflow              Run as non-root (security)                         │
│  EXPOSE 8082               Document the port                                  │
│  HEALTHCHECK               Check /actuator/health every 15s                   │
│  ENTRYPOINT java -jar      Start the application                              │
│                                                                                 │
│  Result: ~200MB Docker image containing just JRE + app.jar                    │
│  (NOT 800MB with Maven + JDK + source code)                                   │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Full System (Docker Compose)

Your project has `infra/docker/docker-compose.yml` that starts ALL infrastructure:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    docker-compose up -d                                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  STARTED BY DOCKER COMPOSE:                 PORTS:                              │
│  ┌────────────────────────┐                                                    │
│  │  PostgreSQL             │                :5432                               │
│  │  (runs init-db.sql →    │                                                   │
│  │   creates payflow_identity,              Creates ALL databases              │
│  │   payflow_merchant,                      for ALL microservices              │
│  │   payflow_payment,                                                          │
│  │   payflow_settlement)   │                                                   │
│  ├────────────────────────┤                                                    │
│  │  Redis                  │                :6379                               │
│  │  (rate limiting cache)  │                                                   │
│  ├────────────────────────┤                                                    │
│  │  Zookeeper              │                :2181                               │
│  │  (Kafka coordination)   │                                                   │
│  ├────────────────────────┤                                                    │
│  │  Kafka                  │                :9092                               │
│  │  (async event messaging)│                                                   │
│  ├────────────────────────┤                                                    │
│  │  Kafka UI               │                :8090                               │
│  │  (web dashboard)        │                                                   │
│  ├────────────────────────┤                                                    │
│  │  LocalStack             │                :4566                               │
│  │  (AWS DynamoDB/SNS/SQS) │                                                   │
│  └────────────────────────┘                                                    │
│                                                                                 │
│  STARTED SEPARATELY (Spring Boot apps):                                        │
│  ┌────────────────────────┐                                                    │
│  │  Service Registry       │                :8761  ← start FIRST              │
│  │  Config Server          │                :8888  ← start SECOND             │
│  │  API Gateway            │                :8080  ← start THIRD              │
│  │  Identity Service       │                :8081                               │
│  │  Merchant Service       │                :8082  ← THIS SERVICE             │
│  │  Payment Service        │                :8083                               │
│  │  Settlement Service     │                :8085                               │
│  │  Webhook Service        │                :8087                               │
│  └────────────────────────┘                                                    │
│                                                                                 │
│  BOOT ORDER:                                                                   │
│  1. docker-compose up -d          (PostgreSQL, Redis, Kafka)                   │
│  2. Service Registry              (Eureka — others register here)              │
│  3. Config Server                 (others fetch config from here)              │
│  4. API Gateway                   (routes external requests)                   │
│  5. Identity + Merchant + Payment + etc. (business services)                   │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Data Flow Diagram — From Browser to Database and Back

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  BROWSER                                                                       │
│  (Merchant Portal)                                                             │
│       │                                                                         │
│       │  fetch('http://localhost:8080/v1/merchants', {                          │
│       │    method: 'POST',                                                     │
│       │    headers: { 'Authorization': 'Bearer eyJ...',                        │
│       │               'Content-Type': 'application/json' },                    │
│       │    body: JSON.stringify({name:"Shop", email:"x@y.com", ...})           │
│       │  })                                                                    │
│       │                                                                         │
│       ▼                        PROTOCOL: HTTP/JSON                              │
│  API GATEWAY (:8080)           ────────────────────                            │
│       │  Validates JWT                                                         │
│       │  Adds X-User-Id, X-Request-Id headers                                 │
│       │  Looks up "merchant-service" in Eureka                                 │
│       │                                                                         │
│       ▼                        PROTOCOL: HTTP/JSON (internal)                  │
│  MERCHANT SERVICE (:8082)      ────────────────────────────                    │
│       │  SecurityConfig: permitAll ✓                                          │
│       │  Controller: deserialize + validate + delegate                         │
│       │  Service: check email + map DTO→Entity + save                         │
│       │  Repository: generate SQL                                              │
│       │                                                                         │
│       ▼                        PROTOCOL: TCP/SQL (JDBC)                        │
│  POSTGRESQL (:5432)            ────────────────────────                         │
│       │  INSERT INTO merchants (id, name, email, ...) VALUES (...)            │
│       │  Returns: generated UUID + timestamps                                 │
│       │                                                                         │
│       ▼                        (back up the chain)                             │
│  MERCHANT SERVICE              Entity → Mapper → Response DTO → JSON          │
│       │                                                                         │
│       ▼                                                                         │
│  API GATEWAY                   Forwards response to browser                    │
│       │                                                                         │
│       ▼                                                                         │
│  BROWSER                       Shows: "Merchant created successfully!"        │
│                                 Stores merchantId for future API calls          │
│                                                                                 │
│  PROTOCOLS USED:                                                               │
│  Browser → Gateway:    HTTPS (port 8080)                                       │
│  Gateway → Merchant:   HTTP (internal, port 8082)                             │
│  Merchant → PostgreSQL: JDBC/TCP (port 5432)                                  │
│  Merchant → Eureka:    HTTP (port 8761, registration/heartbeat)               │
│  Merchant → Config:    HTTP (port 8888, config fetch at startup)              │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Concepts Map — Every Concept Connected to Every File

| Concept | Files That Use It | Why |
|---|---|---|
| **Spring IoC / DI** | All @Service, @Repository, @RestController | Spring creates and wires objects automatically |
| **JPA / Hibernate** | Entities + Repositories + application.yml (ddl-auto) | Java objects ↔ database rows |
| **Flyway** | V1-V4 SQL + application.yml (flyway.enabled) | Versioned database schema management |
| **Lombok** | All Java files (@Data, @Builder, @Slf4j, etc.) | Eliminates boilerplate code |
| **MapStruct** | MerchantMapper + pom.xml (mapstruct dependency) | Compile-time DTO ↔ Entity conversion |
| **Jakarta Validation** | DTOs (@NotBlank, @Email, etc.) + Controller (@Valid) | Reject bad input before business logic |
| **SHA-256 Hashing** | ApiKeyService (sha256 method) | Store API key hashes, not raw keys |
| **SecureRandom** | ApiKeyService + WebhookConfigService | Cryptographic randomness for keys/secrets |
| **REST API** | Controller + DTOs | HTTP endpoints that accept/return JSON |
| **ResponseEntity** | Controller | Control HTTP status codes (201 vs 200) |
| **ApiResponse wrapper** | Controller + common-lib | Consistent JSON format across all services |
| **@Transactional** | All Services | Database operation atomicity (commit/rollback) |
| **Eureka Client** | MerchantServiceApplication + application.yml | Service registration and discovery |
| **Config Client** | application.yml (config.import) | Centralized configuration |
| **Docker multi-stage** | Dockerfile | Build (800MB) → Runtime (180MB) |
| **Spring Security** | SecurityConfig | CSRF off, stateless, permitAll patterns |
| **Mockito** | Test files (@Mock, @InjectMocks, when/verify) | Unit testing with fake dependencies |

---

## 11. What Would Break If You Removed Each File

| If You Remove... | What Breaks | Error You'd See |
|---|---|---|
| `pom.xml` | Everything | "Project build error: Non-readable POM" |
| `application.yml` | Startup | "Failed to configure DataSource" (no DB URL) |
| `MerchantServiceApplication.java` | Startup | "No main class found" |
| `SecurityConfig.java` | All requests | "401 Unauthorized" on every request (Spring Security default) |
| `Merchant.java` | MerchantRepository | "Not a managed type: Merchant" |
| `V1 migration` | Startup | "Hibernate validation: Table 'merchants' doesn't exist" |
| `MerchantRepository.java` | MerchantService | "No qualifying bean of type MerchantRepository" |
| `MerchantRegisterRequest.java` | POST /merchants | "400 Bad Request" (Jackson can't deserialize) |
| `MerchantMapper.java` | MerchantService | "No qualifying bean of type MerchantMapper" |
| `MerchantService.java` | MerchantController | "No qualifying bean of type MerchantService" |
| `MerchantController.java` | HTTP endpoints | Service runs but no endpoints exist (no 404 even — no routes) |
| `MerchantResponse.java` | Responses | "No serializer found for MerchantResponse" (Jackson confusion) |
| `ApiKeyService.java` | Key endpoints | "No qualifying bean of type ApiKeyService" |
| `WebhookConfigService.java` | Webhook endpoints | "No qualifying bean of type WebhookConfigService" |
| `Dockerfile` | Docker builds | Can't containerize (service still works locally via `mvn spring-boot:run`) |

---

## 12. Summary — The Complete Mental Model

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  THE MERCHANT SERVICE IN ONE PICTURE:                                          │
│                                                                                 │
│  pom.xml provides LIBRARIES                                                    │
│  application.yml provides CONFIGURATION                                        │
│  MerchantServiceApplication.java is the POWER BUTTON                           │
│  SecurityConfig.java is the SECURITY GUARD                                     │
│                                                                                 │
│  Entities define WHAT DATA looks like in Java                                  │
│  Migrations define WHAT DATA looks like in PostgreSQL                          │
│  Repositories define HOW TO ACCESS the data                                    │
│                                                                                 │
│  DTOs define WHAT CLIENTS SEE (input and output)                               │
│  Mapper CONVERTS between client format and internal format                     │
│                                                                                 │
│  Services contain ALL BUSINESS LOGIC                                           │
│  Tests VERIFY the business logic works correctly                               │
│                                                                                 │
│  Controller RECEIVES HTTP and DELEGATES to services                            │
│  Dockerfile PACKAGES everything for deployment                                 │
│                                                                                 │
│  Eureka REGISTERS us so others can find us                                     │
│  Config Server PROVIDES configuration                                          │
│  API Gateway ROUTES external requests to us                                    │
│  PostgreSQL STORES our data permanently                                        │
│                                                                                 │
│  Identity Service authenticates USERS who become merchants                     │
│  Payment Service CALLS US to validate API keys                                 │
│  Webhook Service READS our webhook configs to deliver notifications            │
│  Settlement Service READS our fee configs to calculate payouts                 │
│                                                                                 │
│  Everything is connected. Nothing exists in isolation.                          │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part07-merchant-service-overview.md) | Merchant Service Overview |
| [Part 7a](./phase4-part07a-merchant-project-setup.md) | Project Setup |
| [Part 7b](./phase4-part07b-merchant-entities.md) | Entities |
| [Part 7c](./phase4-part07c-merchant-migrations.md) | Flyway Migrations |
| [Part 7d](./phase4-part07d-merchant-repositories.md) | Repositories |
| [Part 7e](./phase4-part07e-merchant-dtos-mapper.md) | DTOs + Mapper |
| [Part 7f](./phase4-part07f-merchant-services-tests.md) | Services + Tests |
| [Part 7g](./phase4-part07g-merchant-controller-docker.md) | Controller + Docker |
| **Part 7h** | **How Everything Connects** (You are here) |

---

*Merchant Service is COMPLETE. Next: [Phase 4 Part 8a — Payment Service Entities](./phase4-part08a-payment-entities.md) →*
