# 🏗️ Phase 4 Part 6e: Identity Service — How Everything Connects

> **"The Identity Service is the trust anchor. Every other service's security traces back to here."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 6e — Connections, Flows & Big Picture |
| **Previous** | [Part 6d — Dockerfile & PostgreSQL](./phase4-part06d-identity-dockerfile-postgres.md) |
| **Next** | [Phase 4 Part 7 — Merchant Service Overview](./phase4-part07-merchant-service-overview.md) |

---

## 📖 Table of Contents

1. [How the 20 Files Connect to Each Other](#1-how-the-20-files-connect-to-each-other)
2. [Request Journey — Through Every File](#2-request-journey--through-every-file)
3. [How Identity Service Connects to Infrastructure](#3-how-identity-service-connects-to-infrastructure)
4. [How Identity Service Connects to Previously Built Services](#4-how-identity-service-connects-to-previously-built-services)
5. [How Future Services Will Use Identity Service](#5-how-future-services-will-use-identity-service)
6. [The Trust Chain — From Login to Payment](#6-the-trust-chain--from-login-to-payment)
7. [What Would Break If You Removed Each File](#7-what-would-break-if-you-removed-each-file)
8. [Concepts Map — Every Concept Connected to Every File](#8-concepts-map--every-concept-connected-to-every-file)
9. [Summary — The Complete Mental Model](#9-summary--the-complete-mental-model)

---

## 1. How the 20 Files Connect to Each Other

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    IDENTITY SERVICE — INTERNAL WIRING                            │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  pom.xml ─────────────────► ALL FILES (provides libraries)                     │
│  application.yml ─────────► Startup (DB, port, JWT, Eureka)                    │
│  IdentityServiceApplication ──► Component scan finds everything               │
│                                                                                 │
│  Entities define data:         Migrations create tables:                       │
│  Role.java        ◄──────────► (used by User.java as enum)                    │
│  User.java        ◄──────────► V1__create_users_table.sql                     │
│  RefreshToken.java ◄─────────► V4__create_refresh_tokens_table.sql            │
│                                V2__roles, V3__user_roles (reference tables)    │
│                                                                                 │
│  Repositories access data:                                                     │
│  UserRepository ──────────────► User.java                                      │
│  RefreshTokenRepository ──────► RefreshToken.java                              │
│                                                                                 │
│  Services contain logic:                                                       │
│  JwtService ──► application.yml (jwt.secret, jwt.expiration)                  │
│  AuthService ─► UserRepository + RefreshTokenRepository                       │
│              ─► JwtService + PasswordEncoder (from SecurityConfig)             │
│                                                                                 │
│  SecurityConfig provides:                                                      │
│  PasswordEncoder bean ──────► AuthService (for BCrypt hashing)                │
│  SecurityFilterChain ───────► All HTTP requests (permit/deny rules)           │
│                                                                                 │
│  DTOs define contracts:                                                        │
│  RegisterRequest ──► AuthController (input for registration)                  │
│  LoginRequest ─────► AuthController (input for login)                         │
│  RefreshRequest ───► AuthController (input for token refresh)                 │
│  AuthResponse ─────► AuthController (output: tokens + profile)                │
│  UserProfileResponse ► AuthController (output: profile only)                  │
│                                                                                 │
│  Mapper converts:                                                              │
│  UserMapper ─► User entity → UserProfileResponse DTO                          │
│                                                                                 │
│  Controller receives HTTP:                                                     │
│  AuthController ──► AuthService (delegates ALL logic)                         │
│                                                                                 │
│  Exception handler catches errors:                                             │
│  IdentityExceptionHandler ──► catches exceptions from AuthService             │
│                             ──► converts to JSON error responses               │
│                                                                                 │
│  Tests verify:                                                                 │
│  AuthServiceTest ─► Mocks: UserRepo, RefreshTokenRepo, JwtService, Encoder   │
│  JwtServiceTest ──► Tests JwtService directly (no mocks, uses reflection)     │
│  AuthControllerTest ► Mocks: AuthService (tests HTTP layer only)              │
│                                                                                 │
│  Dockerfile packages:                                                          │
│  Dockerfile ──► pom.xml (mvn package) ──► app.jar ──► Docker image            │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Request Journey — Through Every File

### Login Request: `POST /v1/auth/login`

```
FILES TOUCHED (in order):

 1. application.yml          Port 8081 (Tomcat listens here)
 2. SecurityConfig.java      /v1/auth/** → permitAll ✓
 3. AuthController.java      @PostMapping("/login") matches
 4. LoginRequest.java        Jackson deserializes JSON → DTO
 5. LoginRequest.java        @Valid → @NotBlank, @Email checks pass
 6. AuthService.java         authService.login(request) called
 7. UserRepository.java      findByEmail("x@y.com") → SQL query
 8. User.java                Hibernate maps DB row → User object
 9. V1 migration             (table must exist for query to work)
10. AuthService.java         passwordEncoder.matches() → BCrypt verify
11. SecurityConfig.java      PasswordEncoder bean (BCrypt cost 12)
12. JwtService.java          generateAccessToken(userId, email, role)
13. application.yml          jwt.secret + jwt.access-token-expiration
14. RefreshToken.java        RefreshToken.builder()...build()
15. RefreshTokenRepository   .save(refreshToken) → SQL INSERT
16. V4 migration             (refresh_tokens table must exist)
17. AuthResponse.java        Builder creates response with tokens
18. UserProfileResponse.java Nested in AuthResponse
19. AuthController.java      ResponseEntity.ok(ApiResponse.success(...))
20. pom.xml                  common-lib provides ApiResponse

= 20 files touched in a SINGLE login request
```

---

## 3. How Identity Service Connects to Infrastructure

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  IDENTITY SERVICE (Port 8081)                                                  │
│       │          │          │          │                                        │
│       ▼          ▼          ▼          ▼                                        │
│                                                                                 │
│  PostgreSQL   Eureka      Config     API Gateway                               │
│  :5432        :8761       Server     :8080                                     │
│                           :8888                                                │
│                                                                                 │
│  ═══════════════════════════════════════════════════════════                    │
│                                                                                 │
│  POSTGRESQL (payflow_identity)                                                 │
│  ──────────────────────────────                                                │
│  WHAT: Permanent storage for users and refresh tokens                          │
│  WHEN: Every register, login, refresh, profile call                            │
│  CONFIG: application.yml → spring.datasource.url                              │
│  TABLES: users, roles, user_roles, refresh_tokens                             │
│  CREATED BY: Flyway V1-V4 migrations on first startup                         │
│                                                                                 │
│  EUREKA (Service Registry)                                                     │
│  ──────────────────────────                                                    │
│  WHAT: Identity Service registers so API Gateway can find it                   │
│  WHEN: On startup (POST registration) + every 30s (heartbeat)                 │
│  CONFIG: application.yml → eureka.client.service-url.defaultZone             │
│  REGISTERS AS: "IDENTITY-SERVICE" at 192.168.x.x:8081                        │
│  IF EUREKA DOWN: Service starts but Gateway can't route to it                 │
│                                                                                 │
│  CONFIG SERVER                                                                 │
│  ─────────────                                                                 │
│  WHAT: Provides JWT secret, DB credentials in production                      │
│  WHEN: Once at startup                                                        │
│  CONFIG: application.yml → spring.config.import: optional:configserver:...    │
│  IF DOWN: "optional:" → uses local application.yml values → no crash         │
│                                                                                 │
│  API GATEWAY                                                                   │
│  ────────────                                                                  │
│  WHAT: The ONLY way external clients reach Identity Service                   │
│  HOW: Gateway route: /v1/auth/** → lb://identity-service                     │
│  WHAT GATEWAY DOES FOR /auth ENDPOINTS:                                       │
│    • Skips JWT validation (login/register don't need a token!)                │
│    • Assigns X-Request-Id for tracing                                         │
│    • Applies rate limiting                                                    │
│  WHAT GATEWAY DOES WITH TOKENS WE ISSUE:                                      │
│    • On subsequent API calls to OTHER services:                               │
│      → Gateway validates the JWT we issued                                    │
│      → Extracts userId, email, role from JWT claims                          │
│      → Passes them as X-User-Id, X-User-Email, X-User-Role headers          │
│      → Downstream services just read these headers                           │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. How Identity Service Connects to Previously Built Services

The Identity Service was built in Phase 4 Part 6. Parts 1-5 built the infrastructure it depends on:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  WHAT WE BUILT BEFORE IDENTITY SERVICE:                                        │
│                                                                                 │
│  Part 1: Parent POM (backend/pom.xml)                                          │
│  ─────────────────────────────────────                                         │
│  RELATIONSHIP: Identity Service's pom.xml has <parent> pointing here          │
│  WHAT IT PROVIDES: All version numbers (Spring Boot 3.2.5, JJWT 0.12.5,      │
│    Lombok 1.18.32, etc.) + Lombok annotation processor config                 │
│  IF MISSING: Identity's pom.xml can't resolve dependency versions → build fail│
│                                                                                 │
│  Part 2: Common Library (common-lib)                                           │
│  ────────────────────────────────────                                          │
│  RELATIONSHIP: Identity pom.xml has <dependency> on common-lib                │
│  WHAT IT PROVIDES:                                                             │
│    • ApiResponse<T>           → controller wraps all responses                │
│    • ErrorResponse            → exception handler error format                │
│    • DuplicateResourceException → thrown on duplicate email (→ 409)           │
│    • UnauthorizedException    → thrown on wrong password (→ 401)              │
│    • ResourceNotFoundException → thrown on user not found (→ 404)             │
│    • ValidationError          → field-level validation error details          │
│  IF MISSING: Identity Service won't compile (missing imports)                 │
│  MUST BE BUILT FIRST: mvn install -pl common-lib -am                         │
│                                                                                 │
│  Part 3: Service Registry (Eureka — Port 8761)                                │
│  ─────────────────────────────────────────────                                │
│  RELATIONSHIP: Identity registers with Eureka on startup                      │
│  WHAT IT PROVIDES: Service discovery — Gateway finds us by name              │
│  IF NOT RUNNING: Identity starts but logs warning about failed registration   │
│  FOR LOCAL DEV: Optional — you can call Identity directly on :8081           │
│                                                                                 │
│  Part 4: Config Server (Port 8888)                                            │
│  ─────────────────────────────────                                            │
│  RELATIONSHIP: Identity fetches config on startup                             │
│  WHAT IT PROVIDES: JWT secret, DB password, logging levels (in production)   │
│  IF NOT RUNNING: "optional:" prefix → Identity uses local application.yml    │
│  FOR LOCAL DEV: Optional — local yml has all needed values                   │
│                                                                                 │
│  Part 5: API Gateway (Port 8080)                                              │
│  ────────────────────────────────                                             │
│  RELATIONSHIP: Gateway routes /v1/auth/** to Identity Service                │
│  WHAT IT PROVIDES:                                                             │
│    • Single entry point for all clients                                       │
│    • Rate limiting (Redis token bucket)                                       │
│    • Request logging (X-Request-Id)                                           │
│    • JWT validation (for non-auth endpoints)                                  │
│    • CORS headers (for browser requests)                                      │
│  CRITICAL: Gateway and Identity SHARE the same jwt.secret                    │
│    → Identity signs JWTs with it                                              │
│    → Gateway verifies JWTs with it                                            │
│    → If secrets differ → Gateway rejects ALL tokens → nothing works          │
│  IF NOT RUNNING: Clients must call Identity directly on :8081                │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### The Shared JWT Secret — Critical Connection

```
Identity Service (application.yml):
  jwt:
    secret: payflow-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256

API Gateway (application.yml):
  jwt:
    secret: payflow-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256
                                    ↑
                    MUST BE IDENTICAL!

Identity SIGNS tokens with this key.
Gateway VERIFIES tokens with this key.
If they don't match → every authenticated request fails with 401.

In production: Both services pull this from Config Server (single source of truth).
```

---

## 5. How Future Services Will Use Identity Service

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  SERVICES THAT DEPEND ON IDENTITY (built after Identity):                      │
│                                                                                 │
│  Part 7: Merchant Service (Port 8082)                                          │
│  ─────────────────────────────────────                                         │
│  HOW: User registers as MERCHANT role via Identity Service                    │
│       → Gets JWT → Uses JWT to create merchant profile                        │
│  DEPENDENCY: Indirect — via JWT token, not direct API call                    │
│                                                                                 │
│  Part 8: Payment Service (Port 8083)                                           │
│  ─────────────────────────────────────                                         │
│  HOW: Payment requests include JWT                                            │
│       → Gateway validates JWT (issued by Identity)                            │
│       → Passes X-User-Id header to Payment Service                           │
│  DEPENDENCY: Indirect — via JWT token validation at Gateway                   │
│                                                                                 │
│  Part 9: Settlement Service (Port 8085)                                        │
│  ──────────────────────────────────────                                        │
│  HOW: Admin users (ADMIN role) access settlement reports                      │
│       → JWT contains role=ADMIN                                               │
│       → Gateway passes X-User-Role: ADMIN header                             │
│  DEPENDENCY: Indirect — via role in JWT claims                                │
│                                                                                 │
│  KEY INSIGHT: Identity Service doesn't CALL other services.                   │
│  Other services don't CALL Identity Service directly either.                  │
│  The connection is the JWT TOKEN — issued by Identity, validated by Gateway,  │
│  consumed by all downstream services via X-headers.                           │
│                                                                                 │
│  ┌──────────┐   JWT    ┌─────────┐  X-User-Id  ┌──────────────┐             │
│  │ Identity │──────────│ Gateway │─────────────│ Any Service  │             │
│  │ (issues) │          │(validates)│             │ (reads header)│             │
│  └──────────┘          └─────────┘             └──────────────┘             │
│                                                                                 │
│  DECOUPLED: Services don't know about Identity Service.                       │
│  They only know about X-User-Id and X-User-Role headers.                      │
│  If you replaced Identity with Keycloak/Auth0, downstream services            │
│  wouldn't change — as long as Gateway still provides the same headers.        │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. The Trust Chain — From Login to Payment

This shows how a single login flows through the ENTIRE system:

```
TIME ──────────────────────────────────────────────────────────────────────────►

T0: MERCHANT REGISTERS
    Browser → Gateway → Identity Service
    POST /v1/auth/register {"email":"merchant@shop.com","password":"...","role":"MERCHANT"}
    ← Identity returns: { accessToken: "eyJ...", refreshToken: "uuid..." }
    Browser stores both tokens.

T1: MERCHANT CREATES PROFILE (uses access token from T0)
    Browser → Gateway → Merchant Service
    POST /v1/merchants  (Authorization: Bearer eyJ...)
    Gateway: validates JWT → adds X-User-Id, X-User-Role: MERCHANT
    Merchant Service: creates merchant profile
    ← Returns: { merchantId: "uuid-123" }

T2: MERCHANT GENERATES API KEY
    Browser → Gateway → Merchant Service
    POST /v1/merchants/uuid-123/api-keys  (Authorization: Bearer eyJ...)
    ← Returns: { rawKey: "pk_YWJjZGVm..." }   ← SAVE THIS!

T3: MERCHANT'S SERVER CREATES ORDER (uses API key from T2)
    Server → Gateway → Payment Service
    POST /v1/orders  (X-API-Key: pk_YWJjZGVm...)
    Gateway: passes X-API-Key downstream
    Payment Service: calls Merchant Service to validate key → gets merchantId
    ← Returns: { orderId: "order_xyz" }

T4: CUSTOMER PAYS
    Browser → Gateway → Payment Service
    POST /v1/payments/order_xyz/authorize
    ← Returns: { paymentId: "pay_abc", status: "AUTHORIZED" }

T5: ACCESS TOKEN EXPIRES (15 minutes after T0)
    Browser → Gateway → Any Service
    Authorization: Bearer eyJ... (EXPIRED!)
    Gateway: JWT expired → returns 401
    Browser: detects 401 → auto-refreshes:

T6: TOKEN REFRESH
    Browser → Gateway → Identity Service
    POST /v1/auth/refresh { refreshToken: "uuid..." }
    Identity: validates refresh token → revokes old → issues new pair
    ← Returns: { accessToken: "NEW eyJ...", refreshToken: "NEW uuid..." }
    Browser: stores new tokens, retries original request.

THE TRUST CHAIN:
  Identity issues JWT → Gateway validates JWT → All services trust the headers
  Identity issues refresh token → stored in DB → enables long sessions
  Everything traces back to that first POST /v1/auth/register.
```

---

## 7. What Would Break If You Removed Each File

| If You Remove... | What Breaks | Error |
|---|---|---|
| `pom.xml` | Everything | "Non-readable POM" |
| `application.yml` | Startup | "Failed to configure DataSource" |
| `IdentityServiceApplication.java` | Startup | "No main class found" |
| `SecurityConfig.java` | All requests blocked | "401 Unauthorized" (Spring default) |
| `Role.java` | User.java won't compile | "Cannot find symbol: Role" |
| `User.java` | UserRepository breaks | "Not a managed type: User" |
| `RefreshToken.java` | RefreshTokenRepository breaks | "Not a managed type: RefreshToken" |
| `V1 migration` | Hibernate validation | "Missing table [users]" |
| `V4 migration` | Hibernate validation | "Missing table [refresh_tokens]" |
| `UserRepository.java` | AuthService breaks | "No qualifying bean of type UserRepository" |
| `RefreshTokenRepository.java` | Token refresh breaks | "No qualifying bean of type RefreshTokenRepository" |
| `JwtService.java` | No token generation | "No qualifying bean of type JwtService" |
| `AuthService.java` | Controller has nothing to call | "No qualifying bean of type AuthService" |
| `RegisterRequest.java` | POST /register fails | "400 Bad Request" (can't deserialize) |
| `LoginRequest.java` | POST /login fails | "400 Bad Request" |
| `AuthResponse.java` | Responses fail | "No serializer found" |
| `AuthController.java` | No HTTP endpoints | Service runs but accepts nothing |
| `IdentityExceptionHandler.java` | Ugly error responses | HTML error pages instead of JSON |
| `UserMapper.java` | Profile endpoint breaks | "No qualifying bean of type UserMapper" |
| `Dockerfile` | Can't containerize | Service still runs locally with `mvn spring-boot:run` |

---

## 8. Concepts Map — Every Concept Connected to Every File

| Concept | Files That Use It |
|---|---|
| **Spring IoC / DI** | All @Service, @Repository, @RestController, @Configuration |
| **JPA / Hibernate** | User.java, RefreshToken.java, Repositories, application.yml (ddl-auto) |
| **Flyway** | V1-V4 SQL, application.yml (flyway.enabled) |
| **Lombok** | All Java files (@Data, @Builder, @RequiredArgsConstructor, @Slf4j) |
| **MapStruct** | UserMapper.java, pom.xml (mapstruct dependency) |
| **JWT (JJWT)** | JwtService.java, pom.xml (jjwt-api/impl/jackson) |
| **BCrypt** | SecurityConfig.java (PasswordEncoder), AuthService.java (encode/matches) |
| **Jakarta Validation** | DTOs (@NotBlank, @Email, @Size), Controller (@Valid) |
| **REST API** | AuthController.java, DTOs |
| **Token Rotation** | AuthService.refreshToken(), RefreshToken.java, RefreshTokenRepository |
| **ApiResponse wrapper** | AuthController + common-lib (ApiResponse.java) |
| **@Transactional** | AuthService (register, refreshToken) |
| **Eureka** | IdentityServiceApplication + application.yml |
| **Config Client** | application.yml (config.import) |
| **Docker** | Dockerfile |
| **Spring Security** | SecurityConfig.java |
| **Mockito** | AuthServiceTest, JwtServiceTest, AuthControllerTest |

---

## 9. Summary — The Complete Mental Model

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                                                                 │
│  THE IDENTITY SERVICE IN ONE PICTURE:                                          │
│                                                                                 │
│  WHAT IT IS:                                                                   │
│    The authentication backbone of PayFlow.                                     │
│    Issues JWT tokens that ALL other services trust.                            │
│                                                                                 │
│  WHAT IT DEPENDS ON (built before it):                                         │
│    Part 1: Parent POM         → dependency versions                            │
│    Part 2: Common Library     → ApiResponse, exceptions                        │
│    Part 3: Eureka             → service registration                           │
│    Part 4: Config Server      → centralized config (optional locally)          │
│    Part 5: API Gateway        → routes /v1/auth/** to us                      │
│    PostgreSQL                 → stores users + refresh tokens                  │
│                                                                                 │
│  WHAT DEPENDS ON IT (built after it):                                          │
│    Part 7: Merchant Service   → merchants are users with MERCHANT role        │
│    Part 8: Payment Service    → JWT validates who's making payments           │
│    Part 9: Settlement Service → JWT role=ADMIN for settlement access          │
│    API Gateway                → validates JWTs we issue                       │
│                                                                                 │
│  THE KEY INSIGHT:                                                              │
│    Identity Service doesn't CALL other services.                              │
│    Other services don't CALL Identity directly.                               │
│    The JWT TOKEN is the connector.                                            │
│    Identity signs it. Gateway validates it. Everyone else trusts the headers.  │
│                                                                                 │
│  FILES: 20 source files + 4 SQL migrations + 3 test files + 1 Dockerfile     │
│         = 28 files total                                                       │
│                                                                                 │
│  ENDPOINTS: 4 (register, login, refresh, profile)                             │
│                                                                                 │
│  DATABASES: payflow_identity (users, roles, user_roles, refresh_tokens)       │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part06-identity-service-overview.md) | Identity Service Overview |
| [Part 6a](./phase4-part06a-identity-entities.md) | Entities & Migrations |
| [Part 6b](./phase4-part06b-identity-jwt-auth.md) | JWT & Authentication |
| [Part 6c](./phase4-part06c-identity-controller-tests.md) | Controller & Tests |
| [Part 6d](./phase4-part06d-identity-dockerfile-postgres.md) | Dockerfile & PostgreSQL |
| **Part 6e** | **How Everything Connects** (You are here) |

---

*Identity Service is COMPLETE. Next: [Phase 4 Part 7 — Merchant Service Overview](./phase4-part07-merchant-service-overview.md) →*
