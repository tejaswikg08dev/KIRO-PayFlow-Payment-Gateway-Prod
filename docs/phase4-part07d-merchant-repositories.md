# 🏗️ Phase 4 Part 7d: Merchant Service — Repositories (Data Access)

> **"You write the method name. Spring writes the SQL. That's the deal."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7d — Repositories (Data Access) |
| **What You Build** | MerchantRepository.java, ApiKeyRepository.java, WebhookConfigRepository.java |
| **Previous** | [Part 7c — Flyway Migrations](./phase4-part07c-merchant-migrations.md) |
| **Next** | [Part 7e — DTOs + Mapper](./phase4-part07e-merchant-dtos-mapper.md) |

---

## 📖 Table of Contents

1. [What Are Repositories and Why Do We Need Them?](#1-what-are-repositories-and-why-do-we-need-them)
2. [How Spring Data JPA Works](#2-how-spring-data-jpa-works)
3. [JpaRepository — What You Get for Free](#3-jparepository--what-you-get-for-free)
4. [Folder Structure After This Part](#4-folder-structure-after-this-part)
5. [Step-by-Step: MerchantRepository.java](#5-step-by-step-merchantrepositoryjava)
6. [Step-by-Step: ApiKeyRepository.java](#6-step-by-step-apikeyrepositoryjava)
7. [Step-by-Step: WebhookConfigRepository.java](#7-step-by-step-webhookconfigrepositoryjava)
8. [Why There's No FeeConfigRepository](#8-why-theres-no-feeconfigrepository)
9. [Method Name → SQL Query Reference](#9-method-name--sql-query-reference)
10. [Return Types Explained](#10-return-types-explained)
11. [Common Mistakes](#11-common-mistakes)
12. [What You Learned](#12-what-you-learned)

---

## 1. What Are Repositories and Why Do We Need Them?

A **repository** is the layer between your business logic (services) and the database. It provides methods to create, read, update, and delete data.

```
Service Layer                  Repository Layer                 Database
─────────────                  ────────────────                 ────────
"Find merchant     ──────►    merchantRepository    ──────►    SELECT *
 by email"                    .findByEmail("x@y.com")          FROM merchants
                                                                WHERE email = 'x@y.com'
```

### Why Not Write SQL Directly in Services?

| Approach | Code | Problems |
|---|---|---|
| Raw SQL in service | `jdbcTemplate.query("SELECT * FROM merchants WHERE email = ?", ...)` | Manual result mapping, SQL injection risk, no compile-time checking |
| Spring Data JPA | `merchantRepository.findByEmail("x@y.com")` | Auto-generated SQL, type-safe, compile-time checking, zero SQL |

---

## 2. How Spring Data JPA Works

The magic: **you write an interface, Spring generates the implementation at runtime.**

```java
// YOU write this (interface — no implementation!):
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    Optional<Merchant> findByEmail(String email);
}

// SPRING GENERATES this (you never see or write it):
public class MerchantRepositoryImpl implements MerchantRepository {
    
    @Override
    public Optional<Merchant> findByEmail(String email) {
        return entityManager.createQuery(
            "SELECT m FROM Merchant m WHERE m.email = :email", Merchant.class)
            .setParameter("email", email)
            .getResultList()
            .stream()
            .findFirst();
    }
    
    // ... plus save(), findById(), findAll(), delete(), count(), etc.
}
```

**You write 3 lines. Spring generates 50+.** And Spring's implementation handles:
- Connection pooling (HikariCP)
- Transaction management
- Query optimization
- Result mapping (SQL rows → Java objects)
- Exception translation (SQL exceptions → Spring exceptions)

### How Spring Reads Method Names

```
findByEmail(String email)
│    │  │
│    │  └── Field name in the Entity (Merchant.email)
│    └── "By" keyword — start of the WHERE clause
└── "find" keyword — SELECT query

Spring translates: findByEmail → SELECT * FROM merchants WHERE email = ?
```

---

## 3. JpaRepository — What You Get for Free

Every repository extends `JpaRepository<EntityType, IdType>` which gives you these methods **without writing any code:**

| Method | SQL Equivalent | What It Does |
|---|---|---|
| `save(entity)` | `INSERT` or `UPDATE` | Save new entity or update existing |
| `findById(id)` | `SELECT WHERE id = ?` | Find one by primary key |
| `findAll()` | `SELECT *` | Get all rows |
| `count()` | `SELECT COUNT(*)` | Count all rows |
| `deleteById(id)` | `DELETE WHERE id = ?` | Delete by primary key |
| `existsById(id)` | `SELECT COUNT(*) > 0 WHERE id = ?` | Check if exists |
| `findAll(Pageable)` | `SELECT * LIMIT ? OFFSET ?` | Paginated query |
| `saveAll(list)` | Batch `INSERT`/`UPDATE` | Save multiple entities |
| `deleteAll()` | `DELETE *` | Delete all rows |
| `flush()` | Force SQL execution | Push pending changes to DB immediately |

**That's 10+ methods with ZERO code from you.** Your custom methods (`findByEmail`, `existsByEmail`) are ON TOP of these.

---

## 4. Folder Structure After This Part

```
backend/merchant-service/src/main/java/com/payflow/merchant/
├── MerchantServiceApplication.java    ← from 7a
├── config/
│   └── SecurityConfig.java            ← from 7a
├── model/
│   ├── Merchant.java                  ← from 7b
│   ├── ApiKey.java                    ← from 7b
│   ├── WebhookConfig.java             ← from 7b
│   └── FeeConfig.java                 ← from 7b
└── repository/                        ← YOU CREATE THIS FOLDER
    ├── MerchantRepository.java        ← YOU CREATE THIS
    ├── ApiKeyRepository.java          ← YOU CREATE THIS
    └── WebhookConfigRepository.java   ← YOU CREATE THIS
```

---

## 5. Step-by-Step: MerchantRepository.java

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/repository/MerchantRepository.java`

### Line-by-Line

```java
package com.payflow.merchant.repository;
```

Repositories go in the `repository` sub-package — convention across all PayFlow services.

```java
import com.payflow.merchant.model.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
```

**IMPORTS EXPLAINED:**

| Import | Why |
|---|---|
| `com.payflow.merchant.model.Merchant` | The entity this repository manages |
| `JpaRepository` | The Spring Data interface we extend (gives us free CRUD methods) |
| `@Repository` | Marks this as a Spring-managed data access component |
| `Optional` | Return type for queries that might return 0 or 1 result |
| `UUID` | The type of Merchant's primary key (`private UUID id`) |

```java
@Repository
```

**WHAT:** "Spring, this is a data access bean. Please manage it."

**WHAT IT DOES BEYOND @Component:**
1. Registers the interface as a Spring bean (injectable via `@RequiredArgsConstructor`)
2. Enables **exception translation** — if PostgreSQL throws a raw SQL exception, Spring converts it to a meaningful `DataAccessException`

**IS IT REQUIRED?** Technically optional for Spring Data JPA interfaces (Spring auto-detects them). But best practice to include it — makes the purpose explicit.

```java
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
```

**WORD BY WORD:**

| Part | Meaning |
|---|---|
| `public interface` | This is an interface (not a class) — Spring generates the implementation |
| `MerchantRepository` | Name convention: `{EntityName}Repository` |
| `extends JpaRepository` | Inherit all free CRUD methods (save, findById, findAll, etc.) |
| `<Merchant, UUID>` | First type = Entity class, Second type = Primary key type |

**WHY `UUID` HERE BUT `String` IN IDENTITY?**

| Service | Repository | Entity ID Type |
|---|---|---|
| Identity | `JpaRepository<User, String>` | `private String id` |
| Merchant | `JpaRepository<Merchant, UUID>` | `private UUID id` |

The second type parameter MUST match the entity's `@Id` field type. Identity uses String, Merchant uses UUID.

```java
    Optional<Merchant> findByEmail(String email);
```

**HOW SPRING READS THIS:**

```
findByEmail(String email)
│    │  │         │
│    │  │         └── Parameter: the value to search for
│    │  └── "Email" → maps to Merchant.email field
│    └── "By" → WHERE clause
└── "find" → SELECT query

GENERATED SQL: SELECT * FROM merchants WHERE email = ?
RETURN TYPE:   Optional<Merchant> → 0 or 1 result
```

**WHY `Optional<Merchant>` NOT `Merchant`?**

```java
// WITHOUT Optional:
Merchant m = merchantRepository.findByEmail("nonexistent@x.com");
// m = null ← Dangerous! Any m.getName() call → NullPointerException

// WITH Optional:
Optional<Merchant> m = merchantRepository.findByEmail("nonexistent@x.com");
// m.isPresent() → false
// m.orElseThrow(() -> new ResourceNotFoundException(...)) → throws exception safely
```

`Optional` forces you to handle the "not found" case. It's impossible to accidentally call methods on a null value.

**WHERE THIS IS USED:** In `MerchantService.getMerchant()`:
```java
Merchant merchant = merchantRepository.findByEmail(email)
    .orElseThrow(() -> new ResourceNotFoundException("Merchant", email));
// If not found → clean exception. If found → unwrapped Merchant object.
```

```java
    boolean existsByEmail(String email);
```

**HOW SPRING READS THIS:**

```
existsByEmail(String email)
│       │  │
│       │  └── "Email" → Merchant.email field
│       └── "By" → WHERE clause
└── "exists" → SELECT COUNT(*) > 0 (returns true/false)

GENERATED SQL: SELECT COUNT(*) > 0 FROM merchants WHERE email = ?
RETURN TYPE:   boolean → true if at least one row matches, false otherwise
```

**WHY `existsByEmail` NOT `findByEmail`?**

| Method | What It Returns | Performance |
|---|---|---|
| `findByEmail()` | Full Merchant object (all columns) | Slower (loads all data) |
| `existsByEmail()` | Just `true` or `false` | Faster (only counts, no data loading) |

When you just need to check "does this email exist?" (for duplicate checking), `existsByEmail` is more efficient — it doesn't load the full merchant row.

**WHERE THIS IS USED:** In `MerchantService.registerMerchant()`:
```java
if (merchantRepository.existsByEmail(request.getEmail())) {
    throw new DuplicateResourceException("Merchant", "email", request.getEmail());
}
// Fast check — doesn't load the entire merchant object
```

```java
}
```

**THAT'S THE ENTIRE FILE.** 2 custom methods + everything inherited from JpaRepository. Spring does the rest.

---

## 6. Step-by-Step: ApiKeyRepository.java

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/repository/ApiKeyRepository.java`

This repository has more methods because API keys are accessed in multiple ways.

### Line-by-Line

```java
package com.payflow.merchant.repository;

import com.payflow.merchant.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
```

Same structure as MerchantRepository. Entity=`ApiKey`, PK type=`UUID`.

```java
    List<ApiKey> findByMerchantId(UUID merchantId);
```

**GENERATED SQL:** `SELECT * FROM api_keys WHERE merchant_id = ?`

**RETURN TYPE: `List<ApiKey>`**
One merchant can have MANY API keys (1:N relationship). So this returns a list, not an Optional.

| Return Type | When to Use | Example |
|---|---|---|
| `Optional<T>` | Expecting 0 or 1 result | `findByEmail()` — email is UNIQUE |
| `List<T>` | Expecting 0 or MANY results | `findByMerchantId()` — many keys per merchant |
| `boolean` | Just need exists check | `existsByEmail()` |

**WHERE USED:** `ApiKeyService.getApiKeys(merchantId)` — "list all keys for this merchant."

```java
    Optional<ApiKey> findByKeyHash(String keyHash);
```

**GENERATED SQL:** `SELECT * FROM api_keys WHERE key_hash = ?`

**THIS IS THE MOST CRITICAL QUERY IN THE MERCHANT SERVICE.**

Every API call to PayFlow includes an API key:
```
Client → X-API-Key: pk_YWJjZGVm...
Payment Service → hash it → "a3f8b2c1..." → look up in DB
                              ↓
              apiKeyRepository.findByKeyHash("a3f8b2c1...")
```

This query runs on **every single API request** that includes an API key. That's why we created `idx_api_keys_key_hash` index in the migration — without it, performance degrades as the number of API keys grows.

**WHERE USED:** `ApiKeyService.validateApiKey(rawKey)` — "hash this key and find the matching record."

```java
    Optional<ApiKey> findByPrefix(String prefix);
```

**GENERATED SQL:** `SELECT * FROM api_keys WHERE prefix = ?`

**WHERE USED:** Admin tools — "find which key has prefix YWJjZGVm."

**WHY `Optional` NOT `List`?** Prefixes are extracted from random keys, so they're practically unique (though not enforced as UNIQUE in the DB).

```java
    List<ApiKey> findByMerchantIdAndActiveTrue(UUID merchantId);
```

**HOW SPRING READS THIS:**

```
findByMerchantIdAndActiveTrue(UUID merchantId)
│    │  │           │   │      │
│    │  │           │   │      └── "True" → active = true (literal value, no parameter needed)
│    │  │           │   └── "Active" → ApiKey.active field
│    │  │           └── "And" → SQL AND operator
│    │  └── "MerchantId" → ApiKey.merchantId field
│    └── "By" → WHERE clause
└── "find" → SELECT

GENERATED SQL: SELECT * FROM api_keys WHERE merchant_id = ? AND active = true
```

**THE `True` SUFFIX IS SPECIAL:**
You don't pass `true` as a parameter — Spring hardcodes it:
```java
// You call:
apiKeyRepository.findByMerchantIdAndActiveTrue(merchantId);
// NOT:
apiKeyRepository.findByMerchantIdAndActive(merchantId, true);  // This also works but verbose
```

The `True`/`False` suffix is a Spring Data JPA shorthand for boolean filtering.

**WHERE USED:** "Show only active (non-revoked) keys for this merchant."

```java
}
```

**4 custom methods. Each one becomes a complete SQL query implementation with ZERO code from you.**

---

## 7. Step-by-Step: WebhookConfigRepository.java

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/repository/WebhookConfigRepository.java`

### Line-by-Line

```java
package com.payflow.merchant.repository;

import com.payflow.merchant.model.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {

    List<WebhookConfig> findByMerchantId(UUID merchantId);
```

**GENERATED SQL:** `SELECT * FROM webhook_configs WHERE merchant_id = ?`

**WHERE USED:** `WebhookConfigService.getWebhookConfigs(merchantId)` — "list ALL webhook configs (including deactivated ones) for this merchant."

**WHY INCLUDE DEACTIVATED?** The merchant dashboard shows all webhooks with their status. Deactivated ones show as "Inactive" — the merchant might want to reactivate them.

```java
    List<WebhookConfig> findByMerchantIdAndActiveTrue(UUID merchantId);
```

**GENERATED SQL:** `SELECT * FROM webhook_configs WHERE merchant_id = ? AND active = true`

**WHERE USED:** Two places:
1. `WebhookConfigService.getActiveWebhookConfigs(merchantId)` — service layer method
2. Later, the **Webhook Service** will call this to find "which endpoints should I send this payment notification to?"

**WHY TWO METHODS (all vs active)?**
- Dashboard: Show ALL (merchant needs to see inactive ones too)
- Webhook delivery: Only ACTIVE ones (don't send to deactivated endpoints)

```java
}
```

**Only 2 custom methods. Simple and focused.**

---

## 8. Why There's No FeeConfigRepository

You might notice: we have 4 entities (Merchant, ApiKey, WebhookConfig, FeeConfig) but only 3 repositories. Where's `FeeConfigRepository`?

**Answer:** It doesn't exist in the current codebase because:
1. The FeeConfig entity and migration exist (future-proofing the data model)
2. But no service, controller, or endpoint manages fee configs yet
3. Fee configs will be used by the **Settlement Service** (Phase 4 Part 9) to calculate payouts

**When it's needed, it would look like:**
```java
@Repository
public interface FeeConfigRepository extends JpaRepository<FeeConfig, UUID> {
    Optional<FeeConfig> findByMerchantId(UUID merchantId);
    // One fee config per merchant (1:1) → Optional, not List
}
```

**PRINCIPLE:** Don't build what you don't need yet. The entity and table exist for schema completeness, but the repository/service/controller come when there's actual functionality that uses them.

---

## 9. Method Name → SQL Query Reference

Here's the complete cheat sheet for how Spring Data JPA translates method names:

### Keywords

| Keyword in Method Name | SQL Equivalent | Example |
|---|---|---|
| `findBy` | `SELECT ... WHERE` | `findByEmail` → `WHERE email = ?` |
| `existsBy` | `SELECT COUNT(*) > 0 WHERE` | `existsByEmail` → true/false |
| `countBy` | `SELECT COUNT(*) WHERE` | `countByActive` → number |
| `deleteBy` | `DELETE WHERE` | `deleteByMerchantId` → deletes matching rows |
| `And` | `AND` | `findByMerchantIdAndActiveTrue` → `WHERE merchant_id = ? AND active = true` |
| `Or` | `OR` | `findByNameOrEmail` → `WHERE name = ? OR email = ?` |
| `True` / `False` | `= true` / `= false` | `findByActiveTrue` → `WHERE active = true` |
| `OrderBy...Asc` | `ORDER BY ... ASC` | `findByMerchantIdOrderByCreatedAtAsc` |
| `OrderBy...Desc` | `ORDER BY ... DESC` | `findAllOrderByCreatedAtDesc` |
| `IsNull` | `IS NULL` | `findByMdrRateIsNull` → `WHERE mdr_rate IS NULL` |
| `IsNotNull` | `IS NOT NULL` | `findByMdrRateIsNotNull` |
| `In` | `IN (...)` | `findByIdIn(List<UUID> ids)` → `WHERE id IN (?, ?, ?)` |
| `Between` | `BETWEEN ... AND ...` | `findByCreatedAtBetween(start, end)` |
| `GreaterThan` | `>` | `findByMdrRateGreaterThan(2.0)` |
| `LessThan` | `<` | `findByMdrRateLessThan(5.0)` |
| `Like` | `LIKE` | `findByNameLike("%Shop%")` |
| `Containing` | `LIKE %...%` | `findByNameContaining("Shop")` |
| `StartingWith` | `LIKE ...%` | `findByNameStartingWith("Raj")` |
| `Top` / `First` | `LIMIT` | `findTop5ByOrderByCreatedAtDesc` → last 5 created |

### All Queries Used in Merchant Service

| Repository | Method | Generated SQL |
|---|---|---|
| MerchantRepo | `findByEmail(email)` | `SELECT * FROM merchants WHERE email = ?` |
| MerchantRepo | `existsByEmail(email)` | `SELECT COUNT(*) > 0 FROM merchants WHERE email = ?` |
| ApiKeyRepo | `findByMerchantId(id)` | `SELECT * FROM api_keys WHERE merchant_id = ?` |
| ApiKeyRepo | `findByKeyHash(hash)` | `SELECT * FROM api_keys WHERE key_hash = ?` |
| ApiKeyRepo | `findByPrefix(prefix)` | `SELECT * FROM api_keys WHERE prefix = ?` |
| ApiKeyRepo | `findByMerchantIdAndActiveTrue(id)` | `SELECT * FROM api_keys WHERE merchant_id = ? AND active = true` |
| WebhookRepo | `findByMerchantId(id)` | `SELECT * FROM webhook_configs WHERE merchant_id = ?` |
| WebhookRepo | `findByMerchantIdAndActiveTrue(id)` | `SELECT * FROM webhook_configs WHERE merchant_id = ? AND active = true` |

---

## 10. Return Types Explained

| Return Type | Meaning | Use When |
|---|---|---|
| `Optional<T>` | 0 or 1 result | Querying by unique field (email, keyHash) |
| `List<T>` | 0, 1, or many results | Querying by non-unique field (merchantId) |
| `boolean` | true or false | Checking existence (`existsBy...`) |
| `long` | Count | Counting rows (`countBy...`) |
| `void` | Nothing returned | Deleting rows (`deleteBy...`) |
| `Page<T>` | Paginated results | With `Pageable` parameter |
| `Stream<T>` | Lazy loading | Large result sets |

### Why Optional Matters

```java
// DANGEROUS (returning null):
Merchant findByEmail(String email);
// Usage:
Merchant m = repo.findByEmail("nonexistent@x.com");  // m = null
m.getName();  // 💥 NullPointerException at runtime!

// SAFE (returning Optional):
Optional<Merchant> findByEmail(String email);
// Usage:
Merchant m = repo.findByEmail("nonexistent@x.com")
    .orElseThrow(() -> new ResourceNotFoundException("Merchant not found"));
// Either returns the merchant OR throws a clean exception. No NPE possible.
```

---

## 11. Common Mistakes

| Mistake | Error | Fix |
|---|---|---|
| Method name typo: `findByEmal` | Startup error: "No property 'emal' found for type Merchant" | Spelling must match entity field name exactly |
| Wrong return type: `Merchant findByMerchantId(UUID id)` | Runtime error when multiple results found | Use `List<Merchant>` for non-unique queries |
| Missing entity field: `findByPhone()` when Merchant has no `phone` field | Startup error: "No property 'phone' found" | Only query on fields that exist in the entity |
| Wrong PK type: `JpaRepository<Merchant, String>` when ID is UUID | Compilation error or runtime type mismatch | PK type must match entity's @Id field type |
| Forgetting `@Repository` | Still works (Spring Data auto-detects) but less explicit | Always include for clarity |
| Using `findByMerchantId` in MerchantRepository | "merchantId" doesn't exist on Merchant — it has "id" | Use `findById()` (inherited) for the entity's own PK |

---

## 12. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Interface, not class** | You write an interface. Spring generates the implementation at runtime. |
| 2 | **JpaRepository<Entity, IdType>** | Inherit save/findById/findAll/delete/count for free |
| 3 | **Method name = query** | `findByEmail` → `WHERE email = ?`. Spring parses the method name. |
| 4 | **Optional vs List** | Optional for unique fields (0-1 result). List for non-unique (0-many). |
| 5 | **existsBy = fast check** | `existsByEmail` is faster than `findByEmail` when you just need true/false |
| 6 | **And keyword** | Combine conditions: `findByMerchantIdAndActiveTrue` → two WHERE conditions |
| 7 | **True/False suffix** | Shorthand for boolean: `ActiveTrue` → `active = true` without parameter |
| 8 | **@Repository** | Marks as data access bean + enables exception translation |
| 9 | **No implementation needed** | The entire MerchantRepository is 7 lines of code. Spring does the rest. |
| 10 | **Build only what you need** | No FeeConfigRepository — it'll be created when Settlement Service needs it |
| 11 | **Method names must match entity fields** | `findByEmail` only works if the entity has an `email` field |
| 12 | **UUID vs String as PK type** | Must match entity's @Id type: `JpaRepository<Merchant, UUID>` |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part07-merchant-service-overview.md) | Merchant Service Overview |
| [Part 7a](./phase4-part07a-merchant-project-setup.md) | Project Setup |
| [Part 7b](./phase4-part07b-merchant-entities.md) | Entities |
| [Part 7c](./phase4-part07c-merchant-migrations.md) | Flyway Migrations |
| **Part 7d** | **Repositories** (You are here) |
| [Part 7e](./phase4-part07e-merchant-dtos-mapper.md) | DTOs + Mapper |
| [Part 7f](./phase4-part07f-merchant-services-tests.md) | Services + Tests |
| [Part 7g](./phase4-part07g-merchant-controller-docker.md) | Controller + Docker |

---

*Next: [Part 7e — DTOs + MerchantMapper (Input/Output Contracts)](./phase4-part07e-merchant-dtos-mapper.md) →*
