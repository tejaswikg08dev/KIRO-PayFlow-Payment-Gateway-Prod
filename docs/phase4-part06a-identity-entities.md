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

1. [Overview](#1-overview)
2. [Entity Architecture](#2-entity-architecture)
3. [Role Enum](#3-role-enum)
4. [User Entity](#4-user-entity)
5. [RefreshToken Entity](#5-refreshtoken-entity)
6. [Entity Relationship Diagram](#6-entity-relationship-diagram)
7. [Flyway Migration V1 — Users Table](#7-flyway-migration-v1--users-table)
8. [Flyway Migration V2 — Refresh Tokens Table](#8-flyway-migration-v2--refresh-tokens-table)
9. [JPA Annotations Deep Dive](#9-jpa-annotations-deep-dive)
10. [Configuration & Dependencies](#10-configuration--dependencies)
11. [What You Learned](#11-what-you-learned)

---

## 1. Overview

The Identity Service is responsible for user registration, authentication, and authorization
across the entire PayFlow ecosystem. It manages three types of users:

| Role | Description | Access Level |
|------|-------------|--------------|
| `USER` | End customers making payments | Read own transactions |
| `MERCHANT` | Business owners accepting payments | Manage orders, view reports |
| `ADMIN` | Platform administrators | Full system access |

**File Structure:**

```
backend/identity-service/src/main/java/com/payflow/identity/
├── model/
│   ├── User.java
│   ├── RefreshToken.java
│   └── Role.java
├── repository/
│   ├── UserRepository.java
│   └── RefreshTokenRepository.java
└── ...
```

---

## 2. Entity Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    IDENTITY SERVICE                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌──────────────────┐         ┌──────────────────────┐     │
│  │      User        │ 1    N  │    RefreshToken       │     │
│  ├──────────────────┤─────────├──────────────────────┤     │
│  │ id (UUID PK)     │         │ id (UUID PK)         │     │
│  │ email (UNIQUE)   │         │ token (UNIQUE)       │     │
│  │ passwordHash     │         │ userId (FK)          │     │
│  │ fullName         │         │ expiresAt            │     │
│  │ role (ENUM)      │         │ revoked              │     │
│  │ active           │         │ createdAt            │     │
│  │ createdAt        │         └──────────────────────┘     │
│  │ updatedAt        │                                       │
│  └──────────────────┘                                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Role Enum

The `Role` enum defines the three access levels in PayFlow. We use a Java enum mapped
directly to a PostgreSQL `VARCHAR` column via `@Enumerated(EnumType.STRING)`.

**File:** `backend/identity-service/src/main/java/com/payflow/identity/model/Role.java`

```java
package com.payflow.identity.model;

/**
 * Defines the access levels for PayFlow users.
 * 
 * USER     - End customer who makes payments
 * MERCHANT - Business owner who accepts payments and manages orders
 * ADMIN    - Platform administrator with full system access
 */
public enum Role {
    USER,
    MERCHANT,
    ADMIN;

    /**
     * Returns the Spring Security authority format.
     * Example: Role.MERCHANT -> "ROLE_MERCHANT"
     */
    public String toAuthority() {
        return "ROLE_" + this.name();
    }
}
```

**Why `EnumType.STRING` over `EnumType.ORDINAL`?**

| Approach | Pros | Cons |
|----------|------|------|
| `EnumType.STRING` | Readable in DB, safe to reorder | More storage space |
| `EnumType.ORDINAL` | Compact storage | Breaks if enum order changes |

We choose `STRING` because payment systems must never have ambiguous data — if someone
reorders the enum, ordinal mapping silently corrupts authorization logic.

---

## 4. User Entity

**File:** `backend/identity-service/src/main/java/com/payflow/identity/model/User.java`

```java
package com.payflow.identity.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core user entity for the PayFlow Identity Service.
 * 
 * Design decisions:
 * - UUID primary key: prevents enumeration attacks, safe for distributed systems
 * - Email as unique constraint: serves as the login identifier
 * - passwordHash: never store plain text; we use BCrypt (cost factor 12)
 * - Soft-delete via 'active' flag: preserves audit trail
 */
@Entity
@Table(
    name = "users",
    indexes = {
        @Index(name = "idx_users_email", columnList = "email", unique = true),
        @Index(name = "idx_users_role", columnList = "role")
    }
)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ─── Constructors ────────────────────────────────────────────

    protected User() {
        // JPA requires a no-arg constructor
    }

    public User(String email, String passwordHash, String fullName, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.active = true;
    }

    // ─── Getters & Setters ───────────────────────────────────────

    public UUID getId() { return id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

**Key JPA Annotations Explained:**

| Annotation | Purpose |
|------------|---------|
| `@Entity` | Marks this class as a JPA entity mapped to a database table |
| `@Table(name = "users")` | Explicitly names the table (avoids reserved word conflicts) |
| `@Id` | Marks the primary key field |
| `@GeneratedValue(strategy = GenerationType.UUID)` | Auto-generates UUID values (Hibernate 6+) |
| `@Column(nullable = false, unique = true)` | Adds NOT NULL and UNIQUE constraints |
| `@Enumerated(EnumType.STRING)` | Stores enum as text, not ordinal position |
| `@CreationTimestamp` | Auto-sets on entity creation |
| `@UpdateTimestamp` | Auto-updates on every modification |
| `@Index` | Creates database indexes for query performance |

---

## 5. RefreshToken Entity

**File:** `backend/identity-service/src/main/java/com/payflow/identity/model/RefreshToken.java`

```java
package com.payflow.identity.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refresh token entity for implementing secure token rotation.
 * 
 * Security design:
 * - Each refresh token is single-use (revoked after rotation)
 * - Tokens have an explicit expiry (7 days default)
 * - If a revoked token is reused, ALL tokens for that user are invalidated
 *   (indicates token theft)
 */
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_tokens_token", columnList = "token", unique = true),
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id")
    }
)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ─── Constructors ────────────────────────────────────────────

    protected RefreshToken() {
        // JPA requires a no-arg constructor
    }

    public RefreshToken(String token, UUID userId, LocalDateTime expiresAt) {
        this.token = token;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    // ─── Business Logic ──────────────────────────────────────────

    /**
     * Checks if this token is still usable.
     * A token is valid if it has not been revoked AND has not expired.
     */
    public boolean isValid() {
        return !revoked && expiresAt.isAfter(LocalDateTime.now());
    }

    /**
     * Revokes this token. Once revoked, it cannot be reused.
     */
    public void revoke() {
        this.revoked = true;
    }

    // ─── Getters ─────────────────────────────────────────────────

    public UUID getId() { return id; }
    public String getToken() { return token; }
    public UUID getUserId() { return userId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

**Repository Interface:**

```java
package com.payflow.identity.repository;

import com.payflow.identity.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.userId = :userId")
    void revokeAllByUserId(UUID userId);

    long countByUserIdAndRevokedFalse(UUID userId);
}
```

---

## 6. Entity Relationship Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        IDENTITY SERVICE DATABASE                        │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ┌─────────────────────────────┐                                       │
│  │          users              │                                       │
│  ├─────────────────────────────┤                                       │
│  │ PK  id         UUID         │                                       │
│  │     email      VARCHAR(255) │ ◄── UNIQUE INDEX                      │
│  │     password_hash VARCHAR(72)│                                      │
│  │     full_name  VARCHAR(150) │                                       │
│  │     role       VARCHAR(20)  │ ◄── INDEX (USER|MERCHANT|ADMIN)       │
│  │     active     BOOLEAN      │                                       │
│  │     created_at TIMESTAMP    │                                       │
│  │     updated_at TIMESTAMP    │                                       │
│  └──────────────┬──────────────┘                                       │
│                 │                                                       │
│                 │ 1:N                                                   │
│                 │                                                       │
│  ┌──────────────▼──────────────┐                                       │
│  │      refresh_tokens         │                                       │
│  ├─────────────────────────────┤                                       │
│  │ PK  id         UUID         │                                       │
│  │     token      VARCHAR(512) │ ◄── UNIQUE INDEX                      │
│  │ FK  user_id    UUID         │ ◄── INDEX (references users.id)       │
│  │     expires_at TIMESTAMP    │                                       │
│  │     revoked    BOOLEAN      │                                       │
│  │     created_at TIMESTAMP    │                                       │
│  └─────────────────────────────┘                                       │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Flyway Migration V1 — Users Table

**File:** `backend/identity-service/src/main/resources/db/migration/V1__create_users_table.sql`

```sql
-- ============================================================================
-- V1__create_users_table.sql
-- Creates the core users table for the PayFlow Identity Service
-- ============================================================================

CREATE TABLE users (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255)    NOT NULL,
    password_hash   VARCHAR(72)     NOT NULL,
    full_name       VARCHAR(150)    NOT NULL,
    role            VARCHAR(20)     NOT NULL DEFAULT 'USER',
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'MERCHANT', 'ADMIN'))
);

-- ─── Indexes ─────────────────────────────────────────────────────────────────

-- Primary lookup: find user by email during login
CREATE UNIQUE INDEX idx_users_email ON users (email);

-- Filter queries: list users by role (admin dashboard)
CREATE INDEX idx_users_role ON users (role);

-- Filter queries: list active/inactive users
CREATE INDEX idx_users_active ON users (active);

-- ─── Comments ────────────────────────────────────────────────────────────────

COMMENT ON TABLE users IS 'Core user accounts for PayFlow platform';
COMMENT ON COLUMN users.password_hash IS 'BCrypt hash (cost factor 12) - never store plaintext';
COMMENT ON COLUMN users.role IS 'Access level: USER, MERCHANT, or ADMIN';
```

---

## 8. Flyway Migration V2 — Refresh Tokens Table

**File:** `backend/identity-service/src/main/resources/db/migration/V2__create_refresh_tokens_table.sql`

```sql
-- ============================================================================
-- V2__create_refresh_tokens_table.sql
-- Creates the refresh_tokens table for secure token rotation
-- ============================================================================

CREATE TABLE refresh_tokens (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    token       VARCHAR(512)    NOT NULL,
    user_id     UUID            NOT NULL,
    expires_at  TIMESTAMP       NOT NULL,
    revoked     BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Foreign Key
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE,

    -- Unique constraint on token
    CONSTRAINT uq_refresh_tokens_token UNIQUE (token)
);

-- ─── Indexes ─────────────────────────────────────────────────────────────────

-- Primary lookup: find token during refresh flow
CREATE UNIQUE INDEX idx_refresh_tokens_token ON refresh_tokens (token);

-- Secondary lookup: find all tokens for a user (bulk revocation)
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- Cleanup: find expired tokens for scheduled deletion
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at)
    WHERE revoked = FALSE;

-- ─── Comments ────────────────────────────────────────────────────────────────

COMMENT ON TABLE refresh_tokens IS 'Single-use refresh tokens with rotation support';
COMMENT ON COLUMN refresh_tokens.token IS 'Opaque token value (UUID-based, not JWT)';
COMMENT ON COLUMN refresh_tokens.revoked IS 'Set to TRUE after single use or security event';
```

---

## 9. JPA Annotations Deep Dive

### Generation Strategies Comparison

| Strategy | How It Works | Best For |
|----------|--------------|----------|
| `GenerationType.UUID` | Hibernate generates UUID in application | Distributed systems, no DB roundtrip |
| `GenerationType.IDENTITY` | DB auto-increment (SERIAL) | Simple sequential IDs |
| `GenerationType.SEQUENCE` | DB sequence object | High-throughput inserts |
| `GenerationType.TABLE` | Simulated sequence via table | Portability (avoid) |

### Why UUID for PayFlow?

```
┌─────────────────────────────────────────────────────────┐
│           Sequential ID vs UUID Comparison               │
├───────────────────────┬─────────────────────────────────┤
│  Sequential (BIGINT)  │           UUID                  │
├───────────────────────┼─────────────────────────────────┤
│ /users/1              │ /users/a1b2c3d4-e5f6-...        │
│ /users/2              │ /users/f7g8h9i0-j1k2-...        │
│ /users/3  ← guessable│ /users/m3n4o5p6-q7r8-...        │
├───────────────────────┼─────────────────────────────────┤
│ ❌ Enumerable         │ ✅ Non-enumerable               │
│ ❌ Reveals count      │ ✅ No information leakage       │
│ ✅ Smaller index      │ ❌ Larger index (16 bytes)      │
│ ❌ DB dependency      │ ✅ Generate anywhere            │
└───────────────────────┴─────────────────────────────────┘
```

### Timestamp Annotations

```java
// @CreationTimestamp — set ONCE when entity is first persisted
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private LocalDateTime createdAt;

// @UpdateTimestamp — updated on EVERY save/merge operation
@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private LocalDateTime updatedAt;
```

**Important:** The `updatable = false` on `created_at` prevents accidental modification
even if application code tries to set it.

---

## 10. Configuration & Dependencies

### pom.xml Dependencies (identity-service)

```xml
<dependencies>
    <!-- Spring Boot Starters -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- PostgreSQL Driver -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Flyway for Database Migrations -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
</dependencies>
```

### application.yml (JPA & Flyway Config)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:payflow_identity}
    username: ${DB_USERNAME:payflow}
    password: ${DB_PASSWORD:payflow_secret}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000

  jpa:
    hibernate:
      ddl-auto: validate  # Flyway manages schema; Hibernate only validates
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
    show-sql: false
    open-in-view: false  # Disable OSIV anti-pattern

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

**Critical Setting:** `ddl-auto: validate` ensures Hibernate never modifies the schema —
Flyway is the single source of truth for database changes.

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | UUID Primary Keys | Prevents enumeration attacks, works in distributed systems |
| 2 | Role Enum with STRING mapping | Human-readable in DB, safe against reordering |
| 3 | Refresh Token design | Single-use tokens with revocation for security |
| 4 | Flyway migrations | Versioned, repeatable schema changes (never edit after deploy) |
| 5 | JPA annotations | `@Entity`, `@Table`, `@Column`, `@Index` control DB mapping |
| 6 | Timestamp auto-generation | `@CreationTimestamp` and `@UpdateTimestamp` reduce boilerplate |
| 7 | `ddl-auto: validate` | Let Flyway own the schema, Hibernate just validates |
| 8 | Index strategy | Index what you query: email (login), user_id (token lookup) |

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

1. `JwtService` — Token generation and validation using JJWT 0.12.5
2. `AuthService` — Registration, login, and secure token rotation
3. `SecurityConfig` — BCrypt password encoding and filter chain
4. JWT structure deep dive (header, payload, signature)
