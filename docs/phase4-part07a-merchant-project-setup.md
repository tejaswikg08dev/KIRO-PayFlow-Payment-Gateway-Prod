# 🏗️ Phase 4 Part 7a: Merchant Service — Project Setup

> **"Before writing a single line of business logic, you need a project that can compile, connect, and run."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7a — Project Setup |
| **What You Build** | pom.xml, application.yml, MerchantServiceApplication.java, SecurityConfig.java |
| **Previous** | [Phase 4 Part 7 Overview](./phase4-part07-merchant-service-overview.md) |
| **Next** | [Phase 4 Part 7b — Entities](./phase4-part07b-merchant-entities.md) |

---

## 📖 Table of Contents

1. [What We Build and Why](#1-what-we-build-and-why)
2. [Step-by-Step: pom.xml (Dependencies)](#2-step-by-step-pomxml-dependencies)
3. [Step-by-Step: application.yml (Configuration)](#3-step-by-step-applicationyml-configuration)
4. [Step-by-Step: MerchantServiceApplication.java (Entry Point)](#4-step-by-step-merchantserviceapplicationjava-entry-point)
5. [Step-by-Step: SecurityConfig.java (Security)](#5-step-by-step-securityconfigjava-security)
6. [How These 4 Files Work Together](#6-how-these-4-files-work-together)
7. [Key Differences from Identity Service Setup](#7-key-differences-from-identity-service-setup)
8. [What You Learned](#8-what-you-learned)

---

## 1. What We Build and Why

Before you can write any entity, service, or controller, you need **4 foundation files**:

| File | What It Does | Analogy |
|---|---|---|
| `pom.xml` | Lists all libraries this project needs | A shopping list — "I need JPA, Security, PostgreSQL driver..." |
| `application.yml` | Configures the running application | A settings page — "Run on port 8082, connect to this database..." |
| `MerchantServiceApplication.java` | The main() method — starts everything | The power button — "Turn on the application" |
| `SecurityConfig.java` | Controls who can access what | The security guard — "Let these people through, stop the rest" |

### Folder Structure After This Part

```
backend/merchant-service/
├── pom.xml                                              ← YOU CREATE THIS
└── src/main/
    ├── java/com/payflow/merchant/
    │   ├── MerchantServiceApplication.java              ← YOU CREATE THIS
    │   └── config/
    │       └── SecurityConfig.java                      ← YOU CREATE THIS
    └── resources/
        └── application.yml                              ← YOU CREATE THIS
```

---

## 2. Step-by-Step: pom.xml (Dependencies)

**File:** `backend/merchant-service/pom.xml`

This file tells Maven: "Here are all the libraries I need. Download them and add them to my project."

### Line-by-Line

```xml
<?xml version="1.0" encoding="UTF-8"?>
```
Every XML file starts with this declaration. It tells the parser "this is XML version 1.0, encoded in UTF-8."

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
```
Standard Maven boilerplate. Every pom.xml has this. `modelVersion 4.0.0` is the only version that exists — don't change it.

```xml
    <parent>
        <groupId>com.payflow</groupId>
        <artifactId>payflow-payment-gateway</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
```

**WHAT:** This merchant-service POM **inherits** from the parent POM (`backend/pom.xml`).

**WHY:** The parent POM defines:
- Java 17 version
- All library versions (Spring Boot 3.2.5, JJWT 0.12.5, MapStruct 1.5.5, etc.)
- Lombok annotation processor config
- Spring Boot test dependency

Without this, you'd have to repeat all version numbers in every service's pom.xml. With it, you write `spring-boot-starter-web` without a version — the parent provides it.

**HOW IT WORKS:**
```
Parent POM (backend/pom.xml):
  <dependencyManagement>
    <spring-boot-starter-web version="3.2.5">   ← Version defined HERE
  </dependencyManagement>

Child POM (merchant-service/pom.xml):
  <dependency>
    <spring-boot-starter-web>                    ← No version needed!
  </dependency>                                     Parent provides it.
```

```xml
    <artifactId>merchant-service</artifactId>
    <name>PayFlow Merchant Service</name>
    <description>Merchant onboarding, API key management, webhook configuration, and fee setup</description>
```

**WHAT:** The unique identifier for THIS module within the PayFlow project.

- `artifactId` = the module name (used by Maven to identify this project)
- `name` = human-readable name (displayed in IDE and build logs)
- `description` = what this module does (documentation only)

**NOTE:** We don't specify `groupId` or `version` — they're inherited from the parent (`com.payflow`, `1.0.0-SNAPSHOT`).

```xml
    <dependencies>
```
Everything inside `<dependencies>` is added to this module's classpath. Let's go through each one:

---

### Dependency #1: common-lib

```xml
        <dependency>
            <groupId>com.payflow</groupId>
            <artifactId>common-lib</artifactId>
        </dependency>
```

**WHAT:** Our shared library (built in Phase 4 Part 02).

**WHY:** Contains:
- `ApiResponse<T>` — the standard response wrapper (`{ "success": true, "data": {...} }`)
- `ErrorResponse` — structured error format
- `DuplicateResourceException` — thrown when email already exists (→ 409)
- `ResourceNotFoundException` — thrown when merchant not found (→ 404)

**WITHOUT THIS:** You'd duplicate these classes in every microservice.

---

### Dependency #2: spring-boot-starter-web

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
```

**WHAT:** A "starter" — a bundle of related libraries. This one gives you:
- **Embedded Tomcat** — web server that runs inside your app (no external server needed)
- **Spring MVC** — `@RestController`, `@GetMapping`, `@PostMapping`, etc.
- **Jackson** — converts Java objects ↔ JSON automatically
- **Error handling** — default error responses

**WHY:** We're building a REST API. This is the absolute minimum dependency for any Spring Boot web service.

**WHAT IS A "STARTER"?**
```
spring-boot-starter-web is NOT one library. It bundles:
├── spring-web          (HTTP handling)
├── spring-webmvc       (MVC annotations)
├── jackson-databind    (JSON serialization)
├── tomcat-embed-core   (embedded web server)
├── tomcat-embed-el     (expression language)
└── spring-boot-starter (core Spring Boot)

Instead of adding 6 dependencies, you add 1 starter.
```

---

### Dependency #3: spring-boot-starter-data-jpa

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
```

**WHAT:** ORM (Object-Relational Mapping) support. Bundles:
- **Hibernate** — the actual ORM engine (converts Java objects ↔ SQL)
- **Spring Data JPA** — `JpaRepository`, auto-generated queries from method names
- **HikariCP** — database connection pool (manages DB connections efficiently)
- **Jakarta Persistence API** — `@Entity`, `@Table`, `@Id`, `@Column` annotations

**WHY:** We have 4 database tables (merchants, api_keys, webhook_configs, fee_configs). Instead of writing raw SQL everywhere, we use JPA entities and repositories.

**WITHOUT THIS:**
```java
// Without JPA (raw JDBC):
String sql = "SELECT * FROM merchants WHERE email = ?";
PreparedStatement stmt = connection.prepareStatement(sql);
stmt.setString(1, "test@example.com");
ResultSet rs = stmt.executeQuery();
Merchant merchant = new Merchant();
merchant.setId(UUID.fromString(rs.getString("id")));
merchant.setName(rs.getString("name"));
// ... 20 more lines for each query

// With JPA:
Optional<Merchant> merchant = merchantRepository.findByEmail("test@example.com");
// That's it. ONE line.
```

---

### Dependency #4: spring-boot-starter-security

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
```

**WHAT:** Spring Security framework.

**WHY:** Even though we `permitAll()` on most endpoints (JWT auth happens at the API Gateway), we still need this because:
1. **CSRF protection** must be explicitly disabled for REST APIs
2. **Session management** must be set to STATELESS (no HttpSession)
3. **Default Spring Security** locks down ALL endpoints — without SecurityConfig, nothing works

**IMPORTANT:** Without this dependency + SecurityConfig, Spring Boot auto-configures security with a random password — every request returns 401.

---

### Dependency #5: spring-boot-starter-validation

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
```

**WHAT:** Jakarta Bean Validation (Hibernate Validator implementation).

**WHY:** Enables annotations like `@NotBlank`, `@Email`, `@Positive`, `@URL`, `@NotEmpty` on DTOs. When combined with `@Valid` in the controller, Spring automatically rejects bad input before it reaches the service layer.

**WITHOUT THIS:** `@Valid` does nothing — bad data passes straight through to your business logic.

---

### Dependency #6: spring-boot-starter-actuator

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

**WHAT:** Production-ready monitoring endpoints.

**WHY:** Provides `/actuator/health` — the endpoint Docker and Kubernetes use to check if your service is alive.

```
Docker HEALTHCHECK → GET /actuator/health
If response = {"status": "UP"}  → container is healthy
If no response or DOWN          → container is unhealthy → restart
```

---

### Dependency #7-8: Eureka Client + Config Client

```xml
        <!-- Eureka Client -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <!-- Config Client -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
```

**WHAT:**
- **Eureka Client** — registers this service with the Service Registry (port 8761)
- **Config Client** — fetches configuration from Config Server (port 8888)

**WHY:**
- **Eureka:** API Gateway uses `lb://merchant-service` to find us. Without Eureka registration, the Gateway can't route requests to us.
- **Config:** In production, database passwords and secrets come from Config Server instead of being hardcoded in application.yml.

---

### Dependency #9-10: PostgreSQL + Flyway

```xml
        <!-- PostgreSQL -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- Flyway -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
```

**WHAT:**
- `postgresql` — JDBC driver (talks to PostgreSQL over TCP)
- `flyway-core` + `flyway-database-postgresql` — database migration tool

**WHY scope="runtime"?**
The PostgreSQL driver is only needed when the app RUNS (not when compiling). Your Java code never imports `org.postgresql.*` — Hibernate uses it internally via JDBC.

**WHY two Flyway dependencies?**
- `flyway-core` = the migration engine
- `flyway-database-postgresql` = PostgreSQL-specific support (since Flyway 10+, database dialects are separate modules)

---

### Dependency #11: MapStruct

```xml
        <!-- MapStruct -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
        </dependency>
```

**WHAT:** Compile-time code generator for entity ↔ DTO mapping.

**WHY:** Instead of manually writing `response.setName(merchant.getName())` for every field, MapStruct generates that code automatically. Safer (compiler catches missing fields) and zero runtime overhead (code is generated at compile time, not reflection).

---

### Dependency #12: SpringDoc OpenAPI

```xml
        <!-- OpenAPI -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
```

**WHAT:** Auto-generates Swagger UI from your `@RestController` annotations.

**WHY:** After building the controller, you can open `http://localhost:8082/swagger-ui.html` and see all 14 endpoints with request/response schemas — no manual documentation needed. Frontend developers use this to understand the API.

---

### Dependency #13-14: Test Dependencies

```xml
        <!-- Test -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

**WHAT:**
- `spring-security-test` — test utilities for Spring Security (e.g., `@WithMockUser`)
- `h2` — in-memory database for tests (no PostgreSQL needed during `mvn test`)

**WHY scope="test"?** These are ONLY available during testing. They're not included in the final JAR.

---

### Build Section

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
```

**WHAT:** The Spring Boot Maven plugin packages the app as an executable JAR (fat JAR) with embedded Tomcat.

**WHY:** Without this, `mvn package` produces a regular JAR that can't run on its own. With this, you get a JAR that includes ALL dependencies — just `java -jar app.jar` to run.

**COMPARE TO common-lib:** In common-lib's pom.xml, this plugin has `<skip>true</skip>` because common-lib is a library (used by other modules), not a runnable application.

---

### What's NOT Here (Compared to Identity Service)

| Identity Service Has | Merchant Service Does NOT | Why |
|---|---|---|
| `jjwt-api`, `jjwt-impl`, `jjwt-jackson` | ❌ No JJWT | Merchant doesn't generate/validate JWTs — that's Identity's job |

The Merchant Service uses JDK's built-in `MessageDigest` (SHA-256) and `SecureRandom` for API key security — no third-party libraries needed.

---

## 3. Step-by-Step: application.yml (Configuration)

**File:** `backend/merchant-service/src/main/resources/application.yml`

### Line-by-Line

```yaml
server:
  port: 8082
```

**WHAT:** This service listens on port 8082.

**WHY 8082?** Each microservice runs on a different port locally:
| Service | Port |
|---|---|
| API Gateway | 8080 |
| Identity | 8081 |
| **Merchant** | **8082** |
| Payment | 8083 |
| Settlement | 8085 |

In production (Docker/Kubernetes), ports don't matter because services are accessed by name via Eureka.

```yaml
spring:
  application:
    name: merchant-service
```

**WHAT:** The logical name of this service.

**WHY:** Used in TWO places:
1. **Eureka registration** — appears as "MERCHANT-SERVICE" in the dashboard
2. **Config Server** — when this service asks for config, Config Server looks for `merchant-service.yml`

**If you change this name:**
- Eureka shows a different name
- API Gateway's `lb://merchant-service` route stops working
- Config Server serves the wrong configuration file

```yaml
  config:
    import: optional:configserver:http://localhost:8888
```

**WHAT:** "On startup, fetch configuration from Config Server at port 8888."

**WHY `optional:`?** If Config Server isn't running, the service uses local application.yml values instead of crashing. This is essential for local development where you might not run all infrastructure services.

**WHAT HAPPENS:**
```
App starts → Tries GET http://localhost:8888/merchant-service/default
  → Config Server responds? → Merge remote config with local (remote wins)
  → Config Server unreachable? → Use local application.yml only (no crash)
```

```yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_merchant
    username: payflow
    password: payflow123
```

**WHAT:** Database connection details.

**EACH PIECE EXPLAINED:**
| Part | Meaning |
|---|---|
| `jdbc:postgresql://` | Protocol — "I'm connecting to PostgreSQL via JDBC" |
| `localhost` | Database server hostname (same machine) |
| `5432` | PostgreSQL default port |
| `payflow_merchant` | Database name (DIFFERENT from identity's `payflow_identity`!) |
| `username: payflow` | DB user (created in init-db.sql) |
| `password: payflow123` | DB password (in production, comes from Config Server/env vars) |

**CRITICAL:** Each microservice has its own database. This is the "database per service" pattern. Merchant Service NEVER touches the `payflow_identity` database.

```yaml
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

**WHAT:** JPA/Hibernate configuration.

| Property | Value | Meaning |
|---|---|---|
| `ddl-auto: validate` | Validate | Hibernate checks entities match DB schema but NEVER creates/modifies tables. Flyway handles that. |
| `show-sql: false` | Off | Don't print SQL queries to console (set to `true` for debugging) |
| `dialect: PostgreSQLDialect` | PostgreSQL | Tells Hibernate which SQL dialect to generate |

**WHY `validate` NOT `create` or `update`?**
- `create` → **DROPS ALL TABLES** on every restart (destroys data!)
- `update` → Auto-modifies tables (risky, unpredictable)
- `validate` → Only checks. If entities don't match DB → startup fails with clear error
- Flyway is the single source of truth for database schema changes

```yaml
  flyway:
    enabled: true
    baseline-on-migrate: true
```

**WHAT:** Flyway migration configuration.

| Property | Meaning |
|---|---|
| `enabled: true` | Run migrations on startup |
| `baseline-on-migrate: true` | If the database already has tables (but no Flyway history), treat current state as baseline and continue |

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}
```

**WHAT:** Eureka registration settings.

| Property | Meaning |
|---|---|
| `defaultZone` | Where to find the Eureka Server |
| `prefer-ip-address: true` | Register with IP (e.g., 192.168.1.5) instead of hostname — better for Docker |
| `instance-id` | Unique identifier: "merchant-service:8082" — prevents collision if multiple instances run |

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
```

**WHAT:** Swagger UI configuration.

After starting the service, open `http://localhost:8082/swagger-ui.html` to see all API endpoints visually. `api-docs` path exposes the raw OpenAPI spec (JSON) at `/v3/api-docs`.

---

## 4. Step-by-Step: MerchantServiceApplication.java (Entry Point)

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/MerchantServiceApplication.java`

### Line-by-Line

```java
package com.payflow.merchant;
```

**WHAT:** Declares this class is in the `com.payflow.merchant` package.

**WHY THIS PACKAGE?** Spring Boot's `@ComponentScan` (included in `@SpringBootApplication`) scans THIS package and all sub-packages. So:
- `com.payflow.merchant.service.MerchantService` → ✅ found
- `com.payflow.merchant.controller.MerchantController` → ✅ found
- `com.payflow.identity.service.AuthService` → ❌ NOT found (different base package)

```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
```

Three imports for three annotations/classes we use.

```java
@SpringBootApplication
```

**WHAT:** This ONE annotation does THREE things:

| Hidden Annotation | What It Does |
|---|---|
| `@Configuration` | "This class can define @Bean methods" |
| `@EnableAutoConfiguration` | "Spring, look at my pom.xml and auto-configure everything you find" |
| `@ComponentScan` | "Scan `com.payflow.merchant` and all sub-packages for @Component/@Service/@Repository/@RestController" |

**AUTO-CONFIGURATION IN ACTION:**
```
Spring sees spring-boot-starter-data-jpa in pom.xml
  → Auto-configures: DataSource, EntityManagerFactory, TransactionManager
Spring sees postgresql driver in pom.xml
  → Auto-configures: PostgreSQL DataSource using application.yml settings
Spring sees spring-boot-starter-security in pom.xml
  → Auto-configures: default SecurityFilterChain (we override in SecurityConfig)
Spring sees flyway-core in pom.xml
  → Auto-configures: Flyway runner that executes V1-V4 migrations on startup
```

```java
@EnableDiscoveryClient
```

**WHAT:** Explicitly enables Eureka client registration.

**WHY EXPLICIT HERE?** In the Identity Service, we didn't use this annotation — Spring Cloud auto-detected the Eureka client dependency. Both approaches work. The `@EnableDiscoveryClient` is more explicit, making it clear this service registers with Eureka.

| Approach | Identity Service | Merchant Service |
|---|---|---|
| Annotation | None (auto-detected) | `@EnableDiscoveryClient` |
| Result | Same — both register with Eureka | Same |
| When to use | When you want less code | When you want explicit intent |

```java
public class MerchantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantServiceApplication.class, args);
    }
}
```

**WHAT:** The entry point. When you run `mvn spring-boot:run` or `java -jar app.jar`, Java calls this `main()` method.

**WHAT `SpringApplication.run()` DOES:**
1. Creates the Spring ApplicationContext (bean container)
2. Reads application.yml
3. Contacts Config Server (optional)
4. Scans for annotated classes (@Service, @Repository, etc.)
5. Creates all beans in dependency order
6. Runs Flyway migrations (V1 → V2 → V3 → V4)
7. Hibernate validates entities match DB schema
8. Starts embedded Tomcat on port 8082
9. Registers with Eureka
10. Prints: "Started MerchantServiceApplication in X seconds"

---

## 5. Step-by-Step: SecurityConfig.java (Security)

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/config/SecurityConfig.java`

### Line-by-Line

```java
package com.payflow.merchant.config;
```

The `config` sub-package — convention for all configuration classes.

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
```

All the Spring Security classes we need.

```java
/**
 * Spring Security configuration for merchant-service.
 * Stateless (no sessions) — relies on JWT from API Gateway for authentication.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
```

**`@Configuration`:**
"This class provides `@Bean` methods." Spring will call the methods below and manage the returned objects.

**`@EnableWebSecurity`:**
"Activate Spring Security's web protection." Without this, Spring Security doesn't apply any filter chain.

**IMPORTANT:** `@EnableWebSecurity` (servlet/Tomcat) — used here because merchant-service runs on Tomcat. The API Gateway uses `@EnableWebFluxSecurity` (reactive/Netty) — they're DIFFERENT annotations for different runtimes.

```java
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
```

**`@Bean`:** "Spring, call this method once at startup and manage the returned SecurityFilterChain."

**`HttpSecurity http`:** Spring passes this builder in. We configure it and return the built chain.

```java
        return http
                .csrf(csrf -> csrf.disable())
```

**WHAT:** Disable CSRF (Cross-Site Request Forgery) protection.

**WHY DISABLE?**
- CSRF protection is for browser-based forms that use cookies
- We're a stateless REST API using JWT tokens in the `Authorization` header
- No cookies = no CSRF vulnerability
- If we LEFT it enabled, every POST/PUT/DELETE request would fail with 403 Forbidden

```java
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

**WHAT:** Tell Spring to NEVER create an HttpSession.

**WHY STATELESS?**
- We use JWT tokens (stateless authentication)
- Server doesn't need to remember who's logged in (no session cookies)
- This enables horizontal scaling — any server instance can handle any request
- Saves memory (no session objects stored on server)

```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/merchants/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated()
                )
```

**LINE BY LINE:**

| Rule | Meaning |
|---|---|
| `/v1/merchants/**` → `permitAll()` | All merchant endpoints are publicly accessible (Gateway handles auth) |
| `/actuator/**` → `permitAll()` | Health check endpoints accessible (Docker needs these) |
| `/swagger-ui/**`, `/v3/api-docs/**` → `permitAll()` | API documentation accessible |
| `anyRequest().authenticated()` | Anything else requires authentication |

**WHY `permitAll()` IF THERE'S NO AUTH?**
JWT validation happens at the **API Gateway** level, not in individual services. The Gateway validates the JWT, extracts userId/role, and passes them as `X-User-Id` / `X-User-Role` headers. This service just reads those headers — it doesn't need its own auth logic.

**WHY NOT `anyRequest().permitAll()` FOR EVERYTHING?**
Defense in depth. If someone bypasses the Gateway and hits this service directly, unexpected endpoints are still protected.

```java
                .build();
    }
}
```

**`.build()`:** Finalize the configuration and return the `SecurityFilterChain` object.

### No PasswordEncoder — Why?

In Identity Service's SecurityConfig, we had:
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
}
```

Merchant Service does NOT have this. Why?
- **Identity Service:** Users have passwords → need BCrypt to hash/verify them
- **Merchant Service:** Merchants have API keys → use SHA-256 (not BCrypt) — and that's done in `ApiKeyService`, not in Spring Security

---

## 6. How These 4 Files Work Together

```
┌───────────────────────────────────────────────────────────────────────────┐
│                    STARTUP FLOW                                           │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  1. You run: mvn spring-boot:run                                         │
│                                                                           │
│  2. Java finds MerchantServiceApplication.main()                         │
│     → @SpringBootApplication triggers:                                   │
│                                                                           │
│  3. Reads pom.xml dependencies (already resolved by Maven)               │
│     → Knows: "I have JPA, Security, Flyway, PostgreSQL, Eureka, etc."   │
│                                                                           │
│  4. Reads application.yml                                                │
│     → Knows: port=8082, DB=payflow_merchant, eureka=localhost:8761      │
│                                                                           │
│  5. (Optional) Contacts Config Server                                    │
│     → GET http://localhost:8888/merchant-service/default                 │
│     → Merges remote config if available                                  │
│                                                                           │
│  6. Component scanning finds SecurityConfig.java                         │
│     → Creates SecurityFilterChain bean                                   │
│     → Applies: CSRF=off, STATELESS, permitAll for /v1/merchants/**      │
│                                                                           │
│  7. Auto-configuration based on dependencies:                            │
│     → Creates DataSource (PostgreSQL connection pool)                    │
│     → Runs Flyway migrations (V1→V4, creates tables)                    │
│     → Creates EntityManagerFactory (Hibernate)                           │
│     → Validates entities match DB schema                                 │
│                                                                           │
│  8. Starts Tomcat on port 8082                                           │
│  9. Registers with Eureka: "I'm merchant-service at :8082"              │
│  10. Prints: "Started MerchantServiceApplication in X seconds"          │
│                                                                           │
│  → Ready for HTTP requests!                                              │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Key Differences from Identity Service Setup

| Feature | Identity Service | Merchant Service |
|---|---|---|
| **Port** | 8081 | 8082 |
| **Database** | payflow_identity | payflow_merchant |
| **Eureka annotation** | None (auto-detected) | `@EnableDiscoveryClient` (explicit) |
| **PasswordEncoder bean** | ✅ BCrypt (for password hashing) | ❌ None (API keys use SHA-256 in service code) |
| **JJWT dependencies** | ✅ (generates/validates JWTs) | ❌ (doesn't touch JWTs) |
| **Swagger config** | Default path | Explicit `springdoc` section in yml |
| **Hibernate dialect** | Not specified (auto-detected) | Explicit `PostgreSQLDialect` |
| **Everything else** | Same pattern | Same pattern |

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Parent POM inheritance** | Child modules inherit versions from parent — no version numbers needed in dependencies |
| 2 | **Starter bundles** | `spring-boot-starter-web` = Tomcat + MVC + Jackson in one dependency |
| 3 | **scope=runtime** | PostgreSQL driver is only needed at runtime, not compile time |
| 4 | **scope=test** | H2 and security-test only available during `mvn test` |
| 5 | **spring-boot-maven-plugin** | Creates executable fat JAR (vs common-lib which skips this) |
| 6 | **application.yml structure** | server.port, spring.datasource, spring.jpa, eureka, springdoc sections |
| 7 | **optional:configserver:** | Service doesn't crash if Config Server is down |
| 8 | **ddl-auto: validate** | Hibernate validates only — Flyway owns the schema |
| 9 | **@SpringBootApplication** | = @Configuration + @EnableAutoConfiguration + @ComponentScan |
| 10 | **@EnableDiscoveryClient** | Explicit Eureka registration (vs auto-detected in Identity) |
| 11 | **CSRF disable** | Required for stateless REST APIs (no cookies = no CSRF risk) |
| 12 | **STATELESS sessions** | No HttpSession — JWT tokens replace sessions |
| 13 | **permitAll + authenticated** | Public paths for Gateway-routed requests, protected for direct access |
| 14 | **No PasswordEncoder** | Merchant Service uses SHA-256 for API keys, not BCrypt for passwords |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part07-merchant-service-overview.md) | Merchant Service Overview & Roadmap |
| **Part 7a** | **Project Setup** (You are here) |
| [Part 7b](./phase4-part07b-merchant-entities.md) | Entities (Data Models) |
| [Part 7c](./phase4-part07c-merchant-migrations.md) | Flyway Migrations (Database Tables) |
| [Part 7d](./phase4-part07d-merchant-repositories.md) | Repositories (Data Access) |
| [Part 7e](./phase4-part07e-merchant-dtos-mapper.md) | DTOs + Mapper (Input/Output) |
| [Part 7f](./phase4-part07f-merchant-services-tests.md) | Services + Tests (Business Logic) |
| [Part 7g](./phase4-part07g-merchant-controller-docker.md) | Controller + Docker (HTTP + Deploy) |

---

*Next: [Part 7b — Entities (Data Models)](./phase4-part07b-merchant-entities.md) →*
