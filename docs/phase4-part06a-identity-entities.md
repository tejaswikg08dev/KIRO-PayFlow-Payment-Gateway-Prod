# 🏗️ Phase 4 Part 6a: Identity Service — Entities & Migrations

> **"Identity is the foundation of trust — every transaction begins with knowing who you are."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Previous** | [phase4-part05-api-gateway.md](./phase4-part05-api-gateway.md) |
| **Next** | [phase4-part06b-identity-jwt-auth.md](./phase4-part06b-identity-jwt-auth.md) |

---

## 📖 Table of Contents

1. [Overview & Purpose](#1-overview--purpose)
2. [Why Do We Need an Identity Service?](#2-why-do-we-need-an-identity-service)
3. [Entity Architecture](#3-entity-architecture)
4. [Step-by-Step: Role Enum](#4-step-by-step-role-enum)
5. [Step-by-Step: User Entity](#5-step-by-step-user-entity)
6. [Step-by-Step: RefreshToken Entity](#6-step-by-step-refreshtoken-entity)
7. [Step-by-Step: Repositories](#7-step-by-step-repositories)
8. [Step-by-Step: Flyway Migrations](#8-step-by-step-flyway-migrations)
9. [Step-by-Step: pom.xml Dependencies](#9-step-by-step-pomxml-dependencies)
10. [Step-by-Step: application.yml Configuration](#10-step-by-step-applicationyml-configuration)
11. [Concepts Deep Dive](#11-concepts-deep-dive)
12. [How to Verify Your Work](#12-how-to-verify-your-work)
13. [What You Learned](#13-what-you-learned)

---

## 1. Overview & Purpose

The Identity Service is the **authentication backbone** of PayFlow. Before anyone can make a payment, accept a payment, or manage the platform, they must first prove who they are. This service handles:

- **Registration** — Creating new user accounts
- **Authentication** — Verifying user credentials (login)
- **Token Management** — Issuing and refreshing JWT tokens
- **Profile Access** — Returning user information

**In this part (6a)**, we focus specifically on the **data layer** — the entities that represent users and tokens in the database, the migrations that create the tables, and the repositories that access the data.

**File Structure We'll Build:**

```
backend/identity-service/src/main/java/com/payflow/identity/
├── IdentityServiceApplication.java          ← Spring Boot entry point
├── model/
│   ├── Role.java                            ← Enum for user access levels
│   ├── User.java                            ← JPA entity for user accounts
│   └── RefreshToken.java                    ← JPA entity for refresh tokens
├── repository/
│   ├── UserRepository.java                  ← Data access for users
│   └── RefreshTokenRepository.java          ← Data access for refresh tokens
└── ...

backend/identity-service/src/main/resources/
├── application.yml                          ← Service configuration
└── db/migration/
    ├── V1__create_users_table.sql           ← Users table migration
    ├── V2__create_roles_table.sql           ← Roles reference table
    ├── V3__create_user_roles_table.sql      ← Many-to-many junction table
    └── V4__create_refresh_tokens_table.sql  ← Refresh tokens table
```

---

## 2. Why Do We Need an Identity Service?

### Real-World Analogy

Think of PayFlow like a bank:
- **Registration** = Opening a new bank account (they verify your identity, create your account)
- **Login** = Showing your ID at the counter (they verify who you are and give you a temporary access pass)
- **Access Token** = The temporary pass that lets you into secure areas (expires quickly — 15 minutes)
- **Refresh Token** = Your account number on file that lets you get a new pass without repeating the full identity check

### Why Separate It as a Microservice?

| Reason | Explanation |
|--------|-------------|
| **Single Responsibility** | Identity logic doesn't belong in the payment service or merchant service |
| **Security Isolation** | Password hashes and tokens are stored in a dedicated database — if another service is compromised, credentials remain safe |
| **Independent Scaling** | Login traffic spikes (e.g., sale events) can be handled by scaling only this service |
| **Reusability** | All other services delegate authentication to this single source of truth |

### User Types in PayFlow

| Role | Who They Are | What They Can Do |
|------|-------------|------------------|
| `USER` | End customer making payments | View own transactions, make payments |
| `MERCHANT` | Business owner accepting payments | Manage orders, view settlement reports, configure webhooks |
| `ADMIN` | Platform administrator | Full system access, manage all users and merchants |

---

## 3. Entity Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     IDENTITY SERVICE DATABASE                            │
│                     Database: payflow_identity                           │
│                     Port: 5432 (PostgreSQL)                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────────────────┐         ┌─────────────────────────────┐  │
│  │         users            │ 1    N  │       refresh_tokens         │  │
│  ├──────────────────────────┤─────────├─────────────────────────────┤  │
│  │ id        VARCHAR(36) PK │         │ id        VARCHAR(36) PK    │  │
│  │ email     VARCHAR(255) UQ│         │ token     VARCHAR(255) UQ   │  │
│  │ password_hash VARCHAR(255)│        │ user_id   VARCHAR(36) FK    │  │
│  │ full_name VARCHAR(100)   │         │ expires_at TIMESTAMP        │  │
│  │ role      VARCHAR(20)    │         │ revoked   BOOLEAN           │  │
│  │ active    BOOLEAN        │         │ created_at TIMESTAMP        │  │
│  │ created_at TIMESTAMP     │         └─────────────────────────────┘  │
│  │ updated_at TIMESTAMP     │                                          │
│  └──────────────────────────┘                                          │
│                                                                         │
│  Relationship: One User can have MANY RefreshTokens                     │
│  (one per login session / device)                                       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

**Why this structure?**
- A user logs in from their phone → gets RefreshToken_1
- Same user logs in from their laptop → gets RefreshToken_2
- Both sessions are tracked independently and can be revoked independently

---

## 4. Step-by-Step: Role Enum

**File:** `backend/identity-service/src/main/java/com/payflow/identity/model/Role.java`

### What It Does
Defines the three access levels available in PayFlow. This is a Java enum that gets stored as a text string in PostgreSQL.

### Why We Need It
Without roles, every user would have the same access. A payment customer shouldn't be able to access admin dashboards. Roles enable **authorization** (what you're allowed to do) as opposed to **authentication** (proving who you are).

### Implementation

```java
package com.payflow.identity.model;

/**
 * User roles for role-based access control.
 * 
 * WHY AN ENUM?
 * - Compile-time safety: You can't accidentally set role = "SUPERUSER"
 * - Limited values: Only these 3 roles exist in the system
 * - Type-safe comparisons: if (user.getRole() == Role.ADMIN) vs string comparison
 * 
 * WHY NOT A DATABASE TABLE?
 * - For 3 fixed values, a table adds joins without benefit
 * - These values won't change at runtime (they're code-level decisions)
 * - Note: V2 migration DOES create a roles table for future extensibility,
 *   but the enum is the source of truth in Java code
 */
public enum Role {
    USER,       // End customer who makes payments
    MERCHANT,   // Business owner who accepts payments
    ADMIN       // Platform administrator with full access
}
```

### Teaching Points

| Concept | Explanation |
|---------|-------------|
| `enum` | A Java type with a fixed set of constants. Unlike a String, the compiler catches typos. |
| Why 3 roles? | Minimum viable RBAC (Role-Based Access Control) for a payment gateway. |
| Extensibility | If you later need `SUPPORT_AGENT`, just add it to the enum and create a new migration. |

---

## 5. Step-by-Step: User Entity

**File:** `backend/identity-service/src/main/java/com/payflow/identity/model/User.java`

### What It Does
Maps a Java object to the `users` table in PostgreSQL. When you save a `User` object, Hibernate generates an INSERT statement. When you load it, Hibernate does a SELECT and populates the object.

### Why Each Field Exists

| Field | Why It's Here |
|-------|--------------|
| `id` | Unique identifier for every user. Used as foreign key in other tables. |
| `email` | Login identifier. Must be unique — two accounts with same email = disaster. |
| `passwordHash` | Stores BCrypt-encrypted password. NEVER stores plaintext. |
| `fullName` | Display name for the UI and receipts. |
| `role` | Controls what the user can access (USER/MERCHANT/ADMIN). |
| `active` | Soft-delete flag. Deactivated users can't log in but their data remains for auditing. |
| `createdAt` | Audit trail — when was this account created? |
| `updatedAt` | Audit trail — when was this account last modified? |

### Implementation

```java
package com.payflow.identity.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * JPA Entity representing a user in the system.
 * Maps to the "users" table in payflow_identity database.
 * 
 * DESIGN DECISIONS:
 * 1. UUID as String (VARCHAR(36)) — prevents enumeration attacks
 * 2. Lombok @Data — eliminates boilerplate getters/setters
 * 3. @Builder — enables clean object construction: User.builder().email("x").build()
 * 4. Soft-delete via 'active' flag — preserves payment history audit trail
 */
@Entity                          // ① Tells JPA: "this class = a database table"
@Table(name = "users")           // ② Explicit table name (avoids SQL reserved word conflicts)
@Data                            // ③ Lombok: generates getters, setters, toString, equals, hashCode
@Builder                         // ④ Lombok: User.builder().email("...").build()
@NoArgsConstructor               // ⑤ JPA REQUIRES a no-arg constructor (Hibernate uses it internally)
@AllArgsConstructor              // ⑥ Required for @Builder to work correctly
public class User {

    @Id                                              // This is the primary key
    @GeneratedValue(strategy = GenerationType.UUID)  // Hibernate auto-generates a UUID string
    private String id;
    // WHY String not java.util.UUID?
    // → Simpler JSON serialization (no custom converter needed)
    // → JPA stores it as VARCHAR(36) which maps directly

    @Column(nullable = false, unique = true)         // DB constraint: NOT NULL + UNIQUE
    private String email;
    // WHY unique?
    // → Email IS the login identifier. Two accounts with same email = which one logs in?
    // WHY indexed?
    // → Every login does: SELECT * FROM users WHERE email = ?
    //   Without an index, this scans ALL rows. With an index, it's instant.

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    // WHY "hash" not "password"?
    // → Making it explicit: we NEVER store plaintext passwords
    // → BCrypt output looks like: $2a$12$LJ3m4vG... (60 chars)
    // → Even if DB is stolen, attacker can't reverse the hash

    @Column(name = "full_name", nullable = false)
    private String fullName;
    // Used for display in UI and on payment receipts

    @Enumerated(EnumType.STRING)                     // Stores "USER"/"MERCHANT"/"ADMIN" as text
    @Column(nullable = false)
    private Role role;
    // WHY EnumType.STRING instead of EnumType.ORDINAL?
    // → ORDINAL stores 0, 1, 2 (position in enum)
    // → If you add a new role between USER and MERCHANT, all existing ORDINAL values shift!
    // → STRING stores "USER", "MERCHANT", "ADMIN" — safe to reorder

    @Column(nullable = false)
    @Builder.Default                                 // When using builder, defaults to true
    private boolean active = true;
    // WHY soft-delete instead of actual DELETE?
    // → A user with payment history can't just be deleted
    // → Regulations require audit trails
    // → Deactivating keeps data but blocks login

    @CreationTimestamp                               // Hibernate auto-sets on first persist
    @Column(name = "created_at", updatable = false)  // Can never be changed after creation
    private Instant createdAt;

    @UpdateTimestamp                                  // Hibernate auto-updates on every save
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

### Annotation Reference Card

| Annotation | What It Does | Why We Use It |
|------------|-------------|---------------|
| `@Entity` | Marks class as a JPA entity (maps to DB table) | Required for Hibernate to manage this class |
| `@Table(name = "users")` | Sets explicit table name | "user" is a reserved word in PostgreSQL |
| `@Id` | Marks the primary key field | Every entity must have exactly one @Id |
| `@GeneratedValue(strategy = GenerationType.UUID)` | Auto-generates UUID on persist | No sequential IDs that can be guessed |
| `@Column(nullable = false, unique = true)` | Adds DB constraints | Prevents null emails and duplicates at DB level |
| `@Enumerated(EnumType.STRING)` | Stores enum name as text | "MERCHANT" not "1" — safe against enum reordering |
| `@Builder.Default` | Sets default value when using Builder pattern | Without this, `User.builder().build()` sets active=false |
| `@CreationTimestamp` | Auto-sets timestamp on INSERT | No manual `setCreatedAt(Instant.now())` needed |
| `@UpdateTimestamp` | Auto-updates timestamp on every UPDATE | Tracks last modification automatically |
| `@Data` (Lombok) | Generates getters, setters, toString, equals, hashCode | Eliminates 50+ lines of boilerplate |
| `@Builder` (Lombok) | Generates builder pattern | Clean construction: `User.builder().email("x").build()` |
| `@NoArgsConstructor` (Lombok) | Generates no-arg constructor | JPA/Hibernate requires this internally |
| `@AllArgsConstructor` (Lombok) | Generates all-args constructor | Required for @Builder to compile |

---

## 6. Step-by-Step: RefreshToken Entity

**File:** `backend/identity-service/src/main/java/com/payflow/identity/model/RefreshToken.java`

### What It Does
Represents a refresh token stored in the database. When a user logs in, they receive both an access token (JWT) and a refresh token (opaque UUID). This entity tracks refresh tokens so they can be validated, revoked, and rotated.

### Why We Need Refresh Tokens

```
WITHOUT refresh tokens:
  User logs in → gets access token (15 min) → token expires → must enter password AGAIN
  
WITH refresh tokens:
  User logs in → gets access token (15 min) + refresh token (7 days)
  → access token expires → client sends refresh token → gets NEW access token
  → user stays logged in for 7 days without re-entering password
```

### Why Store Them in DB (Not as JWT)?

| Approach | Can Revoke? | Detects Theft? |
|----------|:-----------:|:--------------:|
| Refresh token as JWT | ❌ No (stateless) | ❌ No |
| Refresh token in DB (our approach) | ✅ Yes | ✅ Yes (reuse detection) |

If a refresh token is stolen, we can revoke it from the database. If someone reuses an already-revoked token, we know it was stolen and invalidate ALL tokens for that user.

### Implementation

```java
package com.payflow.identity.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * JPA Entity for refresh tokens.
 * Allows users to obtain new access tokens without re-entering credentials.
 * 
 * SECURITY DESIGN:
 * 1. Single-use: Each refresh token is revoked after one use (token rotation)
 * 2. Expiry: Tokens automatically expire after 7 days
 * 3. Reuse Detection: If a revoked token is used, ALL user tokens are invalidated
 *    (indicates the token was stolen)
 */
@Entity
@Table(name = "refresh_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    // Internal DB identifier — the client never sees this

    @Column(nullable = false, unique = true)
    private String token;
    // The actual token value sent to the client (a random UUID string)
    // WHY separate from 'id'?
    // → 'id' is the database primary key (internal)
    // → 'token' is what the client receives and sends back (external)
    // → Decouples API contract from DB structure

    @Column(name = "user_id", nullable = false)
    private String userId;
    // References users.id — stored as plain String, not a @ManyToOne relationship
    // WHY not @ManyToOne?
    // → Avoids lazy-loading complexity
    // → We only need the userId to look up the user separately
    // → Simpler queries and fewer JPA surprises

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    // Explicit expiry (7 days from creation)
    // WHY explicit expiry if we also revoke on use?
    // → Belt and suspenders: even if revocation logic has a bug, tokens still expire
    // → Allows DB cleanup job: DELETE WHERE expires_at < NOW() AND revoked = true

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;
    // WHY not just DELETE revoked tokens?
    // → We KEEP revoked tokens to DETECT REUSE
    // → If someone sends a revoked token, we know it was stolen
    // → This triggers invalidation of ALL tokens for that user

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
```

---

## 7. Step-by-Step: Repositories

### What Are Repositories?
Spring Data JPA repositories are interfaces that give you database operations for free. You declare method names following a naming convention, and Spring generates the SQL at runtime.

### UserRepository

**File:** `backend/identity-service/src/main/java/com/payflow/identity/repository/UserRepository.java`

```java
package com.payflow.identity.repository;

import com.payflow.identity.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Data access interface for User entities.
 * 
 * Spring Data JPA generates the implementation at runtime.
 * You just declare method names — Spring figures out the SQL.
 */
@Repository  // Marks this as a Spring-managed data access component
public interface UserRepository extends JpaRepository<User, String> {
    // JpaRepository<User, String> means:
    //   - Entity type: User
    //   - Primary key type: String (our UUID stored as VARCHAR)
    //   - We get for FREE: save(), findById(), findAll(), delete(), count(), etc.

    /**
     * Find user by email address (for login).
     * 
     * Generated SQL: SELECT * FROM users WHERE email = ?
     * Returns Optional because the user might not exist.
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if an email is already registered (for registration).
     * 
     * Generated SQL: SELECT COUNT(*) > 0 FROM users WHERE email = ?
     * More efficient than findByEmail when you just need true/false.
     */
    boolean existsByEmail(String email);
}
```

### RefreshTokenRepository

**File:** `backend/identity-service/src/main/java/com/payflow/identity/repository/RefreshTokenRepository.java`

```java
package com.payflow.identity.repository;

import com.payflow.identity.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    /**
     * Find a valid (non-revoked) refresh token.
     * Used during token refresh flow.
     * 
     * Generated SQL: SELECT * FROM refresh_tokens WHERE token = ? AND revoked = false
     * 
     * WHY "AndRevokedFalse"?
     * → We only want tokens that haven't been used yet
     * → A revoked token means it was already consumed or invalidated
     */
    Optional<RefreshToken> findByTokenAndRevokedFalse(String token);

    /**
     * Revoke ALL tokens for a user (security: called when theft is detected).
     * 
     * WHY @Modifying + @Query?
     * → Spring Data JPA can't generate UPDATE queries from method names
     * → We need custom JPQL for bulk operations
     * 
     * WHY bulk revoke?
     * → If token reuse is detected (theft), we must invalidate ALL sessions
     * → The user will need to log in again from all devices
     */
    @Modifying  // Tells Spring this modifies data (not just reads)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.userId = :userId")
    void revokeAllByUserId(String userId);
}
```

### How Spring Data JPA Method Names Work

```
findByEmail           → WHERE email = ?
findByEmailAndActive  → WHERE email = ? AND active = ?
existsByEmail         → SELECT COUNT(*) > 0 FROM ... WHERE email = ?
countByRole           → SELECT COUNT(*) FROM ... WHERE role = ?
findByTokenAndRevokedFalse → WHERE token = ? AND revoked = false
```

Spring parses the method name and generates the correct query. No SQL writing needed for simple cases.

---

## 8. Step-by-Step: Flyway Migrations

### What Is Flyway?
Flyway is a database migration tool. Instead of manually running SQL on your database, you write versioned SQL files and Flyway applies them automatically on application startup.

### Why Not Let Hibernate Create Tables?
| Approach | Problem |
|----------|---------|
| `ddl-auto: create` | **Destroys all data** on every restart |
| `ddl-auto: update` | Risky — might generate unexpected ALTER TABLE statements |
| `ddl-auto: validate` + Flyway | ✅ Flyway owns the schema, Hibernate only checks it matches |

### How Flyway Works

```
Application starts
    │
    ▼
Flyway checks `flyway_schema_history` table
    │
    ▼
"Which migrations have already been applied?"
    │
    ▼
Runs NEW migrations in order: V1 → V2 → V3 → V4
    │
    ▼
Records each migration in flyway_schema_history
    │
    ▼
Hibernate validates entities match the DB schema
    │
    ▼
Application is ready
```

### Migration V1: Users Table

**File:** `backend/identity-service/src/main/resources/db/migration/V1__create_users_table.sql`

```sql
-- ============================================================================
-- V1__create_users_table.sql
-- 
-- PURPOSE: Creates the core users table for authentication.
-- 
-- WHY THESE CHOICES:
-- • VARCHAR(36) for id: UUID stored as string (matches Java String id field)
-- • VARCHAR(255) for email: Allows longest possible email addresses
-- • VARCHAR(255) for password_hash: BCrypt produces 60-char hashes, but
--   future algorithms might be longer
-- • DEFAULT NOW(): Auto-populates timestamps if not provided by application
-- ============================================================================

CREATE TABLE users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- INDEX: Email lookup (every login does WHERE email = ?)
-- Without this, login scans ALL rows. With it, it's instant.
CREATE INDEX idx_users_email ON users(email);

-- INDEX: Role-based filtering (admin dashboard: "show me all merchants")
CREATE INDEX idx_users_role ON users(role);
```

### Migration V2: Roles Reference Table

**File:** `backend/identity-service/src/main/resources/db/migration/V2__create_roles_table.sql`

```sql
-- ============================================================================
-- V2__create_roles_table.sql
--
-- PURPOSE: Reference table for future role management extensibility.
-- Currently roles are stored as enum strings in the users table.
-- This table exists for when role management becomes more complex
-- (e.g., custom permissions per role, role descriptions for admin UI).
-- ============================================================================

CREATE TABLE roles (
    id SERIAL PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE,
    description VARCHAR(255)
);

-- Seed the standard roles
INSERT INTO roles (name, description) VALUES ('USER', 'Regular end-user');
INSERT INTO roles (name, description) VALUES ('MERCHANT', 'Merchant who accepts payments');
INSERT INTO roles (name, description) VALUES ('ADMIN', 'Platform administrator');
```

### Migration V3: User-Roles Junction Table

**File:** `backend/identity-service/src/main/resources/db/migration/V3__create_user_roles_table.sql`

```sql
-- ============================================================================
-- V3__create_user_roles_table.sql
--
-- PURPOSE: Many-to-many junction table for future expansion.
-- Currently each user has ONE role (in users.role column).
-- This table allows a user to have MULTIPLE roles in the future
-- (e.g., a merchant who is also an admin).
-- ============================================================================

CREATE TABLE user_roles (
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id INTEGER NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
-- ON DELETE CASCADE: If a user is deleted, their role assignments go too
-- Composite primary key: A user can't have the same role twice
```

### Migration V4: Refresh Tokens Table

**File:** `backend/identity-service/src/main/resources/db/migration/V4__create_refresh_tokens_table.sql`

```sql
-- ============================================================================
-- V4__create_refresh_tokens_table.sql
--
-- PURPOSE: Stores refresh tokens for the token rotation security pattern.
--
-- WHY THIS TABLE EXISTS:
-- Access tokens (JWTs) are stateless — we can validate them without DB lookup.
-- Refresh tokens MUST be stateful (in DB) because:
--   1. We need to REVOKE them (can't revoke a stateless JWT)
--   2. We need REUSE DETECTION (track which tokens have been consumed)
--   3. We need per-session tracking (one token per device/login)
-- ============================================================================

CREATE TABLE refresh_tokens (
    id VARCHAR(36) PRIMARY KEY,
    token VARCHAR(255) NOT NULL UNIQUE,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- INDEX: Token lookup (every refresh request does WHERE token = ?)
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);

-- INDEX: User lookup (bulk revocation does WHERE user_id = ?)
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
```

### Flyway Naming Rules

```
V1__create_users_table.sql
│ │  │
│ │  └── Description (underscores for spaces)
│ └── Double underscore separator (REQUIRED)
└── Version number (must be sequential)

RULES:
• Files run in version order: V1, V2, V3, V4
• NEVER edit a migration after it's been applied (create a new V5 instead)
• Flyway tracks which versions are done in `flyway_schema_history` table
• If you need to fix a mistake, create V5__fix_something.sql with ALTER TABLE
```

---

## 9. Step-by-Step: pom.xml Dependencies

**File:** `backend/identity-service/pom.xml`

### Why Each Dependency Exists

```xml
<dependencies>
    <!-- ═══ OUR SHARED LIBRARY ═══ -->
    <dependency>
        <groupId>com.payflow</groupId>
        <artifactId>common-lib</artifactId>
    </dependency>
    <!-- WHY: Shared DTOs (ApiResponse), exceptions (DuplicateResourceException),
         and utilities used across all microservices. Ensures consistency. -->

    <!-- ═══ SPRING BOOT STARTERS ═══ -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- WHY: Provides REST controller support, embedded Tomcat,
         Jackson JSON serialization. We're building a REST API. -->

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <!-- WHY: Provides Hibernate ORM + Spring Data repositories.
         Lets us use @Entity, JpaRepository, and talk to PostgreSQL
         without writing raw SQL. -->

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <!-- WHY: Provides PasswordEncoder (BCrypt) and SecurityFilterChain.
         We need BCrypt for hashing passwords securely. -->

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <!-- WHY: Enables @Valid, @NotBlank, @Email, @Size annotations.
         Validates incoming requests BEFORE they hit the service layer. -->

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <!-- WHY: Provides /actuator/health endpoint.
         Docker/Kubernetes uses this to check if the service is alive. -->

    <!-- ═══ SPRING CLOUD ═══ -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
    <!-- WHY: Registers this service with Eureka (Service Registry).
         API Gateway discovers us by name instead of hardcoded URL. -->

    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-config</artifactId>
    </dependency>
    <!-- WHY: Pulls configuration from Config Server.
         Secrets like JWT keys can be centrally managed. -->

    <!-- ═══ DATABASE ═══ -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <!-- WHY: JDBC driver to connect to PostgreSQL. Runtime-only because
         our code never imports PostgreSQL classes directly. -->

    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <!-- WHY: Database migration tool. Runs our V1/V2/V3/V4 SQL files
         on startup to create/update the schema. -->

    <!-- ═══ JWT ═══ -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <scope>runtime</scope>
    </dependency>
    <!-- WHY: JJWT library for creating and validating JWT tokens.
         Split into api/impl/jackson for clean dependency separation. -->

    <!-- ═══ MAPPING ═══ -->
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
    </dependency>
    <!-- WHY: Generates entity-to-DTO mapping code at compile time.
         Safer than manual mapping (compiler catches missing fields). -->

    <!-- ═══ DOCUMENTATION ═══ -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    </dependency>
    <!-- WHY: Auto-generates Swagger UI at /swagger-ui/index.html.
         Frontend developers can explore and test the API visually. -->

    <!-- ═══ TEST ═══ -->
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-test</artifactId>
        <scope>test</scope>
    </dependency>
    <!-- WHY: Test utilities for Spring Security (e.g., @WithMockUser). -->

    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>test</scope>
    </dependency>
    <!-- WHY: In-memory database for unit tests.
         Fast (no Docker needed), isolated (fresh DB per test). -->
</dependencies>
```

---

## 10. Step-by-Step: application.yml Configuration

**File:** `backend/identity-service/src/main/resources/application.yml`

```yaml
server:
  port: 8081
  # WHY 8081? Each microservice runs on a different port locally:
  # 8761 = Eureka, 8888 = Config Server, 8080 = API Gateway, 8081 = Identity

spring:
  application:
    name: identity-service
    # This name is used by:
    # 1. Eureka registration (other services find us by this name)
    # 2. Config Server (looks for identity-service.yml in config repo)

  config:
    import: optional:configserver:http://localhost:8888
    # "optional:" means don't crash if Config Server isn't running
    # In production, this pulls secrets from a centralized config store

  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_identity
    username: payflow
    password: payflow123
    # In production: use environment variables, NEVER commit real passwords
    # e.g., url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}

  jpa:
    hibernate:
      ddl-auto: validate
      # CRITICAL SETTING:
      # • "create" = DESTROYS all data on startup (never in production!)
      # • "update" = Risky auto-ALTER statements
      # • "validate" = Only CHECKS that entities match DB schema
      # • We use "validate" because FLYWAY manages the actual schema
    show-sql: false
    # Set to true temporarily for debugging SQL queries

  flyway:
    enabled: true
    baseline-on-migrate: true
    # baseline-on-migrate: If tables already exist (e.g., created manually),
    # Flyway treats the current state as the baseline and runs new migrations

jwt:
  secret: payflow-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256
  # MINIMUM 32 bytes for HMAC-SHA256. This dev key is 64 bytes.
  # In production: generate with `openssl rand -base64 64`
  access-token-expiration: 900000      # 15 minutes in milliseconds
  refresh-token-expiration: 604800000  # 7 days in milliseconds

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}
    # prefer-ip-address: Use IP instead of hostname for Docker compatibility
    # instance-id: Unique identifier when multiple instances run
```

---

## 11. Concepts Deep Dive

### UUID vs Auto-Increment IDs

```
┌─────────────────────────────────────────────────────────────────────┐
│                    WHY UUID FOR A PAYMENT SYSTEM?                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Auto-Increment:                UUID:                               │
│  /users/1                       /users/a1b2c3d4-e5f6-7890-...       │
│  /users/2   ← PREDICTABLE      /users/f7g8h9i0-j1k2-3456-...       │
│  /users/3                       /users/m3n4o5p6-q7r8-9012-...       │
│                                                                     │
│  Problems with auto-increment in a payment system:                  │
│  1. Enumeration: Attacker tries /users/1, /users/2, ... to find    │
│     valid accounts                                                  │
│  2. Information leak: /users/50000 reveals you have 50,000 users   │
│  3. Distributed systems: Two databases might generate the same ID  │
│                                                                     │
│  UUID advantages:                                                   │
│  1. Non-guessable: Can't enumerate users                           │
│  2. No information leak: ID reveals nothing about total users      │
│  3. Generate anywhere: No central ID authority needed               │
│  4. Merge-safe: Two databases will never have conflicting IDs      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Why BCrypt for Password Hashing?

```
Plain text:     "MyP@ssw0rd!"
After BCrypt:   "$2a$12$LJ3m4vGy7z..."  (60 characters, one-way)

WHY ONE-WAY?
→ Even if an attacker gets the entire database, they can't recover passwords
→ BCrypt is intentionally SLOW (cost factor 12 = ~400ms per hash)
→ An attacker trying 1 billion passwords would need ~12.7 YEARS

WHY NOT MD5 or SHA-256?
→ They're FAST (designed for file checksums, not passwords)
→ An attacker can try billions of MD5 hashes per second
→ BCrypt's slowness IS the security feature
```

### Soft Delete vs Hard Delete

```
HARD DELETE: DELETE FROM users WHERE id = 'abc123'
  → Data is GONE forever
  → Problem: What about their payment history? Transaction records?
  → Regulatory compliance requires audit trails

SOFT DELETE: UPDATE users SET active = false WHERE id = 'abc123'
  → Data remains, but user can't log in
  → Payment history preserved for auditing
  → Can reactivate if needed
  → Compliant with data retention regulations
```

---

## 12. How to Verify Your Work

### Prerequisites

1. **PostgreSQL** running on port 5432
2. Create the database:
   ```sql
   CREATE DATABASE payflow_identity;
   CREATE USER payflow WITH PASSWORD 'payflow123';
   GRANT ALL PRIVILEGES ON DATABASE payflow_identity TO payflow;
   ```
3. **Build common-lib first** (it's a dependency):
   ```bash
   cd backend
   mvn install -pl common-lib -am -DskipTests
   ```

### Run and Verify

```bash
cd backend/identity-service
mvn spring-boot:run
```

**Expected output:**
```
Flyway Community Edition ...
Successfully validated 4 migrations
Migrating schema "public" to version "1 - create users table"
Migrating schema "public" to version "2 - create roles table"
Migrating schema "public" to version "3 - create user roles table"
Migrating schema "public" to version "4 - create refresh tokens table"
...
Started IdentityServiceApplication in X seconds
```

### Verify Tables Were Created

```sql
-- Connect to payflow_identity database
\dt
-- Should show: users, roles, user_roles, refresh_tokens, flyway_schema_history

-- Check users table structure
\d users
-- Should show all columns with correct types
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | **UUID Primary Keys** | Prevents enumeration attacks; safe for distributed systems |
| 2 | **Role Enum with STRING mapping** | Human-readable in DB; safe against enum reordering |
| 3 | **Lombok @Data/@Builder** | Eliminates boilerplate; keeps entities clean and readable |
| 4 | **Refresh Token design** | Single-use tokens stored in DB enable revocation and theft detection |
| 5 | **Spring Data JPA repositories** | Declare method names → Spring generates SQL automatically |
| 6 | **Flyway migrations** | Versioned, sequential SQL files; never edit after deployment |
| 7 | **`ddl-auto: validate`** | Flyway owns the schema; Hibernate only validates it matches |
| 8 | **Index strategy** | Index columns you query frequently: email (login), token (refresh), user_id (revocation) |
| 9 | **Soft delete pattern** | `active = false` preserves audit trail; required for payment systems |
| 10 | **@Builder.Default** | Without it, Builder sets booleans to false instead of true |

---

## 📚 Document Index

| Document | Title |
|----------|-------|
| [Phase 4 Part 5](./phase4-part05-api-gateway.md) | API Gateway |
| **Phase 4 Part 6a** | **Identity Service — Entities & Migrations** (You are here) |
| [Phase 4 Part 6b](./phase4-part06b-identity-jwt-auth.md) | Identity Service — JWT & Authentication |
| [Phase 4 Part 6c](./phase4-part06c-identity-controller-tests.md) | Identity Service — Controller & Tests |
| [Phase 4 Part 7a](./phase4-part07a-merchant-entities.md) | Merchant Service — Entities |

---

## 🚀 Next Steps

In **[Phase 4 Part 6b](./phase4-part06b-identity-jwt-auth.md)**, we will implement:

1. `JwtService` — How JWT tokens are generated and validated
2. `AuthService` — The registration, login, and token refresh business logic
3. `SecurityConfig` — BCrypt password encoding and Spring Security setup
4. Token rotation — How we detect stolen tokens
