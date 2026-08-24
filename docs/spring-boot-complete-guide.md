# 🚀 Spring Boot — Zero to Hero Complete Guide

> **For the PayFlow Payment Gateway project. Everything explained with real examples from your codebase.**

---

## 📋 Document Info

| Field | Value |
|-------|-------|
| **Audience** | First-time Spring Boot developer |
| **Prerequisite** | Basic Java knowledge (classes, interfaces, annotations) |
| **Project Context** | PayFlow Payment Gateway (your project) |
| **How to Use** | Read top-to-bottom. Each section builds on the previous. |

---

## 📖 Table of Contents

1. [What Is Spring Boot?](#1-what-is-spring-boot)
2. [Spring vs Spring Boot — The Difference](#2-spring-vs-spring-boot--the-difference)
3. [Core Concept: Inversion of Control (IoC)](#3-core-concept-inversion-of-control-ioc)
4. [Core Concept: Dependency Injection (DI)](#4-core-concept-dependency-injection-di)
5. [Beans — The Building Blocks](#5-beans--the-building-blocks)
6. [Annotations — The Spring Language](#6-annotations--the-spring-language)
7. [Application Layers (Architecture)](#7-application-layers-architecture)
8. [The @SpringBootApplication Entry Point](#8-the-springbootapplication-entry-point)
9. [Controllers — Handling HTTP Requests](#9-controllers--handling-http-requests)
10. [Services — Business Logic](#10-services--business-logic)
11. [Repositories — Database Access](#11-repositories--database-access)
12. [Entities — Database Tables as Java Classes](#12-entities--database-tables-as-java-classes)
13. [DTOs — Data Transfer Objects](#13-dtos--data-transfer-objects)
14. [Configuration — application.yml](#14-configuration--applicationyml)
15. [Validation — Rejecting Bad Input](#15-validation--rejecting-bad-input)
16. [Exception Handling — @RestControllerAdvice](#16-exception-handling--restcontrolleradvice)
17. [Spring Security — Authentication & Authorization](#17-spring-security--authentication--authorization)
18. [Spring Data JPA — ORM Made Easy](#18-spring-data-jpa--orm-made-easy)
19. [Flyway — Database Migrations](#19-flyway--database-migrations)
20. [Spring Cloud — Microservices Toolkit](#20-spring-cloud--microservices-toolkit)
21. [Testing in Spring Boot](#21-testing-in-spring-boot)
22. [Lombok — Less Boilerplate](#22-lombok--less-boilerplate)
23. [How Spring Boot Starts (Boot Sequence)](#23-how-spring-boot-starts-boot-sequence)
24. [Common Errors & Solutions](#24-common-errors--solutions)
25. [Cheat Sheet — Quick Reference](#25-cheat-sheet--quick-reference)

---

## 1. What Is Spring Boot?

Spring Boot is a **framework** that makes building Java web applications fast and simple.

**Without Spring Boot** (raw Java):
```java
// You'd have to manually:
// 1. Download and configure Tomcat
// 2. Write XML configurations (hundreds of lines)
// 3. Manage dependencies manually
// 4. Write database connection code
// 5. Handle JSON serialization yourself
// 6. Configure security from scratch
// Total: 2000+ lines of setup before writing business logic
```

**With Spring Boot**:
```java
@SpringBootApplication
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
// That's it. You have a running web server with:
// ✓ Embedded Tomcat (no external server needed)
// ✓ JSON serialization (Jackson)
// ✓ Database connection pooling (HikariCP)
// ✓ Health endpoints (/actuator/health)
// ✓ Auto-configuration for 200+ libraries
```

**In one sentence:** Spring Boot = Spring Framework + Auto-Configuration + Embedded Server + Opinionated Defaults.

---

## 2. Spring vs Spring Boot — The Difference

| Aspect | Spring Framework | Spring Boot |
|--------|-----------------|-------------|
| Configuration | Manual XML/Java | Auto-configured |
| Server | External Tomcat | Embedded Tomcat |
| Dependencies | Pick each version manually | Starter POMs pick for you |
| Startup | Complex setup | `main()` method |
| Learning curve | Steep | Moderate |

**Analogy:**
- **Spring** = Buying car parts and assembling yourself
- **Spring Boot** = Buying a fully assembled car, ready to drive

Your PayFlow project uses **Spring Boot 3.2.5** — you never touch raw Spring XML.

---

## 3. Core Concept: Inversion of Control (IoC)

### The Old Way (You Control Everything)

```java
// YOU create objects, YOU manage their lifecycle
public class AuthController {
    public AuthController() {
        PasswordEncoder encoder = new BCryptPasswordEncoder(12);
        DataSource ds = new HikariDataSource(config);
        UserRepository repo = new UserRepositoryImpl(ds);
        JwtService jwt = new JwtService("secret", 900000);
        this.authService = new AuthService(repo, jwt, encoder);
    }
}
// Problems:
// 1. Controller knows about ALL implementation details
// 2. Hard to test (can't swap real DB for fake)
// 3. If JwtService constructor changes, you fix it everywhere
```

### The Spring Way (Framework Controls)

```java
// SPRING creates objects, SPRING manages lifecycle
// You just declare what you need
@RestController
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;  // Spring provides this
}
// Benefits:
// 1. Controller only knows about AuthService interface
// 2. Easy to test (Spring can inject a mock)
// 3. If dependencies change, only the bean definition changes
```

**IoC = You don't create objects. The framework creates them and gives them to you.**

---

## 4. Core Concept: Dependency Injection (DI)

DI is HOW IoC works in practice. Spring "injects" objects into your classes.

### Three Ways to Inject (Only Use #1)

```java
// ✅ WAY 1: Constructor Injection (BEST — what PayFlow uses)
@Service
@RequiredArgsConstructor  // Lombok generates constructor
public class AuthService {
    private final UserRepository userRepository;      // Injected
    private final JwtService jwtService;              // Injected
    private final PasswordEncoder passwordEncoder;    // Injected
}

// ❌ WAY 2: Field Injection (AVOID — harder to test)
@Service
public class AuthService {
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
}

// ❌ WAY 3: Setter Injection (AVOID — can be null at runtime)
@Service
public class AuthService {
    private UserRepository userRepository;
    
    @Autowired
    public void setUserRepository(UserRepository repo) {
        this.userRepository = repo;
    }
}
```

### Why Constructor Injection Wins

| Feature | Constructor | Field (@Autowired) | Setter |
|---------|:-----------:|:------------------:|:------:|
| Immutable (final fields) | ✅ | ❌ | ❌ |
| Fails fast if missing | ✅ (startup) | ❌ (runtime NPE) | ❌ |
| Easy to unit test | ✅ | ❌ (needs reflection) | ✅ |
| Clear dependencies | ✅ (visible in constructor) | ❌ (hidden) | ❌ |

---

## 5. Beans — The Building Blocks

A **bean** is any object that Spring creates and manages.

### The Spring Container

```
┌─────────────────────────────────────────────────────────────────┐
│              SPRING APPLICATION CONTEXT (Container)              │
│              ─────────────────────────────────────               │
│                                                                 │
│  Spring creates ONE instance of each bean and reuses it:        │
│  (This is called "Singleton Scope" — the default)               │
│                                                                 │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐ │
│  │  AuthController  │  │   AuthService    │  │  JwtService   │ │
│  │  (1 instance)    │  │  (1 instance)    │  │ (1 instance)  │ │
│  └─────────────────┘  └──────────────────┘  └───────────────┘ │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐ │
│  │ UserRepository  │  │ RefreshTokenRepo │  │PasswordEncoder│ │
│  │  (1 instance)    │  │  (1 instance)    │  │ (1 instance)  │ │
│  └─────────────────┘  └──────────────────┘  └───────────────┘ │
│                                                                 │
│  When AuthService needs UserRepository,                         │
│  Spring finds the single instance here and injects it.          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Two Ways to Create Beans

```java
// WAY 1: Annotate your own class (most common)
@Service                    // ← "Spring, please create and manage this"
public class AuthService {
    // ...
}

// WAY 2: @Bean method in @Configuration (for third-party classes)
@Configuration
public class SecurityConfig {
    
    @Bean                   // ← "Spring, call this method and manage the result"
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
    // You can't put @Service on BCryptPasswordEncoder (it's not your class)
    // So you create it via a @Bean method
}
```

### Bean Scope

| Scope | Meaning | Use Case |
|-------|---------|----------|
| `singleton` (default) | ONE instance for entire app | Services, repositories, controllers |
| `prototype` | NEW instance every time | Rarely used |
| `request` | ONE per HTTP request | Request-scoped data |
| `session` | ONE per user session | Not used in stateless APIs |

PayFlow uses singleton for everything (stateless design).

---

## 6. Annotations — The Spring Language

Annotations are the primary way you communicate with Spring.

### Stereotype Annotations (Register Beans)

| Annotation | Meaning | Layer | PayFlow Example |
|---|---|---|---|
| `@Component` | "I'm a Spring-managed bean" | Any | Base — rarely used directly |
| `@Service` | "I'm a bean with business logic" | Service | `AuthService`, `JwtService` |
| `@Repository` | "I'm a bean that accesses DB" | Data | `UserRepository` |
| `@RestController` | "I handle HTTP requests + return JSON" | HTTP | `AuthController` |
| `@Configuration` | "I define beans and config" | Config | `SecurityConfig` |

### How They Relate

```
@Component                      ← Parent (generic)
  ├── @Service                  ← Child (business logic)
  ├── @Repository               ← Child (database)
  ├── @Controller               ← Child (HTTP — returns views)
  │     └── @RestController     ← Controller + @ResponseBody (returns JSON)
  └── @Configuration            ← Child (bean factory)
```

They ALL register beans. The specialization is for:
1. **Readability** — tells developers the class purpose
2. **Spring features** — `@Repository` adds DB exception translation
3. **Component scanning** — can filter by type

### HTTP Annotations

| Annotation | HTTP Method | Example |
|---|---|---|
| `@GetMapping("/profile")` | GET | Fetch data |
| `@PostMapping("/register")` | POST | Create resource |
| `@PutMapping("/users/{id}")` | PUT | Update resource |
| `@DeleteMapping("/users/{id}")` | DELETE | Delete resource |
| `@PatchMapping("/users/{id}")` | PATCH | Partial update |

### Parameter Annotations

| Annotation | What It Does | Example |
|---|---|---|
| `@RequestBody` | Deserializes JSON body → Java object | `@RequestBody RegisterRequest request` |
| `@PathVariable` | Extracts from URL path | `/users/{id}` → `@PathVariable String id` |
| `@RequestParam` | Extracts query parameter | `/users?page=1` → `@RequestParam int page` |
| `@RequestHeader` | Extracts HTTP header | `@RequestHeader("X-User-Id") String userId` |
| `@Valid` | Triggers validation annotations | `@Valid @RequestBody RegisterRequest req` |

### Configuration Annotations

| Annotation | What It Does | Example |
|---|---|---|
| `@Value("${jwt.secret}")` | Injects property from yml | Field gets "payflow-jwt-secret..." |
| `@Bean` | Method return value becomes a bean | `@Bean PasswordEncoder encoder()` |
| `@Primary` | Preferred bean when multiple exist | Mark default implementation |
| `@Qualifier("name")` | Pick specific bean by name | When 2+ beans of same type exist |

---

## 7. Application Layers (Architecture)

Spring Boot apps follow a **layered architecture**:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         SPRING BOOT APPLICATION                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │  CONTROLLER LAYER (@RestController)                                │ │
│  │  • Receives HTTP requests                                         │ │
│  │  • Validates input (@Valid)                                        │ │
│  │  • Delegates to Service                                           │ │
│  │  • Returns HTTP response (status code + JSON)                     │ │
│  │  • ZERO business logic                                            │ │
│  └───────────────────────────┬───────────────────────────────────────┘ │
│                              │                                          │
│  ┌───────────────────────────▼───────────────────────────────────────┐ │
│  │  SERVICE LAYER (@Service)                                          │ │
│  │  • Contains ALL business logic                                    │ │
│  │  • Orchestrates multiple repositories                             │ │
│  │  • Handles transactions (@Transactional)                          │ │
│  │  • Throws business exceptions                                     │ │
│  │  • Doesn't know about HTTP (no Request/Response objects)          │ │
│  └───────────────────────────┬───────────────────────────────────────┘ │
│                              │                                          │
│  ┌───────────────────────────▼───────────────────────────────────────┐ │
│  │  REPOSITORY LAYER (@Repository)                                    │ │
│  │  • Talks to database                                              │ │
│  │  • CRUD operations                                                │ │
│  │  • Custom queries (@Query)                                        │ │
│  │  • Returns entities                                               │ │
│  └───────────────────────────┬───────────────────────────────────────┘ │
│                              │                                          │
│  ┌───────────────────────────▼───────────────────────────────────────┐ │
│  │  DATABASE                                                          │ │
│  │  • PostgreSQL (tables, indexes, constraints)                      │ │
│  │  • Managed by Flyway migrations                                   │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │  CROSS-CUTTING CONCERNS (apply to all layers)                     │ │
│  │  • Exception Handler (@RestControllerAdvice)                      │ │
│  │  • Security (SecurityFilterChain)                                 │ │
│  │  • Configuration (@Configuration + @Value)                        │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Rule: Each Layer Only Talks to the One Below

```
Controller → Service → Repository → Database

✅ Controller calls Service
✅ Service calls Repository
❌ Controller calls Repository directly (skips business logic!)
❌ Repository calls Service (circular!)
```

---

## 8. The @SpringBootApplication Entry Point

```java
package com.payflow.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication  // ← This one annotation does THREE things
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
```

### What @SpringBootApplication Does

```
@SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan

@Configuration:
  "This class can define @Bean methods"

@EnableAutoConfiguration:
  "Spring, look at my dependencies (pom.xml) and auto-configure everything"
  → Found spring-data-jpa? Auto-configure Hibernate + DataSource
  → Found spring-security? Auto-configure SecurityFilterChain
  → Found postgresql driver? Configure PostgreSQL connection

@ComponentScan:
  "Scan THIS package and all sub-packages for @Component/@Service/@Repository/etc"
  → Package: com.payflow.identity
  → Scans: com.payflow.identity.service.AuthService ✓
  → Scans: com.payflow.identity.controller.AuthController ✓
  → Scans: com.payflow.identity.repository.UserRepository ✓
  → Does NOT scan: com.payflow.payment.* (different package!)
```

### SpringApplication.run() — What Happens Inside

```
1. Creates ApplicationContext (the bean container)
2. Scans for all annotated classes
3. Creates beans in dependency order
4. Reads application.yml
5. Runs auto-configuration
6. Runs Flyway migrations (if configured)
7. Starts embedded Tomcat on configured port
8. Registers with Eureka (if configured)
9. Prints "Started IdentityServiceApplication in X seconds"
10. Waits for HTTP requests
```

---

## 9. Controllers — Handling HTTP Requests

### What a Controller Does

```
HTTP Request → [Deserialize JSON] → [Validate] → [Call Service] → [Serialize JSON] → HTTP Response
```

### Complete Example (Your AuthController)

```java
@RestController              // ① "I handle HTTP and return JSON"
@RequestMapping("/v1/auth")  // ② Base path: all endpoints start with /v1/auth
@RequiredArgsConstructor     // ③ Lombok: generates constructor for final fields
public class AuthController {

    private final AuthService authService;  // ④ Injected by Spring

    @PostMapping("/register")  // ⑤ Handles: POST /v1/auth/register
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        //  ⑥ @Valid = validate annotations on RegisterRequest
        //  ⑦ @RequestBody = deserialize JSON body into RegisterRequest object
        
        AuthResponse response = authService.register(request);  // ⑧ Delegate
        
        return ResponseEntity
                .status(HttpStatus.CREATED)        // ⑨ HTTP 201
                .body(ApiResponse.success(response)); // ⑩ Wrap in standard envelope
    }
}
```

### ResponseEntity — Controlling HTTP Response

```java
// 200 OK (default for successful GET/POST)
return ResponseEntity.ok(body);

// 201 Created (after creating a resource)
return ResponseEntity.status(HttpStatus.CREATED).body(body);

// 204 No Content (successful delete, nothing to return)
return ResponseEntity.noContent().build();

// 404 Not Found
return ResponseEntity.notFound().build();
```

---

## 10. Services — Business Logic

### What a Service Does

```
Receives request from Controller
→ Validates business rules (email unique? account active?)
→ Calls repositories for data
→ Transforms data (hash password, generate token)
→ Returns result to controller
```

### Example (Your AuthService — simplified)

```java
@Service                     // ① Register as bean
@RequiredArgsConstructor     // ② Constructor injection
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @Transactional  // ③ If anything fails, rollback ALL DB changes
    public AuthResponse register(RegisterRequest request) {
        
        // Business rule: email must be unique
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("User", "email", request.getEmail());
        }

        // Business logic: hash the password
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .role(Role.USER)
                .build();

        user = userRepository.save(user);  // Save to DB

        // Business logic: generate tokens
        String accessToken = jwtService.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .build();
    }
}
```

### @Transactional — Atomicity

```java
@Transactional
public AuthResponse register(RegisterRequest request) {
    userRepository.save(user);           // DB INSERT #1
    refreshTokenRepository.save(token);  // DB INSERT #2
    
    // If INSERT #2 fails (e.g., DB constraint violation):
    // → INSERT #1 is ROLLED BACK automatically
    // → Database stays consistent (no orphaned user without token)
}
```

Without `@Transactional`, INSERT #1 would persist even if INSERT #2 fails = inconsistent data.

---

## 11. Repositories — Database Access

### What Spring Data JPA Does

You write an **interface**. Spring generates the **implementation** at runtime.

```java
@Repository
public interface UserRepository extends JpaRepository<User, String> {
    //                                        ↑       ↑
    //                                   Entity    PK type

    // You write this method signature:
    Optional<User> findByEmail(String email);

    // Spring GENERATES this implementation:
    // @Override
    // public Optional<User> findByEmail(String email) {
    //     return entityManager
    //         .createQuery("SELECT u FROM User u WHERE u.email = :email", User.class)
    //         .setParameter("email", email)
    //         .getResultList()
    //         .stream()
    //         .findFirst();
    // }
}
```

### Method Name → Query Generation

| Method Name | Generated SQL |
|---|---|
| `findByEmail(email)` | `WHERE email = ?` |
| `findByEmailAndActive(email, active)` | `WHERE email = ? AND active = ?` |
| `existsByEmail(email)` | `SELECT COUNT(*) > 0 WHERE email = ?` |
| `findByRoleOrderByCreatedAtDesc(role)` | `WHERE role = ? ORDER BY created_at DESC` |
| `countByActive(active)` | `SELECT COUNT(*) WHERE active = ?` |
| `deleteByEmail(email)` | `DELETE WHERE email = ?` |

### Free Methods from JpaRepository

```java
// You get ALL of these for free (no code needed):
userRepository.save(user);              // INSERT or UPDATE
userRepository.findById("uuid");        // SELECT by primary key
userRepository.findAll();               // SELECT all
userRepository.count();                 // SELECT COUNT(*)
userRepository.deleteById("uuid");      // DELETE by primary key
userRepository.existsById("uuid");      // EXISTS check
```

### Custom Queries with @Query

```java
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    // When method naming isn't enough, write JPQL:
    @Modifying  // Required for UPDATE/DELETE queries
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.userId = :userId")
    void revokeAllByUserId(String userId);
}
```

---

## 12. Entities — Database Tables as Java Classes

### What JPA Does

```
Java Object (Entity)  ←→  Database Row (Table)

User.java             ←→  users table
  .id                       id column
  .email                    email column
  .passwordHash             password_hash column
```

### Complete Example (Your User Entity)

```java
@Entity                    // "This class maps to a DB table"
@Table(name = "users")     // Table name (explicit to avoid reserved words)
@Data                      // Lombok: getters + setters + toString + equals
@Builder                   // Lombok: User.builder().email("x").build()
@NoArgsConstructor         // JPA needs this (creates empty object, then sets fields)
@AllArgsConstructor        // Builder needs this
public class User {

    @Id                                             // Primary key
    @GeneratedValue(strategy = GenerationType.UUID) // Auto-generate UUID
    private String id;

    @Column(nullable = false, unique = true)        // NOT NULL + UNIQUE constraint
    private String email;

    @Column(name = "password_hash", nullable = false)  // Column name in DB
    private String passwordHash;
    // Why "name" param? Java convention: camelCase. DB convention: snake_case.

    @Enumerated(EnumType.STRING)  // Store enum as "USER"/"MERCHANT"/"ADMIN"
    @Column(nullable = false)
    private Role role;

    @CreationTimestamp                    // Auto-set on INSERT
    @Column(name = "created_at", updatable = false)  // Can't change after creation
    private Instant createdAt;
}
```

### JPA Lifecycle

```
1. You call: userRepository.save(user)
2. Hibernate checks: Does this user have an ID?
   - No ID  → INSERT INTO users (id, email, ...) VALUES (generated_uuid, ...)
   - Has ID → UPDATE users SET email = ?, ... WHERE id = ?
3. Hibernate converts Java types → SQL types:
   - String → VARCHAR
   - Instant → TIMESTAMP
   - Role.MERCHANT → 'MERCHANT' (because @Enumerated STRING)
   - boolean → BOOLEAN
4. After save: user.getId() is now populated (Hibernate fills it)
```

---

## 13. DTOs — Data Transfer Objects

### Why Not Use Entities Directly?

```java
// ❌ DANGEROUS: Entity has passwordHash!
@PostMapping("/register")
public User register(@RequestBody User user) {
    return userRepository.save(user);
    // Response includes: {"passwordHash": "$2a$12$..."}  ← LEAKED!
}

// ✅ SAFE: DTO only has what the client needs
@PostMapping("/register")
public ApiResponse<AuthResponse> register(@RequestBody RegisterRequest request) {
    // RegisterRequest has: email, password, fullName (input)
    // AuthResponse has: accessToken, refreshToken, user profile (output)
    // NEVER exposes passwordHash
}
```

### Input DTO (What Client Sends)

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank @Size(min = 8, max = 100)
    private String password;

    @NotBlank @Size(min = 2, max = 100)
    private String fullName;

    private String role;  // Optional
}
```

### Output DTO (What Client Receives)

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuthResponse {
    private String accessToken;      // Client stores this
    private String refreshToken;     // Client stores this
    private long expiresIn;          // When to refresh (seconds)
    private UserProfileResponse user; // Basic user info
}
```

### The Pattern

```
Client sends JSON → RegisterRequest (Input DTO)
                         ↓
                    AuthService (converts DTO → Entity)
                         ↓
                    User (Entity — saved to DB)
                         ↓
                    AuthService (converts Entity → Response DTO)
                         ↓
Client receives JSON ← AuthResponse (Output DTO)
```

---

## 14. Configuration — application.yml

### What It Is

A YAML file where you configure your application (ports, DB URLs, secrets, etc.):

```yaml
# backend/identity-service/src/main/resources/application.yml
server:
  port: 8081                    # Which port to listen on

spring:
  application:
    name: identity-service      # Service name (for Eureka registration)
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_identity
    username: payflow
    password: payflow123
  jpa:
    hibernate:
      ddl-auto: validate        # Only validate schema, don't modify

jwt:
  secret: payflow-jwt-secret-key-must-be-at-least-256-bits-long
  access-token-expiration: 900000   # 15 minutes in milliseconds
```

### How to Read Values in Code

```java
@Service
public class JwtService {
    @Value("${jwt.secret}")                     // Reads "jwt.secret" from yml
    private String jwtSecret;                   // = "payflow-jwt-secret-key..."

    @Value("${jwt.access-token-expiration}")    // Reads the number
    private long accessTokenExpiration;          // = 900000
}
```

### Profile-Specific Configuration

```yaml
# application.yml         → used always (base config)
# application-docker.yml  → used when SPRING_PROFILES_ACTIVE=docker
# application-test.yml    → used when running tests
```

Activate a profile:
```bash
java -jar app.jar --spring.profiles.active=docker
# or environment variable:
SPRING_PROFILES_ACTIVE=docker
```

---

## 15. Validation — Rejecting Bad Input

### How It Works

```
1. Client sends JSON → Spring deserializes into DTO
2. @Valid triggers validation annotations on DTO fields
3. If ANY field fails → MethodArgumentNotValidException thrown
4. Exception handler catches → returns 400 with field errors
5. Controller code NEVER RUNS for invalid input
```

### Available Annotations

| Annotation | What It Checks | Example |
|---|---|---|
| `@NotBlank` | Not null, not empty, not whitespace | Rejects: null, "", "   " |
| `@NotNull` | Not null (empty string OK) | Rejects: null |
| `@Email` | Valid email format | Rejects: "abc", "@x" |
| `@Size(min, max)` | String/collection length | `@Size(min=8)` rejects "short" |
| `@Min(value)` | Number minimum | `@Min(0)` rejects -1 |
| `@Max(value)` | Number maximum | `@Max(100)` rejects 101 |
| `@Pattern(regexp)` | Regex match | `@Pattern(regexp="^[A-Z]+$")` |
| `@Positive` | Number > 0 | Rejects: 0, -5 |
| `@Future` | Date must be in future | Rejects past dates |

### Example Error Response

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [
      { "field": "email", "message": "Invalid email format", "rejectedValue": "notanemail" },
      { "field": "password", "message": "Password must be 8-100 characters", "rejectedValue": "short" }
    ]
  }
}
```

---

## 16. Exception Handling — @RestControllerAdvice

### The Problem

Without centralized handling, every controller needs try-catch:

```java
// ❌ BAD: try-catch in every method
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
    try {
        return ResponseEntity.ok(authService.register(request));
    } catch (DuplicateResourceException e) {
        return ResponseEntity.status(409).body(error);
    } catch (Exception e) {
        return ResponseEntity.status(500).body(error);
    }
}
```

### The Solution

```java
// ✅ GOOD: One handler catches ALL exceptions from ALL controllers
@RestControllerAdvice  // "Apply to all controllers in this service"
public class IdentityExceptionHandler {

    @ExceptionHandler(DuplicateResourceException.class)  // Catches this specific exception
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)  // 409
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)  // 401
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)  // Catch-all for unexpected errors
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)  // 500
                .body(ApiResponse.error(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred")));
        // NEVER expose stack traces to clients!
    }
}
```

### How It Works

```
AuthService throws DuplicateResourceException
    ↓
Exception bubbles through Controller (no try-catch needed)
    ↓
Spring intercepts it
    ↓
Finds matching @ExceptionHandler method in @RestControllerAdvice
    ↓
Calls handleDuplicate() → returns 409 response
    ↓
Client receives clean JSON error
```

---

## 17. Spring Security — Authentication & Authorization

### What It Does in PayFlow Identity Service

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
        // This bean is injected into AuthService for password hashing
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())     // No CSRF for stateless API
                .sessionManagement(session -> 
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/auth/**").permitAll()  // Public
                        .requestMatchers("/actuator/**").permitAll() // Health
                        .anyRequest().authenticated()                // Everything else
                )
                .build();
    }
}
```

### Key Concepts

| Concept | Meaning | PayFlow Usage |
|---|---|---|
| **Authentication** | "Who are you?" | Login with email + password |
| **Authorization** | "What can you do?" | Role-based: USER, MERCHANT, ADMIN |
| **PasswordEncoder** | Hash passwords (one-way) | BCrypt with cost factor 12 |
| **CSRF** | Cross-site request forgery protection | Disabled (stateless API with JWT) |
| **STATELESS** | No server-side sessions | Uses JWT tokens instead |
| **permitAll()** | No auth needed for this path | /v1/auth/login, /v1/auth/register |
| **authenticated()** | Must be authenticated | All other endpoints |

---

## 18. Spring Data JPA — ORM Made Easy

### What Is ORM?

**ORM = Object-Relational Mapping** — converts between Java objects and database rows.

```
WITHOUT ORM (raw JDBC):
  String sql = "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)";
  PreparedStatement stmt = connection.prepareStatement(sql);
  stmt.setString(1, UUID.randomUUID().toString());
  stmt.setString(2, "tejaswi@example.com");
  stmt.setString(3, "$2a$12$...");
  stmt.executeUpdate();

WITH ORM (Spring Data JPA):
  userRepository.save(User.builder().email("tejaswi@example.com").build());
  // JPA generates the SQL for you
```

### JPA vs Hibernate vs Spring Data JPA

| Layer | What | Role |
|---|---|---|
| **JPA** | Specification (interface) | Defines rules: @Entity, @Id, EntityManager |
| **Hibernate** | Implementation | Actually executes SQL, manages caching |
| **Spring Data JPA** | Abstraction on top | Generates repositories, method-name queries |

```
Your Code → Spring Data JPA → Hibernate → JDBC → PostgreSQL
             (generates impl)   (generates SQL)  (sends SQL)
```

---

## 19. Flyway — Database Migrations

### What Problem It Solves

```
WITHOUT Flyway:
  Developer A creates users table manually
  Developer B doesn't know → creates it differently
  Production deploy → table doesn't match code → CRASH

WITH Flyway:
  V1__create_users_table.sql  → everyone runs the same SQL
  V2__add_role_column.sql     → tracked, versioned, repeatable
  Deploy to production → Flyway runs only NEW migrations automatically
```

### How It Works

```
App starts → Flyway checks flyway_schema_history table:
  "Which migrations have already been applied?"
  → V1 applied ✓, V2 applied ✓, V3 NOT applied
  → Run V3__create_user_roles_table.sql
  → Record V3 in flyway_schema_history
  → Done. Hibernate validates entities match schema.
```

### Rules

```
V1__create_users_table.sql         ← Version 1
V2__create_roles_table.sql         ← Version 2
V3__create_user_roles_table.sql    ← Version 3
V4__create_refresh_tokens_table.sql ← Version 4

RULES:
✅ Files run in version order
❌ NEVER edit a migration after it's applied (create V5 to fix mistakes)
❌ NEVER delete a migration
❌ NEVER change the filename
```

---

## 20. Spring Cloud — Microservices Toolkit

Spring Cloud provides the glue between microservices:

| Component | What | Port | PayFlow Module |
|---|---|---|---|
| **Eureka** | Service Registry (find services by name) | 8761 | `service-registry` |
| **Config Server** | Centralized configuration | 8888 | `config-server` |
| **Gateway** | Single entry point + routing | 8080 | `api-gateway` |
| **LoadBalancer** | Distribute requests across instances | — | Built into Gateway |

### How They Connect

```
┌──────────┐ register ┌────────────┐
│ Identity │─────────▶│   Eureka   │
│ Service  │◀─────────│ (Registry) │
└──────────┘ discover └────────────┘
      ▲                      ▲
      │ fetch config         │ register
      ▼                      │
┌──────────┐          ┌─────┴──────┐
│  Config  │          │ API Gateway│
│  Server  │          │ (routes via│
└──────────┘          │  Eureka)   │
                      └────────────┘
```

---

## 21. Testing in Spring Boot

### Test Pyramid

```
           ┌─────────────┐
           │ Integration │  Few: slow, test full flow
           │   Tests     │
          ┌┴─────────────┴┐
          │  Controller    │  Some: test HTTP layer
          │   Tests        │
         ┌┴───────────────┴┐
         │   Unit Tests     │  Many: fast, isolated
         └─────────────────┘
```

### Unit Test (Mockito)

```java
@ExtendWith(MockitoExtension.class)  // Enable mocking
class AuthServiceTest {

    @Mock UserRepository userRepository;  // Fake DB
    @Mock JwtService jwtService;          // Fake JWT
    @InjectMocks AuthService authService; // Real service with fake deps

    @Test
    void register_Success() {
        // GIVEN: set up fake behavior
        when(userRepository.existsByEmail("test@x.com")).thenReturn(false);
        when(userRepository.save(any())).thenReturn(testUser);
        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("token");

        // WHEN: call the method under test
        AuthResponse response = authService.register(request);

        // THEN: verify results
        assertThat(response.getAccessToken()).isEqualTo("token");
        verify(userRepository).save(any());  // save() was called
    }
}
```

### Controller Test (@WebMvcTest)

```java
@WebMvcTest(AuthController.class)  // Only loads web layer
class AuthControllerTest {

    @Autowired MockMvc mockMvc;         // Simulates HTTP
    @MockBean AuthService authService;  // Fake service

    @Test
    void register_Returns201() throws Exception {
        when(authService.register(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@y.com\",\"password\":\"pass1234\",\"fullName\":\"Test\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.accessToken").value("jwt-token"));
    }
}
```

---

## 22. Lombok — Less Boilerplate

Lombok generates code at compile time so you don't write it:

| Annotation | What It Generates | Lines Saved |
|---|---|---|
| `@Data` | getters + setters + toString + equals + hashCode | ~50 lines |
| `@Builder` | Builder pattern | ~30 lines |
| `@NoArgsConstructor` | Empty constructor | 3 lines |
| `@AllArgsConstructor` | Constructor with all fields | 5+ lines |
| `@RequiredArgsConstructor` | Constructor for `final` fields only | 5+ lines |
| `@Getter` / `@Setter` | Just getters / setters | 2 lines per field |
| `@Slf4j` | Logger field | 1 line |

### Example

```java
// WITHOUT Lombok (70+ lines):
public class User {
    private String id;
    private String email;
    
    public User() {}
    public User(String id, String email) { this.id = id; this.email = email; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    @Override public String toString() { return "User(id=" + id + ", email=" + email + ")"; }
    @Override public int hashCode() { ... }
    @Override public boolean equals(Object o) { ... }
}

// WITH Lombok (3 lines):
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String id;
    private String email;
}
// Same functionality. Lombok generates all the methods at compile time.
```

---

## 23. How Spring Boot Starts (Boot Sequence)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    SPRING BOOT STARTUP SEQUENCE                           │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  1. JVM starts → main() method runs                                     │
│     └── SpringApplication.run(IdentityServiceApplication.class, args)   │
│                                                                          │
│  2. Spring creates ApplicationContext (the bean container)               │
│                                                                          │
│  3. Reads application.yml                                                │
│     └── port: 8081, datasource URL, jwt.secret, etc.                   │
│                                                                          │
│  4. (Optional) Contacts Config Server for additional properties          │
│     └── GET http://localhost:8888/identity-service/default              │
│                                                                          │
│  5. Component Scanning                                                   │
│     └── Finds: AuthController, AuthService, JwtService, SecurityConfig  │
│     └── Finds: UserRepository, RefreshTokenRepository                   │
│     └── Finds: IdentityExceptionHandler, UserMapper                     │
│                                                                          │
│  6. Auto-Configuration triggers based on dependencies                    │
│     ├── Found spring-data-jpa → Configure Hibernate + EntityManager     │
│     ├── Found postgresql → Configure DataSource + connection pool       │
│     ├── Found spring-security → Configure SecurityFilterChain           │
│     ├── Found flyway → Configure Flyway migration runner               │
│     └── Found actuator → Configure /actuator/health endpoint           │
│                                                                          │
│  7. Bean Creation (dependency order)                                     │
│     ├── PasswordEncoder (no deps)                                       │
│     ├── DataSource (needs yml properties)                               │
│     ├── EntityManagerFactory (needs DataSource)                          │
│     ├── UserRepository (needs EntityManager)                            │
│     ├── JwtService (needs @Value properties)                            │
│     ├── AuthService (needs UserRepo + JwtService + PasswordEncoder)     │
│     └── AuthController (needs AuthService)                              │
│                                                                          │
│  8. Flyway runs migrations                                               │
│     └── V1, V2, V3, V4 → creates tables in PostgreSQL                  │
│                                                                          │
│  9. Hibernate validates entities match DB schema                         │
│     └── User.java fields match 'users' table columns? ✓                │
│                                                                          │
│  10. Embedded Tomcat starts on port 8081                                 │
│                                                                          │
│  11. (Optional) Registers with Eureka: "I'm identity-service at :8081" │
│                                                                          │
│  12. LOG: "Started IdentityServiceApplication in 4.2 seconds"           │
│                                                                          │
│  13. Waiting for HTTP requests...                                        │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 24. Common Errors & Solutions

| Error | Cause | Solution |
|---|---|---|
| `No qualifying bean of type 'X'` | Spring can't find or create the bean | Check: Is the class annotated? Is it in the scanned package? |
| `Circular dependency` | A needs B, B needs A | Redesign — break the cycle with an interface or event |
| `Table 'users' doesn't exist` | Flyway didn't run / wrong DB | Check: Is PostgreSQL running? Database name correct? |
| `Column 'password_hash' not found` | Entity field doesn't match DB column | Use `@Column(name = "password_hash")` |
| `Failed to configure DataSource` | Can't connect to database | Check: PostgreSQL running? URL/username/password correct? |
| `Port already in use: 8081` | Another process on that port | Kill the process or change the port in yml |
| `Could not resolve placeholder 'jwt.secret'` | Missing property in yml | Add `jwt.secret: your-value` to application.yml |
| `@Valid not working` | Missing `spring-boot-starter-validation` | Add it to pom.xml dependencies |
| `401 on all requests` | Spring Security locks everything | Add `.permitAll()` for public paths in SecurityConfig |
| `Bean of type 'X' required but not found (test)` | Test doesn't load the bean | Use `@MockBean` for dependencies in `@WebMvcTest` |

---

## 25. Cheat Sheet — Quick Reference

### Annotation Cheat Sheet

```java
// REGISTER BEANS:
@Service           // Business logic class
@Repository        // Database access class  
@RestController    // HTTP handler class
@Configuration     // Bean factory class
@Component         // Generic (use specific ones above)

// DEFINE BEANS:
@Bean              // Method that returns a managed object

// INJECT DEPENDENCIES:
@RequiredArgsConstructor  // Constructor injection (Lombok)
@Value("${key}")          // Inject config property

// HTTP HANDLING:
@RequestMapping("/v1/auth")    // Base path
@GetMapping("/profile")        // GET endpoint
@PostMapping("/register")      // POST endpoint
@RequestBody                   // JSON body → Java object
@PathVariable                  // URL path variable
@RequestHeader                 // HTTP header value
@Valid                         // Trigger validation

// DATABASE:
@Entity              // Class = DB table
@Table(name = "x")   // Explicit table name
@Id                  // Primary key
@GeneratedValue      // Auto-generate value
@Column              // Column config (name, nullable, unique)
@Enumerated          // Enum storage type (STRING or ORDINAL)
@CreationTimestamp   // Auto-set on create
@Transactional       // Atomic DB operations

// ERROR HANDLING:
@RestControllerAdvice   // Global exception handler
@ExceptionHandler       // Catches specific exception type

// CONFIG:
@EnableWebSecurity     // Activate Spring Security
@EnableEurekaServer    // Activate Eureka Server
@EnableConfigServer    // Activate Config Server
```

### File Layout Cheat Sheet

```
src/main/java/com/payflow/identity/
├── IdentityServiceApplication.java     ← @SpringBootApplication (entry point)
├── config/
│   └── SecurityConfig.java             ← @Configuration (beans + security)
├── controller/
│   └── AuthController.java             ← @RestController (HTTP endpoints)
├── service/
│   ├── AuthService.java                ← @Service (business logic)
│   └── JwtService.java                 ← @Service (token handling)
├── repository/
│   ├── UserRepository.java             ← @Repository (DB queries)
│   └── RefreshTokenRepository.java     ← @Repository (DB queries)
├── model/
│   ├── User.java                       ← @Entity (DB table mapping)
│   ├── RefreshToken.java               ← @Entity
│   └── Role.java                       ← enum
├── dto/
│   ├── RegisterRequest.java            ← Input DTO (with validation)
│   ├── LoginRequest.java               ← Input DTO
│   ├── AuthResponse.java               ← Output DTO
│   └── UserProfileResponse.java        ← Output DTO
├── exception/
│   └── IdentityExceptionHandler.java   ← @RestControllerAdvice
└── mapper/
    └── UserMapper.java                 ← @Mapper (MapStruct)

src/main/resources/
├── application.yml                     ← Configuration
└── db/migration/
    ├── V1__create_users_table.sql      ← Flyway migration
    └── V4__create_refresh_tokens.sql   ← Flyway migration

src/test/java/com/payflow/identity/
├── service/AuthServiceTest.java        ← Unit test (@Mock)
└── controller/AuthControllerTest.java  ← Web test (@WebMvcTest)
```

### Request Flow Cheat Sheet

```
Client → API Gateway → Identity Service → Database
                            │
         ┌──────────────────┼──────────────────────┐
         ▼                  ▼                      ▼
    Controller         Service              Repository
    (@Valid +          (business             (SQL via
     delegate)          logic)               JPA)
         │                  │                      │
         ▼                  ▼                      ▼
    HTTP status        Exceptions            Entity objects
    + JSON             (caught by            (mapped to
    response           @RestControllerAdvice) DB rows)
```

---

## 🎯 Learning Path (Suggested Order)

| Step | Topic | Where You'll See It |
|---|---|---|
| 1 | Understand `@SpringBootApplication` + `main()` | `IdentityServiceApplication.java` |
| 2 | Read `application.yml` | Understand port, DB config |
| 3 | Look at an `@Entity` | `User.java` — how Java maps to DB |
| 4 | Look at a `@Repository` | `UserRepository.java` — zero SQL needed |
| 5 | Look at a `@Service` | `AuthService.java` — where logic lives |
| 6 | Look at a `@RestController` | `AuthController.java` — HTTP layer |
| 7 | Understand `@Valid` + DTOs | `RegisterRequest.java` — input validation |
| 8 | Understand `@RestControllerAdvice` | `IdentityExceptionHandler.java` — error formatting |
| 9 | Understand `@Configuration` + `@Bean` | `SecurityConfig.java` — password encoder |
| 10 | Run the tests | `AuthServiceTest.java` — see how mocking works |
| 11 | Start the app and test with curl | Verify everything works end-to-end |

---

*"Spring Boot does 90% of the work. Your job is the 10% that's unique to your business."*
