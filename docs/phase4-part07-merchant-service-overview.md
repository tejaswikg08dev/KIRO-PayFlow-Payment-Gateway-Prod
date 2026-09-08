# 🏗️ Phase 4 Part 07: Merchant Service — Overview & Roadmap

> **"A merchant is more than a name — it's a verified business identity with API keys, webhook endpoints, and fee configurations."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7 (split into 7a through 7g) |
| **Module** | `merchant-service` |
| **Package** | `com.payflow.merchant` |
| **Port** | 8082 |
| **Database** | PostgreSQL — `payflow_merchant` |
| **Previous** | [Phase 4 Part 6c: Identity Service — Controller & Tests](./phase4-part06c-identity-controller-tests.md) |
| **Next** | [Phase 4 Part 8: Payment Service Overview](./phase4-part08-payment-service-overview.md) |

---

## 🎯 What Is the Merchant Service?

The Merchant Service manages **businesses that accept payments** through PayFlow. While the Identity Service handles "who are you?" (authentication), the Merchant Service handles "what business do you run and how do we connect to it?"

### Real-World Analogy

Think of it like opening a business bank account:
- **Merchant Registration** = Open the account (provide business name, email, type)
- **API Key** = Your debit card (identifies your business in every API call)
- **Webhook** = Your notification preferences ("call me when a payment goes through")
- **Fee Config** = Your pricing plan (what percentage the bank charges per transaction)

---

## 🛠️ Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| **Java** | 17 | Language |
| **Spring Boot** | 3.2.5 | Application framework |
| **Spring Data JPA** | (via starter) | ORM / database access |
| **Spring Security** | (via starter) | SecurityFilterChain (stateless, JWT at Gateway) |
| **Spring Validation** | (via starter) | `@Valid`, `@NotBlank`, `@Email`, `@URL`, `@Positive` |
| **Spring Cloud Eureka Client** | 2023.0.1 | Register with Service Registry |
| **Spring Cloud Config Client** | 2023.0.1 | Pull config from Config Server |
| **PostgreSQL** | 16 | Relational database |
| **Flyway** | (via starter) | Versioned database migrations |
| **Lombok** | 1.18.32 | `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` |
| **MapStruct** | 1.5.5 | Compile-time entity ↔ DTO mapping (including `updateEntity()`) |
| **SpringDoc OpenAPI** | 2.5.0 | Swagger UI at `/swagger-ui.html` |
| **SecureRandom + SHA-256** | (JDK) | API key generation and hashing |
| **H2** | (test scope) | In-memory DB for unit tests |
| **JUnit 5 + Mockito** | (via starter-test) | Testing |

---

## 📐 What We Build — 14 Endpoints

| # | Method | Path | Description |
|---|---|---|---|
| 1 | POST | `/v1/merchants` | Register a new merchant |
| 2 | GET | `/v1/merchants/{id}` | Get merchant by ID |
| 3 | GET | `/v1/merchants` | List all merchants |
| 4 | PUT | `/v1/merchants/{id}` | Update merchant details |
| 5 | DELETE | `/v1/merchants/{id}` | Deactivate merchant (soft delete) |
| 6 | POST | `/v1/merchants/{id}/api-keys` | Generate new API key |
| 7 | GET | `/v1/merchants/{id}/api-keys` | List API keys (prefix only) |
| 8 | DELETE | `/v1/merchants/{id}/api-keys/{keyId}` | Revoke an API key |
| 9 | POST | `/v1/merchants/api-keys/validate` | Validate an API key |
| 10 | POST | `/v1/merchants/{id}/webhooks` | Create webhook config |
| 11 | GET | `/v1/merchants/{id}/webhooks` | List webhook configs |
| 12 | PUT | `/v1/merchants/{id}/webhooks/{configId}` | Update webhook config |
| 13 | DELETE | `/v1/merchants/{id}/webhooks/{configId}` | Deactivate webhook |

---

## 📄 Sub-Parts — Build Order (7a → 7g)

You build these **in order**. Each part depends on the one before it.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    BUILD ORDER (7a → 7g)                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  7a  Project Setup        pom.xml, application.yml, main class, security│
│   │                       "Set up the project so it can run"             │
│   ▼                                                                      │
│  7b  Entities             Merchant, ApiKey, WebhookConfig, FeeConfig    │
│   │                       "Define what data we store"                    │
│   ▼                                                                      │
│  7c  Flyway Migrations    V1-V4 SQL files                               │
│   │                       "Create the actual database tables"            │
│   ▼                                                                      │
│  7d  Repositories         MerchantRepo, ApiKeyRepo, WebhookConfigRepo  │
│   │                       "Define how we access the data"                │
│   ▼                                                                      │
│  7e  DTOs + Mapper        Request/Response DTOs, MerchantMapper         │
│   │                       "Define what goes in and out of the API"       │
│   ▼                                                                      │
│  7f  Services + Tests     MerchantService, ApiKeyService,               │
│   │                       WebhookConfigService + unit tests             │
│   │                       "Write the business logic"                     │
│   ▼                                                                      │
│  7g  Controller + Docker  MerchantController, curl testing, Dockerfile  │
│                           "Wire HTTP endpoints and deploy"               │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Why This Order?

| Step | What | Why It Goes Here |
|---|---|---|
| **7a** | Project Setup | You can't write any code until pom.xml has dependencies and application.yml has config |
| **7b** | Entities | You can't create tables or queries without knowing what data you're storing |
| **7c** | Migrations | Tables must exist in the DB before Hibernate validates entities against them |
| **7d** | Repositories | Data access interfaces need entities to exist first |
| **7e** | DTOs + Mapper | Input/output contracts are defined before the logic that uses them |
| **7f** | Services + Tests | Business logic uses repos + DTOs + mapper — all must exist first |
| **7g** | Controller + Docker | HTTP layer calls services — services must exist first. Docker is last (deployment) |

---

## 📄 What Each Part Covers

### [Part 7a — Project Setup](./phase4-part07a-merchant-project-setup.md)

| File | What You Type |
|---|---|
| `pom.xml` | Dependencies (what libraries we need and why) |
| `application.yml` | Configuration (port, database, Eureka, Swagger) |
| `MerchantServiceApplication.java` | Spring Boot entry point with `@EnableDiscoveryClient` |
| `SecurityConfig.java` | Stateless security (CSRF off, permitAll for /v1/merchants/**) |

**You'll understand:** Maven parent inheritance, why each dependency exists, how application.yml connects to PostgreSQL/Eureka/Config Server, why SecurityConfig exists even though we permitAll.

---

### [Part 7b — Entities (Data Models)](./phase4-part07b-merchant-entities.md)

| File | What You Type |
|---|---|
| `Merchant.java` | Main entity (name, email, businessType, mdrRate, active) |
| `ApiKey.java` | API key entity (keyHash, prefix, merchantId, active) |
| `WebhookConfig.java` | Webhook entity (url, secret, events TEXT[], merchantId) |
| `FeeConfig.java` | Fee entity (mdrPercent, gstPercent as BigDecimal) |

**You'll understand:** Every JPA annotation line-by-line (`@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column`, `@Builder.Default`), UUID vs String IDs, why Boolean wrapper vs boolean primitive, PostgreSQL TEXT[] arrays, BigDecimal for money.

---

### [Part 7c — Flyway Migrations (Database Tables)](./phase4-part07c-merchant-migrations.md)

| File | What You Type |
|---|---|
| `V1__create_merchants_table.sql` | Parent table with indexes |
| `V2__create_api_keys_table.sql` | API keys with FK + CASCADE |
| `V3__create_webhook_configs_table.sql` | Webhook configs with TEXT[] |
| `V4__create_fee_configs_table.sql` | Fee configs with DECIMAL(5,2) |

**You'll understand:** Every SQL keyword (PRIMARY KEY, DEFAULT, UNIQUE, FOREIGN KEY, ON DELETE CASCADE, TIMESTAMP WITH TIME ZONE), index strategy, why each migration exists separately, how Flyway ordering works.

---

### [Part 7d — Repositories (Data Access)](./phase4-part07d-merchant-repositories.md)

| File | What You Type |
|---|---|
| `MerchantRepository.java` | findByEmail(), existsByEmail() |
| `ApiKeyRepository.java` | findByKeyHash(), findByMerchantId(), findByPrefix(), findByMerchantIdAndActiveTrue() |
| `WebhookConfigRepository.java` | findByMerchantId(), findByMerchantIdAndActiveTrue() |

**You'll understand:** How Spring Data JPA generates SQL from method names, why no FeeConfigRepository exists yet, JpaRepository inheritance, `Optional<T>` vs `List<T>` return types.

---

### [Part 7e — DTOs + MerchantMapper (Input/Output)](./phase4-part07e-merchant-dtos-mapper.md)

| File | What You Type |
|---|---|
| `MerchantRegisterRequest.java` | Input DTO with @NotBlank, @Email, @Positive |
| `MerchantResponse.java` | Output DTO |
| `ApiKeyResponse.java` | Output DTO (rawKey only on creation!) |
| `WebhookConfigRequest.java` | Input DTO with @URL, @NotEmpty |
| `MerchantMapper.java` | MapStruct: toEntity(), toResponse(), updateEntity(@MappingTarget) |

**You'll understand:** Why DTOs exist (never expose entities), every validation annotation, @URL from Hibernate Validator, how MapStruct generates code at compile time, what @MappingTarget does, why updateEntity() is void.

---

### [Part 7f — Services + Tests (Business Logic)](./phase4-part07f-merchant-services-tests.md)

| File | What You Type |
|---|---|
| `MerchantService.java` | Register, get, getAll, update, deactivate |
| `ApiKeyService.java` | Generate (SecureRandom + SHA-256), validate, revoke, list |
| `WebhookConfigService.java` | Create (auto-generate whsec_ secret), get, update, deactivate |
| `MerchantServiceTest.java` | 5 unit tests |
| `ApiKeyServiceTest.java` | 6 unit tests |

**You'll understand:** SecureRandom vs Random, SHA-256 vs BCrypt (when to use which), HMAC webhook signing, @Transactional, @Transactional(readOnly=true), @Slf4j logging, smart duplicate email check on update, Mockito patterns (Given-When-Then).

---

### [Part 7g — Controller + curl + Dockerfile (HTTP + Deploy)](./phase4-part07g-merchant-controller-docker.md)

| File | What You Type |
|---|---|
| `MerchantController.java` | 14 endpoints in 3 sections |
| `Dockerfile` | Multi-stage build |
| curl commands | Test every endpoint |

**You'll understand:** @PathVariable UUID, @RequestParam, 3 resource groups in 1 controller (why not split), ResponseEntity status codes (201 vs 200), ApiResponse wrapper, multi-stage Docker build (builder vs runtime), HEALTHCHECK, non-root user in Docker.

---

## 🗂️ Complete File List

```
backend/merchant-service/
├── pom.xml                                              ← 7a
├── Dockerfile                                           ← 7g
└── src/
    ├── main/
    │   ├── java/com/payflow/merchant/
    │   │   ├── MerchantServiceApplication.java          ← 7a
    │   │   ├── config/
    │   │   │   └── SecurityConfig.java                  ← 7a
    │   │   ├── model/
    │   │   │   ├── Merchant.java                        ← 7b
    │   │   │   ├── ApiKey.java                          ← 7b
    │   │   │   ├── WebhookConfig.java                   ← 7b
    │   │   │   └── FeeConfig.java                       ← 7b
    │   │   ├── repository/
    │   │   │   ├── MerchantRepository.java              ← 7d
    │   │   │   ├── ApiKeyRepository.java                ← 7d
    │   │   │   └── WebhookConfigRepository.java         ← 7d
    │   │   ├── dto/
    │   │   │   ├── MerchantRegisterRequest.java         ← 7e
    │   │   │   ├── MerchantResponse.java                ← 7e
    │   │   │   ├── ApiKeyResponse.java                  ← 7e
    │   │   │   └── WebhookConfigRequest.java            ← 7e
    │   │   ├── mapper/
    │   │   │   └── MerchantMapper.java                  ← 7e
    │   │   ├── service/
    │   │   │   ├── MerchantService.java                 ← 7f
    │   │   │   ├── ApiKeyService.java                   ← 7f
    │   │   │   └── WebhookConfigService.java            ← 7f
    │   │   └── controller/
    │   │       └── MerchantController.java              ← 7g
    │   └── resources/
    │       ├── application.yml                          ← 7a
    │       └── db/migration/
    │           ├── V1__create_merchants_table.sql        ← 7c
    │           ├── V2__create_api_keys_table.sql         ← 7c
    │           ├── V3__create_webhook_configs_table.sql  ← 7c
    │           └── V4__create_fee_configs_table.sql      ← 7c
    └── test/java/com/payflow/merchant/service/
        ├── MerchantServiceTest.java                     ← 7f
        └── ApiKeyServiceTest.java                       ← 7f
```

---

## 🏗️ Architecture Diagram

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                      MERCHANT SERVICE (Port 8082)                              │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│   HTTP Request                                                                │
│       │                                                                       │
│       ▼                                                                       │
│   ┌──────────────────────────────────────────────────────────┐               │
│   │  MerchantController (7g) — 14 endpoints                  │               │
│   │  ├── Merchant CRUD:     POST/GET/PUT/DELETE merchants    │               │
│   │  ├── API Key Mgmt:      POST/GET/DELETE api-keys         │               │
│   │  └── Webhook Config:    POST/GET/PUT/DELETE webhooks     │               │
│   └──────────┬───────────┬───────────┬───────────────────────┘               │
│              │           │           │                                        │
│              ▼           ▼           ▼                                        │
│   ┌──────────────┐ ┌────────────┐ ┌──────────────────┐                      │
│   │MerchantService│ │ApiKeyService│ │WebhookConfigSvc  │  (7f)              │
│   └──────┬───────┘ └─────┬──────┘ └────────┬─────────┘                      │
│          │               │                  │                                │
│          ▼               ▼                  ▼                                │
│   ┌────────────────────────────────────────────────────────┐                │
│   │  Repositories (7d) → PostgreSQL Tables (7c)             │                │
│   │  Entities (7b) define the shape of the data             │                │
│   └────────────────────────────────────────────────────────┘                │
│                                                                               │
│   DTOs + Mapper (7e) convert between HTTP JSON ↔ Java entities               │
│   Project Setup (7a) provides config, security, and dependencies             │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## 🔐 API Key Security — The Most Important Concept

```
GENERATION (one time):
  SecureRandom → 32 bytes → Base64 → "pk_YWJjZGVm..."
                                           │
                                           ├── SHA-256 hash → store in DB
                                           └── Return raw key to merchant (ONCE!)

EVERY API CALL (validation):
  Merchant sends: X-API-Key: pk_YWJjZGVm...
                                    │
                                    └── SHA-256 hash → lookup in DB → return merchantId

WHY SHA-256 (not BCrypt)?
  API keys = RANDOM (256 bits) → can't be brute-forced → fast hash is safe
  Passwords = HUMAN-CHOSEN (weak) → can be brute-forced → need slow hash (BCrypt)
```

---

## ✅ Pre-Requisites

1. ✅ **Part 02** — `common-lib` built (`mvn install -pl common-lib`)
2. ✅ **PostgreSQL** running with:
   ```sql
   CREATE DATABASE payflow_merchant;
   GRANT ALL PRIVILEGES ON DATABASE payflow_merchant TO payflow;
   \c payflow_merchant
   GRANT ALL ON SCHEMA public TO payflow;
   ```

---

## ✅ Final Verification Checklist (After All 7 Parts)

- [ ] Service starts without errors on port 8082
- [ ] All 4 Flyway migrations run successfully
- [ ] `POST /v1/merchants` → 201 Created
- [ ] Duplicate email → 409 Conflict
- [ ] `POST /{id}/api-keys` → returns rawKey (pk_...)
- [ ] `GET /{id}/api-keys` → rawKey is null (never exposed again)
- [ ] `POST /api-keys/validate?key=pk_...` → returns merchantId
- [ ] `POST /{id}/webhooks` → returns secret (whsec_...)
- [ ] `DELETE /{id}` → soft-deactivates (active=false)
- [ ] All 11 unit tests pass (`mvn test`)
- [ ] Swagger UI loads at http://localhost:8082/swagger-ui.html
- [ ] Service appears in Eureka at http://localhost:8761

---

## 📚 Navigation

| Document | Title |
|---|---|
| **This document** | **Merchant Service Overview & Roadmap** |
| [Part 7a](./phase4-part07a-merchant-project-setup.md) | Project Setup (pom.xml, yml, main, security) |
| [Part 7b](./phase4-part07b-merchant-entities.md) | Entities (Merchant, ApiKey, WebhookConfig, FeeConfig) |
| [Part 7c](./phase4-part07c-merchant-migrations.md) | Flyway Migrations (V1-V4 SQL) |
| [Part 7d](./phase4-part07d-merchant-repositories.md) | Repositories (Data Access) |
| [Part 7e](./phase4-part07e-merchant-dtos-mapper.md) | DTOs + MerchantMapper (Input/Output) |
| [Part 7f](./phase4-part07f-merchant-services-tests.md) | Services + Unit Tests (Business Logic) |
| [Part 7g](./phase4-part07g-merchant-controller-docker.md) | Controller + curl + Dockerfile (HTTP + Deploy) |
| [Part 7h](./phase4-part07h-merchant-connections-and-flows.md) | How Everything Connects (Flows, Infrastructure, Big Picture) |

---

*Start with [Part 7a — Project Setup](./phase4-part07a-merchant-project-setup.md) →*
