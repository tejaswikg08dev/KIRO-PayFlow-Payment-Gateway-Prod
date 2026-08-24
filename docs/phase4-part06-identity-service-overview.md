# 🏗️ Phase 4 Part 06: Identity Service — Overview & Roadmap

> **"Every transaction begins with trust — and trust begins with identity."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 6 (split into 6a, 6b, 6c) |
| **Module** | `identity-service` |
| **Package** | `com.payflow.identity` |
| **Port** | 8081 |
| **Previous** | [Phase 4 Part 5: API Gateway](./phase4-part05-api-gateway.md) |
| **Next** | [Phase 4 Part 7a: Merchant Service — Entities](./phase4-part07a-merchant-entities.md) |
| **Database** | PostgreSQL — `payflow_identity` |

---

## 🎯 What Is the Identity Service?

The Identity Service is the **authentication backbone** of PayFlow. Before anyone can make a payment, accept a payment, or manage the platform, they must first prove who they are.

| Responsibility | Endpoint | Description |
|---|---|---|
| **Registration** | `POST /v1/auth/register` | Create a new user account |
| **Login** | `POST /v1/auth/login` | Authenticate with email + password |
| **Token Refresh** | `POST /v1/auth/refresh` | Get new tokens without re-entering password |
| **Profile** | `GET /v1/auth/profile` | Return current user's info |

---

## 🛠️ Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| **Java** | 17 | Language |
| **Spring Boot** | 3.2.5 | Application framework |
| **Spring Data JPA** | (via starter) | ORM / database access (Hibernate under the hood) |
| **Spring Security** | (via starter) | BCrypt password encoding + SecurityFilterChain |
| **Spring Validation** | (via starter) | `@Valid`, `@NotBlank`, `@Email`, `@Size` |
| **Spring Cloud Eureka Client** | 2023.0.1 | Register with Service Registry for discovery |
| **Spring Cloud Config Client** | 2023.0.1 | Pull configuration from Config Server |
| **PostgreSQL** | 16 | Relational database |
| **Flyway** | (via starter) | Versioned database migrations |
| **JJWT** | 0.12.5 | JWT token creation, signing, and validation |
| **Lombok** | 1.18.32 | Reduce boilerplate (`@Data`, `@Builder`, `@RequiredArgsConstructor`) |
| **MapStruct** | 1.5.5 | Compile-time entity → DTO mapping |
| **SpringDoc OpenAPI** | 2.5.0 | Auto-generated Swagger UI at `/swagger-ui/index.html` |
| **H2** | (test scope) | In-memory database for fast unit tests |
| **JUnit 5 + Mockito** | (via starter-test) | Unit and integration testing |
| **AssertJ** | (via starter-test) | Fluent test assertions |

---

## 📄 Sub-Parts Breakdown

### [Part 6a — Entities & Migrations (Data Layer)](./phase4-part06a-identity-entities.md)

**What you build:**

| File | Purpose |
|---|---|
| `Role.java` | Enum: USER, MERCHANT, ADMIN |
| `User.java` | JPA entity with UUID ID, email, passwordHash, role, active |
| `RefreshToken.java` | JPA entity for token rotation |
| `UserRepository.java` | `findByEmail()`, `existsByEmail()` |
| `RefreshTokenRepository.java` | `findByTokenAndRevokedFalse()`, `revokeAllByUserId()` |
| `V1__create_users_table.sql` | Users table with indexes |
| `V2__create_roles_table.sql` | Roles reference table |
| `V3__create_user_roles_table.sql` | Many-to-many junction table |
| `V4__create_refresh_tokens_table.sql` | Refresh tokens table |
| `pom.xml` | All dependencies |
| `application.yml` | Port, DB, JWT, Eureka config |
| `IdentityServiceApplication.java` | Spring Boot entry point |

**Key concepts taught:**
- UUID vs auto-increment (security)
- `EnumType.STRING` vs `ORDINAL` (safety)
- Soft-delete pattern (`active` flag)
- Flyway versioned migrations
- Spring Data JPA query method naming
- `@Builder.Default`, `@CreationTimestamp`, `@UpdateTimestamp`
- Index strategy (index what you query)

---

### [Part 6b — JWT & Authentication (Service Layer)](./phase4-part06b-identity-jwt-auth.md)

**What you build:**

| File | Purpose |
|---|---|
| `JwtService.java` | Generate JWT, validate, extract claims |
| `SecurityConfig.java` | BCrypt encoder (cost 12) + permit/deny rules |
| `AuthService.java` | register(), login(), refreshToken(), getProfile() |

**Key concepts taught:**
- JWT structure (header.payload.signature)
- HMAC-SHA256 symmetric signing
- Dual-token strategy (short access token + long refresh token)
- Token rotation for stolen-token detection
- BCrypt cost factor trade-offs
- `@Transactional` for atomicity
- User enumeration prevention (same error for wrong email/password)
- Constructor injection via `@RequiredArgsConstructor`

---

### [Part 6c — Controller, DTOs & Tests (HTTP Layer)](./phase4-part06c-identity-controller-tests.md)

**What you build:**

| File | Purpose |
|---|---|
| `RegisterRequest.java` | Input DTO with validation annotations |
| `LoginRequest.java` | Input DTO |
| `RefreshRequest.java` | Input DTO |
| `AuthResponse.java` | Output DTO (tokens + user profile) |
| `UserProfileResponse.java` | Output DTO |
| `AuthController.java` | REST endpoints (register, login, refresh, profile) |
| `IdentityExceptionHandler.java` | `@RestControllerAdvice` error formatting |
| `UserMapper.java` | MapStruct entity → DTO converter |
| `AuthServiceTest.java` | Mockito unit tests for business logic |
| `JwtServiceTest.java` | Unit tests for token generation/validation |
| `AuthControllerTest.java` | `@WebMvcTest` HTTP layer tests |

**Key concepts taught:**
- DTOs — why never expose entities directly
- `@Valid` + Jakarta validation annotations
- Thin controllers (zero business logic)
- `ApiResponse<T>` wrapper for consistent JSON
- `@RestControllerAdvice` centralized error handling
- Exception → HTTP status mapping
- MapStruct compile-time mapping
- Mockito: `@Mock`, `@InjectMocks`, `when().thenReturn()`, `verify()`
- `@WebMvcTest` vs `@SpringBootTest`
- Given-When-Then test pattern

---

## 🗂️ Complete File List

```
backend/identity-service/
├── pom.xml                                          ← 6a
├── Dockerfile                                       ← 6a
└── src/
    ├── main/
    │   ├── java/com/payflow/identity/
    │   │   ├── IdentityServiceApplication.java      ← 6a
    │   │   ├── model/
    │   │   │   ├── Role.java                        ← 6a
    │   │   │   ├── User.java                        ← 6a
    │   │   │   └── RefreshToken.java                ← 6a
    │   │   ├── repository/
    │   │   │   ├── UserRepository.java              ← 6a
    │   │   │   └── RefreshTokenRepository.java      ← 6a
    │   │   ├── config/
    │   │   │   └── SecurityConfig.java              ← 6b
    │   │   ├── service/
    │   │   │   ├── JwtService.java                  ← 6b
    │   │   │   └── AuthService.java                 ← 6b
    │   │   ├── dto/
    │   │   │   ├── RegisterRequest.java             ← 6c
    │   │   │   ├── LoginRequest.java                ← 6c
    │   │   │   ├── RefreshRequest.java              ← 6c
    │   │   │   ├── AuthResponse.java                ← 6c
    │   │   │   └── UserProfileResponse.java         ← 6c
    │   │   ├── controller/
    │   │   │   └── AuthController.java              ← 6c
    │   │   ├── exception/
    │   │   │   └── IdentityExceptionHandler.java    ← 6c
    │   │   └── mapper/
    │   │       └── UserMapper.java                  ← 6c
    │   └── resources/
    │       ├── application.yml                      ← 6a
    │       └── db/migration/
    │           ├── V1__create_users_table.sql        ← 6a
    │           ├── V2__create_roles_table.sql        ← 6a
    │           ├── V3__create_user_roles_table.sql   ← 6a
    │           └── V4__create_refresh_tokens_table.sql ← 6a
    └── test/java/com/payflow/identity/
        ├── service/
        │   ├── AuthServiceTest.java                 ← 6c
        │   └── JwtServiceTest.java                  ← 6c
        └── controller/
            └── AuthControllerTest.java              ← 6c
```

---

## 🔄 Implementation Order

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    BUILD ORDER (bottom → top)                             │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  STEP 3: Part 6c (HTTP Layer)                                            │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │  DTOs → Controller → ExceptionHandler → Mapper → Tests             │ │
│  │  (Depends on: AuthService, JwtService from 6b)                     │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                              ▲                                           │
│                              │                                           │
│  STEP 2: Part 6b (Service Layer)                                         │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │  SecurityConfig → JwtService → AuthService                         │ │
│  │  (Depends on: User, RefreshToken, Repositories from 6a)           │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                              ▲                                           │
│                              │                                           │
│  STEP 1: Part 6a (Data Layer)                                            │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │  pom.xml → application.yml → Role → User → RefreshToken           │ │
│  │  → Migrations (V1-V4) → Repositories                              │ │
│  │  (Depends on: common-lib from Part 02)                            │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 🏗️ Architecture Diagram

```
┌───────────────────────────────────────────────────────────────────────────┐
│                      IDENTITY SERVICE (Port 8081)                          │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│   HTTP Request                                                            │
│       │                                                                   │
│       ▼                                                                   │
│   ┌─────────────────────────────────────────────┐                        │
│   │  AuthController (6c)                         │                        │
│   │  • POST /v1/auth/register  → 201 Created    │                        │
│   │  • POST /v1/auth/login     → 200 OK         │                        │
│   │  • POST /v1/auth/refresh   → 200 OK         │                        │
│   │  • GET  /v1/auth/profile   → 200 OK         │                        │
│   └───────────────────┬─────────────────────────┘                        │
│                       │                                                   │
│                       ▼                                                   │
│   ┌─────────────────────────────────────────────┐                        │
│   │  AuthService (6b)                            │                        │
│   │  • register() — hash password, create user  │                        │
│   │  • login() — verify credentials             │                        │
│   │  • refreshToken() — rotate tokens           │                        │
│   │  • getProfile() — lookup user by ID         │                        │
│   └───────┬──────────────────────┬──────────────┘                        │
│           │                      │                                        │
│           ▼                      ▼                                        │
│   ┌───────────────┐    ┌─────────────────┐                               │
│   │ JwtService (6b)│    │PasswordEncoder  │                               │
│   │ • generate()  │    │ (BCrypt, cost 12)│                               │
│   │ • validate()  │    │ from             │                               │
│   │ • extract()   │    │ SecurityConfig   │                               │
│   └───────────────┘    └─────────────────┘                               │
│           │                                                               │
│           ▼                                                               │
│   ┌─────────────────────────────────────────────┐                        │
│   │  Repositories (6a)                           │                        │
│   │  • UserRepository.findByEmail()             │                        │
│   │  • RefreshTokenRepository.findByToken...()  │                        │
│   └───────────────────┬─────────────────────────┘                        │
│                       │                                                   │
│                       ▼                                                   │
│   ┌─────────────────────────────────────────────┐                        │
│   │  PostgreSQL (payflow_identity)               │                        │
│   │  Tables: users, roles, user_roles,           │                        │
│   │          refresh_tokens                      │                        │
│   │  (Created by Flyway V1-V4 migrations)       │                        │
│   └─────────────────────────────────────────────┘                        │
│                                                                           │
│   ┌─────────────────────────────────────────────┐                        │
│   │  IdentityExceptionHandler (6c)               │                        │
│   │  Catches all exceptions → JSON error response│                        │
│   │  • DuplicateResourceException → 409         │                        │
│   │  • UnauthorizedException → 401              │                        │
│   │  • ResourceNotFoundException → 404          │                        │
│   │  • ValidationException → 400                │                        │
│   └─────────────────────────────────────────────┘                        │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘

External connections:
  → Eureka (8761): Registers as "identity-service"
  → Config Server (8888): Pulls jwt.secret, DB credentials
  ← API Gateway (8080): Receives requests routed via lb://identity-service
```

---

## 🔐 Security Design Summary

| Feature | Implementation | Why |
|---|---|---|
| Password storage | BCrypt (cost 12) | One-way hash; ~400ms per hash makes brute-force impractical |
| Access Token | JWT (HMAC-SHA256, 15 min) | Stateless verification at API Gateway without DB call |
| Refresh Token | Opaque UUID (7 days, in DB) | Revocable; enables token rotation and theft detection |
| Token Rotation | Old RT revoked on each use | If revoked token reused → ALL tokens invalidated |
| User Enumeration | Same error for wrong email/password | Attacker can't discover valid emails |
| Soft Delete | `active` flag (not DELETE) | Preserves audit trail for payment history |
| Input Validation | `@Valid` + Jakarta annotations | Rejects bad input before it reaches business logic |

---

## ✅ Pre-Requisites (Before Starting Part 6a)

1. ✅ **Part 01** — Parent POM with all dependency versions defined
2. ✅ **Part 02** — `common-lib` built and installed (`mvn install -pl common-lib`)
3. ✅ **Part 03** — Service Registry running on port 8761 (optional for local dev)
4. ✅ **Part 04** — Config Server running on port 8888 (optional — `optional:configserver:` won't crash)
5. ✅ **PostgreSQL** — Running on port 5432 with database `payflow_identity` created:
   ```sql
   CREATE DATABASE payflow_identity;
   CREATE USER payflow WITH PASSWORD 'payflow123';
   GRANT ALL PRIVILEGES ON DATABASE payflow_identity TO payflow;
   ```

---

## ✅ Final Verification Checklist

After completing all three parts (6a + 6b + 6c), verify:

- [ ] All 4 Flyway migrations run on startup (check console output)
- [ ] `POST /v1/auth/register` returns 201 with tokens
- [ ] `POST /v1/auth/register` with same email returns 409
- [ ] `POST /v1/auth/login` returns 200 with tokens
- [ ] `POST /v1/auth/login` with wrong password returns 401
- [ ] `POST /v1/auth/register` with invalid input returns 400 with field errors
- [ ] `POST /v1/auth/refresh` returns new token pair (old token invalidated)
- [ ] `GET /v1/auth/profile` with valid X-User-Id returns user info
- [ ] All unit tests pass: `mvn test`
- [ ] Swagger UI loads: http://localhost:8081/swagger-ui/index.html
- [ ] Service appears in Eureka dashboard: http://localhost:8761

---

## 📚 Navigation

| Document | Title |
|---|---|
| **This document** | **Phase 4 Part 06 — Identity Service Overview** |
| [Part 6a](./phase4-part06a-identity-entities.md) | Entities & Migrations (Data Layer) |
| [Part 6b](./phase4-part06b-identity-jwt-auth.md) | JWT & Authentication (Service Layer) |
| [Part 6c](./phase4-part06c-identity-controller-tests.md) | Controller, DTOs & Tests (HTTP Layer) |

---

*Start with [Part 6a](./phase4-part06a-identity-entities.md) →*


---

## 🧭 End-to-End Flow: From UI to Database and Back

This section explains **exactly** what happens when a user clicks "Register" or "Login" in the browser — which services are involved, how they communicate, and what happens at each step.

---

### 🖥️ Services Involved and Their Roles

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│   ┌──────────┐     ┌──────────────┐     ┌───────────────┐     ┌────────────┐  │
│   │ Frontend │     │ API Gateway  │     │  Identity     │     │ PostgreSQL │  │
│   │ (Next.js)│     │ (Port 8080)  │     │  Service      │     │ (Port 5432)│  │
│   │ Port 3000│     │              │     │  (Port 8081)  │     │            │  │
│   └──────────┘     └──────────────┘     └───────────────┘     └────────────┘  │
│                                                                                 │
│   ┌──────────────┐     ┌──────────────┐                                        │
│   │Service Registry│   │Config Server │                                        │
│   │(Eureka: 8761) │   │(Port 8888)   │                                        │
│   └──────────────┘     └──────────────┘                                        │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

| Service | Port | Role in This Flow |
|---|---|---|
| **Frontend (Next.js)** | 3000 | User interface — forms, buttons, JavaScript |
| **API Gateway** | 8080 | Single entry point — routing, JWT check, rate limiting |
| **Identity Service** | 8081 | Business logic — register, login, token generation |
| **PostgreSQL** | 5432 | Stores users and refresh tokens permanently |
| **Service Registry (Eureka)** | 8761 | Tells API Gateway where Identity Service is running |
| **Config Server** | 8888 | Provides configuration (DB passwords, JWT secrets) on startup |

---

### 🔄 Communication Protocols

| From → To | Protocol | Format | Why |
|---|---|---|---|
| Frontend → API Gateway | HTTP/HTTPS | JSON | Browser can only speak HTTP |
| API Gateway → Identity Service | HTTP | JSON | Internal REST call via load balancer |
| Identity Service → PostgreSQL | TCP | SQL (via JDBC) | Standard database protocol |
| All Services → Eureka | HTTP | JSON | Service registration/discovery |
| All Services → Config Server | HTTP | JSON/YAML | Configuration fetch at startup |

**Key Point:** All service-to-service communication in PayFlow is synchronous HTTP (REST). There's no Kafka involved in the Identity Service flow (Kafka is used later in Payment/Settlement services).

---

### 📝 Detailed Flow: User Registration

Here's what happens step-by-step when a user fills a registration form and clicks "Register":

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  STEP 1: User fills form in browser                                            │
│  ═══════════════════════════════════                                            │
│                                                                                 │
│  Browser (http://localhost:3000/register)                                       │
│  ┌────────────────────────────────────────────────────┐                        │
│  │  Full Name:  [Tejaswi Kumar        ]              │                        │
│  │  Email:      [tejaswi@example.com  ]              │                        │
│  │  Password:   [••••••••••••         ]              │                        │
│  │  Role:       [MERCHANT ▼           ]              │                        │
│  │                                                    │                        │
│  │        [ Register ]  ← User clicks this           │                        │
│  └────────────────────────────────────────────────────┘                        │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  STEP 2: Frontend JavaScript sends HTTP request                                │
│  ══════════════════════════════════════════════                                 │
│                                                                                 │
│  When user clicks "Register", the frontend code runs:                          │
│                                                                                 │
│  // Frontend code (React/Next.js)                                              │
│  const response = await fetch('http://localhost:8080/v1/auth/register', {       │
│    method: 'POST',                                                             │
│    headers: {                                                                  │
│      'Content-Type': 'application/json'                                        │
│    },                                                                          │
│    body: JSON.stringify({                                                       │
│      fullName: 'Tejaswi Kumar',                                                │
│      email: 'tejaswi@example.com',                                             │
│      password: 'MySecureP@ss1',                                                │
│      role: 'MERCHANT'                                                          │
│    })                                                                          │
│  });                                                                           │
│                                                                                 │
│  NOTE: Frontend sends to port 8080 (API Gateway), NOT directly to 8081!        │
│  The frontend NEVER talks directly to microservices.                            │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  STEP 3: API Gateway receives the request (Port 8080)                          │
│  ════════════════════════════════════════════════════                            │
│                                                                                 │
│  The HTTP request arrives at the API Gateway. Filters run IN ORDER:            │
│                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐           │
│  │                                                                 │           │
│  │  Filter 1: RateLimitFilter (order: -3)                          │           │
│  │  ─────────────────────────────────────                          │           │
│  │  "Has this IP sent too many requests?"                          │           │
│  │  → Check Redis: IP 192.168.1.5 has 3 of 20 tokens used         │           │
│  │  → OK, not rate-limited. Pass through. ✓                       │           │
│  │                                                                 │           │
│  │  Filter 2: RequestLoggingFilter (order: -2)                     │           │
│  │  ─────────────────────────────────────────                      │           │
│  │  "Let me assign a tracking ID for this request."                │           │
│  │  → Generate X-Request-Id: "a1b2c3d4-e5f6-7890"                │           │
│  │  → Log: "Request: POST /v1/auth/register | RequestId: a1b2..." │           │
│  │  → Add header to request: X-Request-Id: a1b2c3d4-e5f6-7890    │           │
│  │                                                                 │           │
│  │  Filter 3: JwtValidationFilter (order: -1)                      │           │
│  │  ─────────────────────────────────────────                      │           │
│  │  "Does this path need JWT authentication?"                      │           │
│  │  → Path is /v1/auth/register                                   │           │
│  │  → This is in publicPaths list! SKIP validation. ✓             │           │
│  │  → (Register/Login don't need a token — you're GETTING one!)   │           │
│  │                                                                 │           │
│  │  Filter 4: ApiKeyValidationFilter (order: 0)                    │           │
│  │  ─────────────────────────────────────────                      │           │
│  │  "Is there an X-API-Key header?"                                │           │
│  │  → No X-API-Key header present. Pass through. ✓                │           │
│  │                                                                 │           │
│  │  Route Matching:                                                │           │
│  │  ─────────────                                                  │           │
│  │  Path /v1/auth/** matches route "identity-service"             │           │
│  │  URI: lb://identity-service                                     │           │
│  │                                                                 │           │
│  └─────────────────────────────────────────────────────────────────┘           │
│                                                                                 │
│  HOW lb://identity-service IS RESOLVED:                                        │
│  ──────────────────────────────────────                                        │
│  1. Gateway asks Eureka: "Where is identity-service?"                          │
│  2. Eureka responds: "192.168.1.5:8081 (status: UP)"                          │
│  3. Gateway forwards request to: http://192.168.1.5:8081/v1/auth/register     │
│     (If multiple instances were registered, it round-robins between them)      │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  STEP 4: Identity Service receives the request (Port 8081)                     │
│  ═════════════════════════════════════════════════════════                       │
│                                                                                 │
│  The request arrives at Identity Service's embedded Tomcat server.              │
│  Spring MVC dispatches it based on the URL path.                               │
│                                                                                 │
│  4a. SPRING MVC DISPATCHER                                                     │
│  ─────────────────────────                                                     │
│  "POST /v1/auth/register → which controller handles this?"                     │
│  → Scans @RequestMapping annotations                                           │
│  → Found: AuthController has @RequestMapping("/v1/auth")                       │
│  → Found: register() has @PostMapping("/register")                             │
│  → Match! Call AuthController.register()                                       │
│                                                                                 │
│  4b. DESERIALIZATION (JSON → Java object)                                      │
│  ─────────────────────────────────────────                                     │
│  Spring reads the JSON body and creates a RegisterRequest object:              │
│                                                                                 │
│  JSON:                              Java Object:                               │
│  {                                  RegisterRequest {                          │
│    "fullName": "Tejaswi Kumar"        fullName = "Tejaswi Kumar"              │
│    "email": "tejaswi@example.com"     email = "tejaswi@example.com"           │
│    "password": "MySecureP@ss1"        password = "MySecureP@ss1"              │
│    "role": "MERCHANT"                 role = "MERCHANT"                        │
│  }                                  }                                          │
│                                                                                 │
│  4c. VALIDATION (@Valid)                                                       │
│  ────────────────────────                                                      │
│  Spring checks the annotations on RegisterRequest fields:                      │
│  • @NotBlank fullName → "Tejaswi Kumar" → ✓ not blank                        │
│  • @Email email → "tejaswi@example.com" → ✓ valid email format               │
│  • @Size(min=8) password → "MySecureP@ss1" (12 chars) → ✓ long enough       │
│                                                                                 │
│  If ANY validation fails → Spring throws MethodArgumentNotValidException       │
│  → IdentityExceptionHandler catches it → returns 400 Bad Request              │
│  → Controller code NEVER runs                                                  │
│                                                                                 │
│  All validations pass! Continue to controller...                               │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  STEP 5: AuthController.register() executes                                    │
│  ═══════════════════════════════════════════                                    │
│                                                                                 │
│  @PostMapping("/register")                                                     │
│  public ResponseEntity<ApiResponse<AuthResponse>> register(                    │
│          @Valid @RequestBody RegisterRequest request) {                         │
│      AuthResponse response = authService.register(request);  ← delegates       │
│      return ResponseEntity.status(HttpStatus.CREATED)                          │
│              .body(ApiResponse.success(response));                              │
│  }                                                                             │
│                                                                                 │
│  The controller does almost nothing — just calls AuthService and wraps result. │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  STEP 6: AuthService.register() — The business logic                           │
│  ═══════════════════════════════════════════════════                             │
│                                                                                 │
│  6a. CHECK DUPLICATE EMAIL                                                     │
│  ──────────────────────────                                                    │
│  Code:  userRepository.existsByEmail("tejaswi@example.com")                    │
│  SQL:   SELECT COUNT(*) > 0 FROM users WHERE email = 'tejaswi@example.com'    │
│  Result: false (email not taken) → continue                                    │
│                                                                                 │
│  If true → throw DuplicateResourceException → ExceptionHandler → 409 response │
│                                                                                 │
│  6b. HASH THE PASSWORD                                                         │
│  ──────────────────────                                                        │
│  Code:  passwordEncoder.encode("MySecureP@ss1")                                │
│  Result: "$2a$12$LJ3m4vGy7zKxYb..." (60-char BCrypt hash)                    │
│                                                                                 │
│  What BCrypt does internally:                                                  │
│  1. Generate random salt (22 chars)                                            │
│  2. Hash password + salt through 2^12 = 4096 rounds                           │
│  3. Output: "$2a$12$" + salt + hash = 60 characters                           │
│  4. Takes ~400ms (intentionally slow to prevent brute force)                   │
│                                                                                 │
│  6c. CREATE USER ENTITY                                                        │
│  ──────────────────────                                                        │
│  Code:  User.builder()                                                         │
│           .email("tejaswi@example.com")                                        │
│           .passwordHash("$2a$12$LJ3m4vGy7z...")                               │
│           .fullName("Tejaswi Kumar")                                           │
│           .role(Role.MERCHANT)                                                 │
│           .active(true)                                                        │
│           .build()                                                             │
│                                                                                 │
│  6d. SAVE TO DATABASE                                                          │
│  ─────────────────────                                                         │
│  Code:  user = userRepository.save(user)                                       │
│  SQL:   INSERT INTO users (id, email, password_hash, full_name, role, active,  │
│              created_at, updated_at)                                            │
│         VALUES ('uuid-generated-by-hibernate', 'tejaswi@example.com',          │
│              '$2a$12$LJ3m4v...', 'Tejaswi Kumar', 'MERCHANT', true,           │
│              NOW(), NOW())                                                      │
│  Result: User now has an id field filled in: "a1b2c3d4-..."                   │
│                                                                                 │
│  6e. GENERATE JWT ACCESS TOKEN                                                 │
│  ──────────────────────────────                                                │
│  Code:  jwtService.generateAccessToken("a1b2c3d4", "tejaswi@...", "MERCHANT") │
│                                                                                 │
│  What happens:                                                                 │
│  1. Create header: {"alg":"HS256","typ":"JWT"}                                │
│  2. Create payload: {"sub":"a1b2c3d4","email":"tejaswi@...",                  │
│                      "role":"MERCHANT","iat":1700000000,"exp":1700000900}      │
│  3. Sign: HMAC-SHA256(header + "." + payload, secret_key)                     │
│  4. Output: "eyJhbGciOi...eyJzdWIiOi...SflKxwRJSM..."                        │
│                                                                                 │
│  6f. GENERATE REFRESH TOKEN                                                    │
│  ───────────────────────────                                                   │
│  Code:  UUID.randomUUID().toString()                                           │
│  Result: "f47ac10b-58cc-4372-a567-0e02b2c3d479" (random, opaque)             │
│                                                                                 │
│  6g. SAVE REFRESH TOKEN TO DATABASE                                            │
│  ──────────────────────────────────                                            │
│  SQL:   INSERT INTO refresh_tokens (id, token, user_id, expires_at, revoked)   │
│         VALUES ('uuid', 'f47ac10b-...', 'a1b2c3d4-...', NOW() + 7 days, false)│
│                                                                                 │
│  6h. BUILD RESPONSE OBJECT                                                     │
│  ──────────────────────────                                                    │
│  AuthResponse {                                                                │
│    accessToken: "eyJhbGciOi...",                                               │
│    refreshToken: "f47ac10b-58cc-...",                                          │
│    expiresIn: 900,           // seconds (15 minutes)                           │
│    user: {                                                                     │
│      id: "a1b2c3d4-...",                                                      │
│      email: "tejaswi@example.com",                                            │
│      fullName: "Tejaswi Kumar",                                               │
│      role: "MERCHANT",                                                         │
│      createdAt: "2024-01-15T10:30:00Z"                                        │
│    }                                                                           │
│  }                                                                             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  STEP 7: Response travels back                                                 │
│  ═════════════════════════════                                                  │
│                                                                                 │
│  7a. AuthService returns AuthResponse to AuthController                        │
│                                                                                 │
│  7b. AuthController wraps it:                                                  │
│      ResponseEntity.status(201).body(ApiResponse.success(authResponse))        │
│                                                                                 │
│  7c. Spring MVC serializes Java → JSON:                                        │
│      {                                                                         │
│        "success": true,                                                        │
│        "data": {                                                               │
│          "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIi...",                   │
│          "refreshToken": "f47ac10b-58cc-4372-a567-0e02b2c3d479",              │
│          "expiresIn": 900,                                                     │
│          "user": {                                                             │
│            "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",                      │
│            "email": "tejaswi@example.com",                                     │
│            "fullName": "Tejaswi Kumar",                                        │
│            "role": "MERCHANT",                                                 │
│            "createdAt": "2024-01-15T10:30:00Z"                                │
│          }                                                                     │
│        },                                                                      │
│        "timestamp": "2024-01-15T10:30:00.123Z"                                │
│      }                                                                         │
│                                                                                 │
│  7d. HTTP Response sent back:                                                  │
│      Identity Service (8081) → API Gateway (8080) → Browser (3000)            │
│      Status: 201 Created                                                       │
│      Content-Type: application/json                                            │
│      Body: (the JSON above)                                                    │
│                                                                                 │
│  7e. Frontend receives response:                                               │
│      const data = await response.json();                                       │
│      localStorage.setItem('accessToken', data.data.accessToken);               │
│      localStorage.setItem('refreshToken', data.data.refreshToken);             │
│      // Redirect user to dashboard                                             │
│      router.push('/dashboard');                                                │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

### 📝 Detailed Flow: Login (After Registration)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  SAME FLOW AS REGISTER, BUT:                                                   │
│                                                                                 │
│  Step 6 differs (AuthService.login):                                           │
│                                                                                 │
│  6a. FIND USER BY EMAIL                                                        │
│      SQL: SELECT * FROM users WHERE email = 'tejaswi@example.com'             │
│      → Found user with id="a1b2c3d4", passwordHash="$2a$12$LJ3m..."          │
│      → If NOT found: throw UnauthorizedException("Invalid email or password") │
│                                                                                 │
│  6b. VERIFY PASSWORD                                                           │
│      Code: passwordEncoder.matches("MySecureP@ss1", "$2a$12$LJ3m...")         │
│      → BCrypt re-hashes "MySecureP@ss1" with the stored salt                 │
│      → Compares result to stored hash                                          │
│      → true = match! (password correct)                                        │
│      → If false: throw UnauthorizedException("Invalid email or password")     │
│                                                                                 │
│  6c. CHECK ACCOUNT IS ACTIVE                                                   │
│      Code: if (!user.isActive()) throw UnauthorizedException("Account disabled")│
│                                                                                 │
│  6d-6h. SAME AS REGISTER (generate tokens, build response)                    │
│                                                                                 │
│  Response: 200 OK (not 201, because nothing was created)                       │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

### 📝 Detailed Flow: Using the Access Token (Protected Endpoints)

After login, the frontend stores the access token. Here's what happens when calling a protected API:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  Frontend wants to fetch merchant data:                                        │
│                                                                                 │
│  const response = await fetch('http://localhost:8080/v1/merchants', {           │
│    headers: {                                                                  │
│      'Authorization': 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIi...'              │
│      //                ^^^^^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^                  │
│      //                prefix       the JWT access token                       │
│    }                                                                           │
│  });                                                                           │
│                                                                                 │
│  AT THE API GATEWAY:                                                           │
│  ───────────────────                                                           │
│  JwtValidationFilter runs:                                                     │
│  1. Path is /v1/merchants → NOT in publicPaths → needs JWT check              │
│  2. Authorization header exists and starts with "Bearer "                      │
│  3. Extract token: "eyJhbGciOi..."                                            │
│  4. Verify signature using JWT secret key → VALID ✓                           │
│  5. Check expiration → not expired ✓                                          │
│  6. Extract claims: sub="a1b2c3d4", email="tejaswi@...", role="MERCHANT"     │
│  7. Add headers to request:                                                    │
│     X-User-Id: a1b2c3d4-e5f6-7890-abcd-ef1234567890                          │
│     X-User-Email: tejaswi@example.com                                         │
│     X-User-Role: MERCHANT                                                      │
│  8. Forward to lb://merchant-service                                           │
│                                                                                 │
│  THE DOWNSTREAM SERVICE (Merchant Service):                                    │
│  ──────────────────────────────────────────                                    │
│  • Receives request WITH the X-User-Id, X-User-Email, X-User-Role headers    │
│  • Does NOT need to parse JWT itself                                           │
│  • Just reads the headers to know who's calling                                │
│  • Can check X-User-Role == "MERCHANT" for authorization                      │
│                                                                                 │
│  WHY THIS PATTERN?                                                             │
│  → JWT is validated ONCE at the gateway                                        │
│  → All downstream services just read headers (fast, no crypto)                 │
│  → If JWT is invalid → 401 returned immediately at gateway level              │
│  → Downstream services never see invalid requests                              │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

### 📝 Detailed Flow: Token Refresh (When Access Token Expires)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  SCENARIO: 15 minutes have passed. Access token expired.                       │
│                                                                                 │
│  Frontend makes an API call → Gateway checks JWT → EXPIRED! → returns 401     │
│                                                                                 │
│  Frontend detects 401 and automatically refreshes:                             │
│                                                                                 │
│  // Frontend code (automatic refresh logic)                                    │
│  if (response.status === 401) {                                                │
│    const refreshResponse = await fetch('http://localhost:8080/v1/auth/refresh',{│
│      method: 'POST',                                                           │
│      headers: { 'Content-Type': 'application/json' },                          │
│      body: JSON.stringify({                                                    │
│        refreshToken: localStorage.getItem('refreshToken')                      │
│        // "f47ac10b-58cc-4372-a567-0e02b2c3d479"                              │
│      })                                                                        │
│    });                                                                          │
│    const newTokens = await refreshResponse.json();                             │
│    localStorage.setItem('accessToken', newTokens.data.accessToken);            │
│    localStorage.setItem('refreshToken', newTokens.data.refreshToken);          │
│    // RETRY the original request with new access token                         │
│  }                                                                             │
│                                                                                 │
│  AT IDENTITY SERVICE (AuthService.refreshToken):                               │
│  ───────────────────────────────────────────────                               │
│  1. Look up token in DB:                                                       │
│     SELECT * FROM refresh_tokens                                               │
│     WHERE token = 'f47ac10b-...' AND revoked = false                          │
│     → Found!                                                                   │
│                                                                                 │
│  2. Check expiry:                                                              │
│     Token expires_at = Jan 22 (7 days from creation)                           │
│     Current time = Jan 15                                                      │
│     → Not expired ✓                                                           │
│                                                                                 │
│  3. REVOKE old token (TOKEN ROTATION):                                         │
│     UPDATE refresh_tokens SET revoked = true WHERE id = '...'                  │
│     → This token can NEVER be used again                                       │
│                                                                                 │
│  4. Generate NEW access token + NEW refresh token                              │
│                                                                                 │
│  5. Save new refresh token to DB                                               │
│                                                                                 │
│  6. Return both new tokens to frontend                                         │
│                                                                                 │
│  SECURITY: If an attacker stole the old refresh token and tries to use it:     │
│  → It's already revoked (step 3)                                               │
│  → Query returns empty → UnauthorizedException("Invalid refresh token")       │
│  → Attacker is blocked!                                                        │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

### 🔄 Service Startup Sequence (What Happens Before Any Request)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  BOOT ORDER (these must start in sequence):                                    │
│                                                                                 │
│  1. PostgreSQL (already running)                                               │
│     └── Database payflow_identity exists                                       │
│                                                                                 │
│  2. Service Registry (Eureka) starts on :8761                                  │
│     └── Dashboard available at http://localhost:8761                            │
│     └── Waiting for services to register                                       │
│                                                                                 │
│  3. Config Server starts on :8888                                              │
│     └── Registers with Eureka                                                  │
│     └── Serves configuration files from /configurations/                       │
│                                                                                 │
│  4. Identity Service starts on :8081                                           │
│     ├── a) Contacts Config Server: GET http://localhost:8888/identity-service/default │
│     │      → Receives: DB URL, JWT secret, Eureka URL                          │
│     ├── b) Flyway runs migrations: V1 → V2 → V3 → V4                         │
│     │      → Creates tables: users, roles, user_roles, refresh_tokens          │
│     ├── c) Hibernate validates entities match DB schema                         │
│     ├── d) Registers with Eureka: "I'm identity-service at 192.168.1.5:8081"  │
│     └── e) Starts embedded Tomcat on port 8081                                 │
│                                                                                 │
│  5. API Gateway starts on :8080                                                │
│     ├── a) Contacts Config Server for routes and JWT secret                    │
│     ├── b) Registers with Eureka                                               │
│     ├── c) Fetches registry from Eureka (knows where all services are)        │
│     └── d) Ready to accept requests from frontend                              │
│                                                                                 │
│  NOW the system is ready:                                                      │
│  Frontend (3000) → Gateway (8080) → Identity Service (8081) → PostgreSQL      │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

### ❌ Error Flow: What Happens When Something Goes Wrong

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  SCENARIO: User registers with an existing email                               │
│                                                                                 │
│  1. Frontend sends: POST /v1/auth/register {"email": "existing@test.com",...}  │
│  2. Gateway forwards to Identity Service                                       │
│  3. AuthService.register() calls: userRepository.existsByEmail(email)          │
│  4. SQL returns: true (email already exists!)                                  │
│  5. AuthService throws: new DuplicateResourceException("User","email","existing@...")│
│  6. Exception bubbles up from AuthService → through Controller                 │
│  7. IdentityExceptionHandler catches it:                                       │
│                                                                                 │
│     @ExceptionHandler(DuplicateResourceException.class)                        │
│     public ResponseEntity<ApiResponse<Void>> handleDuplicate(...) {            │
│       return ResponseEntity.status(409)                                        │
│         .body(ApiResponse.error(ErrorResponse.of("DUPLICATE_RESOURCE", ...)))  │
│     }                                                                          │
│                                                                                 │
│  8. Response sent back:                                                        │
│     HTTP 409 Conflict                                                          │
│     {                                                                          │
│       "success": false,                                                        │
│       "error": {                                                               │
│         "code": "DUPLICATE_RESOURCE",                                          │
│         "message": "User already exists with email: existing@test.com"         │
│       },                                                                       │
│       "timestamp": "2024-01-15T10:30:00Z"                                     │
│     }                                                                          │
│                                                                                 │
│  9. Frontend shows error to user:                                              │
│     if (!data.success) {                                                       │
│       showError(data.error.message);  // "User already exists with email: ..." │
│     }                                                                          │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

### 📊 Complete Request Timeline (Summary)

```
TIME →  0ms        5ms         10ms       50ms        100ms       450ms       460ms       465ms       470ms
         │          │           │          │           │           │           │           │           │
         ▼          ▼           ▼          ▼           ▼           ▼           ▼           ▼           ▼
     Frontend   Gateway     Gateway    Gateway     Identity    BCrypt     Save to    Generate    Response
     sends      receives    filters    resolves    Service     hashes     DB (user   JWT +       arrives
     HTTP       request     execute    via Eureka  receives    password   + token)   build       at
     request    at :8080    (rate      → :8081     request     (~400ms)              response    frontend
                            limit,
                            logging,
                            JWT skip)

Total time: ~470ms (dominated by BCrypt hashing)
```

---

### 🗣️ Key Takeaways for Beginners

| Question | Answer |
|---|---|
| "Why doesn't the frontend talk directly to Identity Service?" | Security + flexibility. Gateway is the single entry point — handles rate limiting, CORS, auth. If you move Identity Service to a different server, frontend doesn't change. |
| "Why does the Gateway need Eureka?" | So it can find services by NAME ("identity-service") instead of hardcoding IPs. If Identity Service moves to a new IP, nothing breaks. |
| "Why not validate JWT in Identity Service?" | You'd be re-validating on every call. Gateway validates ONCE, then passes user info as headers. 10 services × 1000 requests = 10,000 JWT parses vs 1,000 at gateway. |
| "Why store refresh tokens in DB but not access tokens?" | Access tokens are validated by SIGNATURE (no DB needed). Refresh tokens must be REVOCABLE (need DB to check if revoked). |
| "Why is BCrypt so slow (400ms)?" | That IS the security feature. If an attacker steals the password hashes, they can only try ~2.5 passwords/second/CPU. Makes brute-force impractical. |
| "Why does Config Server exist?" | Without it, every service has its own application.yml with DB passwords. Change password = edit 10 files + redeploy 10 services. With Config Server = change 1 file. |
| "What if Config Server is down?" | `optional:configserver:` prefix means services use their local application.yml as fallback. They don't crash. |
| "What's the difference between @Service and @Repository?" | Both are Spring beans. @Service = business logic (AuthService). @Repository = database access (UserRepository). The difference is INTENT — tells developers what the class does. |
