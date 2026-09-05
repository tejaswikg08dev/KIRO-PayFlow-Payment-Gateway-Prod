# 🏗️ Phase 4 Part 7b: Merchant Service — Entities (Data Models)

> **"Entities are the blueprint of your database — every column, constraint, and relationship starts here."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7b — Entities (Data Models) |
| **What You Build** | Merchant.java, ApiKey.java, WebhookConfig.java, FeeConfig.java |
| **Previous** | [Part 7a — Project Setup](./phase4-part07a-merchant-project-setup.md) |
| **Next** | [Part 7c — Flyway Migrations](./phase4-part07c-merchant-migrations.md) |

---

## 📖 Table of Contents

1. [What Are Entities and Why Do We Need Them?](#1-what-are-entities-and-why-do-we-need-them)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: Merchant.java](#3-step-by-step-merchantjava)
4. [Step-by-Step: ApiKey.java](#4-step-by-step-apikeyjava)
5. [Step-by-Step: WebhookConfig.java](#5-step-by-step-webhookconfigjava)
6. [Step-by-Step: FeeConfig.java](#6-step-by-step-feeconfigjava)
7. [How Entities Relate to Each Other](#7-how-entities-relate-to-each-other)
8. [Key Differences from Identity Service Entities](#8-key-differences-from-identity-service-entities)
9. [Common Mistakes and How to Avoid Them](#9-common-mistakes-and-how-to-avoid-them)
10. [What You Learned](#10-what-you-learned)

---

## 1. What Are Entities and Why Do We Need Them?

An **entity** is a Java class that maps to a **database table**. Each instance of the class = one row in the table. Each field in the class = one column in the table.

```
Java World                          Database World
──────────                          ──────────────
Merchant.java         ←→            merchants table
  .id                 ←→              id column
  .name               ←→              name column
  .email              ←→              email column

Merchant object       ←→            One row in merchants table
{id=uuid, name="Shop"}              (uuid, 'Shop', ...)
```

### Why Not Just Write SQL?

| Approach | Code | Problems |
|---|---|---|
| Raw SQL | `ResultSet rs = stmt.executeQuery("SELECT * FROM merchants")` | Manual mapping, no type safety, SQL injection risk |
| JPA Entity | `merchantRepository.findByEmail("x@y.com")` | Auto-mapping, type safe, SQL generated for you |

### The 4 Entities We Build

| Entity | Table | Relationship | Purpose |
|---|---|---|---|
| **Merchant** | `merchants` | Parent | The business that accepts payments |
| **ApiKey** | `api_keys` | Child (N:1) | Merchant's API key for authenticating API calls |
| **WebhookConfig** | `webhook_configs` | Child (N:1) | Merchant's webhook endpoint for notifications |
| **FeeConfig** | `fee_configs` | Child (1:1) | Merchant's fee structure (MDR + GST percentages) |

---

## 2. Folder Structure After This Part

```
backend/merchant-service/src/main/java/com/payflow/merchant/
├── MerchantServiceApplication.java    ← from 7a
├── config/
│   └── SecurityConfig.java            ← from 7a
└── model/                             ← YOU CREATE THIS FOLDER
    ├── Merchant.java                  ← YOU CREATE THIS
    ├── ApiKey.java                    ← YOU CREATE THIS
    ├── WebhookConfig.java             ← YOU CREATE THIS
    └── FeeConfig.java                 ← YOU CREATE THIS
```

---

## 3. Step-by-Step: Merchant.java

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/Merchant.java`

This is the **parent entity** — all other entities belong to a merchant.

### Line-by-Line

```java
package com.payflow.merchant.model;
```

**WHAT:** This class lives in the `model` sub-package.

**WHY `model` not `entity`?** Both names are common. PayFlow uses `model` consistently across all services (Identity used `model` too). Some projects use `entity` — it's a team convention, not a technical requirement.

```java
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;
```

**IMPORTS EXPLAINED:**

| Import | What It Provides |
|---|---|
| `jakarta.persistence.*` | All JPA annotations: `@Entity`, `@Table`, `@Id`, `@Column`, `@GeneratedValue` |
| `lombok.*` | `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` |
| `org.hibernate.annotations.*` | `@CreationTimestamp`, `@UpdateTimestamp` (Hibernate-specific, not JPA standard) |
| `java.time.Instant` | Timestamp type (UTC, nanosecond precision) |
| `java.util.UUID` | Java's built-in UUID type |

**WHY `jakarta.persistence` NOT `javax.persistence`?**
Spring Boot 3.x uses Jakarta EE 10 (the `jakarta.*` namespace). Spring Boot 2.x used `javax.*`. If you see `javax.persistence` in old tutorials, it won't compile with Spring Boot 3.

```java
@Data
```

**WHAT:** Lombok annotation that generates at compile time:
- `getName()`, `setName()` — for every field
- `getEmail()`, `setEmail()` — for every field
- `toString()` — `"Merchant(id=uuid, name=Shop, email=...)`"
- `equals()` — compares all fields
- `hashCode()` — consistent with equals

**WITHOUT @Data** you'd write ~60 lines of boilerplate getters/setters/toString/equals/hashCode.

```java
@Builder
```

**WHAT:** Lombok generates a Builder pattern:
```java
// Instead of:
Merchant m = new Merchant();
m.setName("Shop");
m.setEmail("shop@example.com");
m.setBusinessType("RETAIL");

// You write:
Merchant m = Merchant.builder()
    .name("Shop")
    .email("shop@example.com")
    .businessType("RETAIL")
    .build();
```

**WHY BUILDER?** Cleaner, more readable, and you can't accidentally forget to set a required field (compiler catches it in some patterns).

```java
@NoArgsConstructor
```

**WHAT:** Generates an empty constructor: `public Merchant() {}`

**WHY:** JPA/Hibernate REQUIRES a no-arg constructor. When Hibernate loads data from the database, it:
1. Creates an empty `Merchant` object using the no-arg constructor
2. Fills in the fields using setters (or reflection)

Without this → Hibernate throws: `No default constructor for entity: Merchant`

```java
@AllArgsConstructor
```

**WHAT:** Generates a constructor with ALL fields as parameters.

**WHY:** `@Builder` needs this to work. The Builder pattern calls the all-args constructor internally:
```java
// Builder internally does:
new Merchant(id, name, email, businessType, mdrRate, active, createdAt, updatedAt);
```

Without `@AllArgsConstructor` → `@Builder` compilation error.

```java
@Entity
```

**WHAT:** "This class is a JPA entity — it maps to a database table."

**WHY:** Without `@Entity`, JPA/Hibernate completely ignores this class. It's just a regular Java class that has no connection to the database.

**WHAT HAPPENS:** When Spring Boot starts, Hibernate scans for all `@Entity` classes and:
1. Creates an in-memory model of the database schema
2. If `ddl-auto: validate` → compares this model to the actual DB tables
3. If they don't match → startup fails with a clear error message

```java
@Table(name = "merchants")
```

**WHAT:** Explicitly sets the database table name to `merchants`.

**WHY EXPLICIT?** Without `@Table`, JPA uses the class name as the table name: `Merchant` → `merchant` table. We want `merchants` (plural). Being explicit also:
- Makes the mapping crystal clear to anyone reading the code
- Avoids surprises if you rename the class later
- Prevents issues with reserved words (e.g., `User` is reserved in PostgreSQL)

```java
public class Merchant {
```

Now let's go through each field:

```java
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
```

**LINE 1 — `@Id`:**
"This field is the primary key of the table." Every entity MUST have exactly one `@Id` field.

**LINE 2 — `@GeneratedValue(strategy = GenerationType.UUID)`:**
"Hibernate, generate a UUID value automatically when I save a new entity."

What happens when you call `merchantRepository.save(merchant)`:
- If `merchant.id == null` → Hibernate generates a UUID and does INSERT
- If `merchant.id != null` → Hibernate does UPDATE (assumes entity already exists)

**LINE 3 — `private UUID id`:**
Java's native `UUID` type (not `String` like Identity Service).

**WHY UUID HERE BUT STRING IN IDENTITY?**

| | Identity Service | Merchant Service |
|---|---|---|
| Type | `private String id` | `private UUID id` |
| DB column | `VARCHAR(36)` | `UUID` (PostgreSQL native) |
| Both work? | ✅ Yes | ✅ Yes |

Both approaches are valid. `UUID` type gives type safety (you can't accidentally pass a random string where a UUID is expected). `String` is simpler for JSON serialization. It's a team preference.

```java
    @Column(nullable = false)
    private String name;
```

**`@Column(nullable = false)`:**
"This column cannot be NULL in the database." Maps to `NOT NULL` SQL constraint.

If you try to save a Merchant with `name = null`:
- **JPA level:** Hibernate throws `ConstraintViolationException` before hitting DB
- **DB level:** PostgreSQL rejects the INSERT with `NOT NULL violation`

Double protection — both application and database enforce the constraint.

**`private String name`:**
The merchant's business name (e.g., "Rajesh Electronics"). Maps to `VARCHAR(255)` by default.

```java
    @Column(nullable = false, unique = true)
    private String email;
```

**`unique = true`:**
"No two rows can have the same email." Maps to `UNIQUE` SQL constraint.

**WHY UNIQUE?** One email = one merchant. If two merchants shared an email:
- Which merchant does `findByEmail()` return?
- Which merchant receives notification emails?
- Duplicate registrations become possible

The `unique` constraint prevents this at the database level (even if application code has a bug).

```java
    @Column(name = "business_type", nullable = false)
    private String businessType;
```

**`name = "business_type"`:**
"The DB column name is `business_type`, not `businessType`."

**WHY?** Java convention = camelCase (`businessType`). Database convention = snake_case (`business_type`). The `name` parameter bridges the two conventions.

Without `name`: Hibernate auto-converts camelCase → snake_case in most configurations. But being explicit is safer and more readable.

**WHY String NOT enum?**
```java
// Option A: Enum (strict)
private BusinessType businessType;  // Only predefined values: RETAIL, SAAS, etc.

// Option B: String (flexible) ← What we use
private String businessType;  // Any value: "RETAIL", "FOOD_DELIVERY", "CUSTOM_TYPE"
```

We chose String because new business types shouldn't require a code change and redeployment. With String, an admin can add "FOOD_DELIVERY" to a merchant without touching Java code.

```java
    @Column(name = "mdr_rate")
    private Double mdrRate;
```

**No `nullable = false`:**
This field CAN be null. MDR rate might not be set at registration time — it can be configured later.

**WHY `Double` (wrapper) NOT `double` (primitive)?**
- `double` (primitive) → can't be null (defaults to 0.0)
- `Double` (wrapper) → CAN be null (means "not set yet")

For a field that's optional, you need the wrapper type to distinguish "0.0" (zero rate) from "not configured" (null).

**WHY `Double` NOT `BigDecimal` for MDR rate?**
This field is just for display/reference. The actual fee calculation happens in `FeeConfig` which uses `BigDecimal` for precision. This is a convenience field on the merchant profile.

```java
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;
```

**`@Builder.Default`:**
"When using the Builder pattern and `active` is NOT explicitly set, use `true` as the default."

**WHY IS THIS NEEDED?**
```java
// WITHOUT @Builder.Default:
Merchant m = Merchant.builder().name("Shop").email("x@y.com").build();
// m.active = null (!!!) — Builder doesn't know about "= true" default

// WITH @Builder.Default:
Merchant m = Merchant.builder().name("Shop").email("x@y.com").build();
// m.active = true (✓) — Builder uses the default value
```

**WHY `Boolean` (wrapper) NOT `boolean` (primitive)?**
- `@Builder.Default` works more reliably with wrapper types
- In some JPA lazy-loading scenarios, a wrapper type can be null to indicate "not loaded yet"
- Convention in this project — Identity Service used primitive `boolean`, Merchant uses wrapper `Boolean`. Both work.

```java
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
```

**`@CreationTimestamp`:**
"Hibernate, set this field to `Instant.now()` when the entity is first saved (INSERT)."
You never need to write `merchant.setCreatedAt(Instant.now())` — Hibernate does it automatically.

**`updatable = false`:**
"This column can NEVER be changed after the initial INSERT."
Even if code tries to do `merchant.setCreatedAt(someOtherTime)`, Hibernate ignores it during UPDATE.

**WHY `Instant` NOT `LocalDateTime`?**
- `Instant` = a point in time in UTC (no timezone ambiguity)
- `LocalDateTime` = a date/time without timezone (ambiguous — which timezone?)

For a payment system serving multiple timezones, `Instant` (UTC) is the standard.

```java
    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
```

**`@UpdateTimestamp`:**
"Hibernate, update this field to `Instant.now()` on EVERY save (INSERT and UPDATE)."

**NO `updatable = false`:**
Unlike `createdAt`, this field IS updated on every modification. That's the whole point — "when was this merchant last modified?"

---

## 4. Step-by-Step: ApiKey.java

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/ApiKey.java`

```java
package com.payflow.merchant.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
```

Same annotations as Merchant — nothing new here.

```java
    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;
```

**WHAT:** The SHA-256 hash of the raw API key.

**WHY `key_hash` NOT `key`?**
1. `key` is a reserved word in SQL
2. The name makes it explicit: we store a HASH, not the actual key

**WHY `unique = true`?**
Each API key has a unique hash. Two different raw keys will (virtually) never produce the same SHA-256 hash. The unique constraint is a safety net.

**WHAT'S STORED:**
```
Raw key (returned to merchant once):  "pk_YWJjZGVmZ2hpamtsbW5vcHFy..."
What we store in DB (hash):           "a3f8b2c1d4e7f6a5b9c8d7e6f5a4b3c2d1e0..."
                                       ↑ 64 hex characters (SHA-256 output)

If DB is stolen, attacker has hashes → can't reverse to get raw keys
```

```java
    @Column(nullable = false, length = 8)
    private String prefix;
```

**`length = 8`:**
"This column is VARCHAR(8) — exactly 8 characters."

**WHAT'S STORED:**
```
Raw key: "pk_YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXox"
Prefix:       "YWJjZGVm"  ← characters 3-11 (first 8 after "pk_")
```

**WHY STORE PREFIX?**
When a merchant looks at their API keys dashboard, they see:
```
| Key ID | Prefix    | Status | Created     |
|--------|-----------|--------|-------------|
| uuid-1 | YWJjZGVm  | Active | 2024-01-15  |
| uuid-2 | bXl0ZXN0  | Revoked| 2024-01-10  |
```

The prefix lets them IDENTIFY which key is which without exposing the full key. Like showing "****1234" for a credit card.

```java
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;
```

**WHAT:** Foreign key reference to the `merchants` table.

**WHY PLAIN UUID NOT `@ManyToOne`?**
```java
// OPTION A: JPA relationship (complex)
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "merchant_id")
private Merchant merchant;

// OPTION B: Plain UUID (simple) ← What we use
@Column(name = "merchant_id", nullable = false)
private UUID merchantId;
```

We chose Option B because:
- Simpler — no lazy-loading complexity, no N+1 query problems
- We only need the ID to look up the merchant separately if needed
- The FK constraint is enforced at the DB level (in the migration SQL), not by JPA

```java
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

Same pattern as Merchant — `active` defaults to true, timestamps are auto-managed.

---

## 5. Step-by-Step: WebhookConfig.java

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/WebhookConfig.java`

The unique feature here is the **PostgreSQL TEXT[] array column**.

```java
package com.payflow.merchant.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "webhook_configs")
public class WebhookConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String url;
```

**WHAT:** The HTTPS endpoint PayFlow sends webhook notifications to.
Example: `"https://merchant-site.com/payflow-webhook"`

```java
    @Column(nullable = false)
    private String secret;
```

**WHAT:** An auto-generated HMAC signing secret.
Format: `"whsec_YWJjZGVmZ2hpamtsbW5vcHFy..."` (prefix + base64-encoded random bytes)

**HOW IT'S USED (later, by the Webhook Service):**
```
PayFlow sends webhook:
1. Create JSON payload: {"event": "payment.captured", "amount": 1000}
2. Compute: signature = HMAC-SHA256(payload, whsec_YWJjZGVm...)
3. Send HTTP POST to merchant's URL with header:
   X-Payflow-Signature: sha256=a3f8b2c1d4e7...

Merchant verifies (in their code):
1. Read payload and X-Payflow-Signature header
2. Recompute: expected = HMAC-SHA256(payload, their_stored_secret)
3. If signature matches → authentic webhook from PayFlow
4. If signature doesn't match → reject (possible attack)
```

```java
    @Column(name = "events", columnDefinition = "TEXT[]")
    private String[] events;
```

**THIS IS THE UNIQUE PART.**

**`columnDefinition = "TEXT[]"`:**
"Don't use the default VARCHAR column type. Use PostgreSQL's native TEXT array."

**WHAT'S STORED IN DB:**
```sql
-- PostgreSQL stores it as a native array:
events = '{payment.authorized,payment.captured,payment.refunded}'
```

**WHAT'S IN JAVA:**
```java
String[] events = {"payment.authorized", "payment.captured", "payment.refunded"};
```

**WHY ARRAY NOT SEPARATE TABLE?**

| Approach | SQL | Complexity |
|---|---|---|
| Separate table | `SELECT e.event FROM webhook_events e WHERE e.config_id = ?` | Needs JOIN, extra table, extra migration |
| PostgreSQL array | `SELECT events FROM webhook_configs WHERE id = ?` | Single query, no join |

For a small, fixed set of values (6 possible event types), an array is simpler and faster. A separate table makes sense when the list is large or needs its own attributes.

**SUPPORTED EVENTS:**
```
payment.authorized    — Payment was approved by bank
payment.captured      — Money was collected from customer
payment.failed        — Payment was declined
payment.refunded      — Money was returned to customer
settlement.completed  — Settlement batch completed
settlement.payout     — Payout sent to merchant's bank
```

```java
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

Same patterns as ApiKey.

---

## 6. Step-by-Step: FeeConfig.java

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/FeeConfig.java`

The unique feature here is **BigDecimal for financial calculations**.

```java
package com.payflow.merchant.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fee_configs")
public class FeeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private UUID merchantId;
```

**`unique = true` ON A FOREIGN KEY:**
This is special. Unlike ApiKey and WebhookConfig (where one merchant can have MANY), each merchant has EXACTLY ONE fee config.

```
Merchant → ApiKey:       1:N (one merchant, many keys)
Merchant → WebhookConfig: 1:N (one merchant, many webhooks)
Merchant → FeeConfig:     1:1 (one merchant, ONE fee config) ← UNIQUE enforces this
```

If you try to INSERT a second FeeConfig for the same merchant → PostgreSQL rejects it with `UNIQUE constraint violation`.

```java
    @Column(name = "mdr_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal mdrPercent;
```

**`precision = 5, scale = 2`:**
- `precision` = total number of digits (before + after decimal point)
- `scale` = digits after the decimal point

So `DECIMAL(5,2)` means: maximum value is `999.99`.

Examples:
```
2.00   ← valid (MDR rate of 2%)
18.00  ← valid (GST rate of 18%)
100.00 ← valid
999.99 ← valid (maximum)
1000.00 ← INVALID (6 digits, exceeds precision 5)
```

**WHY `BigDecimal` NOT `Double`?**

```java
// WRONG — Double loses precision:
double mdr = 2.0;
double amount = 1000.50;
double fee = amount * mdr / 100;
System.out.println(fee);  // 20.009999999999998 ← WRONG!

// RIGHT — BigDecimal is exact:
BigDecimal mdr = new BigDecimal("2.00");
BigDecimal amount = new BigDecimal("1000.50");
BigDecimal fee = amount.multiply(mdr).divide(new BigDecimal("100"));
System.out.println(fee);  // 20.01 ← CORRECT!
```

**RULE:** In a payment system, NEVER use `float` or `double` for money or percentages. Always use `BigDecimal`.

```java
    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercent;
```

**WHAT:** GST (Goods and Services Tax) percentage applied on top of MDR.

**FEE CALCULATION EXAMPLE:**
```
Payment:     ₹1,000.00
MDR (2%):    ₹1,000 × 2.00 / 100 = ₹20.00     ← PayFlow's fee
GST (18%):   ₹20.00 × 18.00 / 100 = ₹3.60     ← Tax on the fee
Total:       ₹20.00 + ₹3.60 = ₹23.60           ← Total deduction
Merchant:    ₹1,000.00 - ₹23.60 = ₹976.40     ← Merchant receives this

Note: This calculation happens in Settlement Service (not here).
FeeConfig just STORES the percentages.
```

```java
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

**NOTE:** No `active` field on FeeConfig. Fee configs are either updated (new percentages) or deleted (rare). There's no "deactivate" concept for fee structures.

---

## 7. How Entities Relate to Each Other

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│  Merchant (PARENT)                                               │
│  ┌──────────┐                                                    │
│  │ id (PK)  │                                                    │
│  │ name     │                                                    │
│  │ email UQ │                                                    │
│  └────┬─────┘                                                    │
│       │                                                          │
│       ├────── 1:N ──── ApiKey                                    │
│       │                 └── merchant_id (FK) → merchants.id      │
│       │                 └── One merchant can have MANY keys      │
│       │                                                          │
│       ├────── 1:N ──── WebhookConfig                             │
│       │                 └── merchant_id (FK) → merchants.id      │
│       │                 └── One merchant can have MANY webhooks  │
│       │                                                          │
│       └────── 1:1 ──── FeeConfig                                │
│                         └── merchant_id (FK + UQ) → merchants.id│
│                         └── One merchant has ONE fee config      │
│                                                                  │
│  All FKs use ON DELETE CASCADE:                                  │
│  Delete a merchant → all keys, webhooks, fees auto-deleted      │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**WHY NOT JPA @OneToMany/@ManyToOne?**
We use plain UUID foreign keys instead of JPA relationship annotations. The FK constraints are defined in the SQL migrations (Part 7c). This keeps entities simple and avoids JPA lazy-loading complexity.

---

## 8. Key Differences from Identity Service Entities

| Feature | Identity (User) | Merchant (Merchant) |
|---|---|---|
| **ID type** | `String` | `UUID` |
| **Active type** | `boolean` (primitive) | `Boolean` (wrapper) |
| **Timestamp** | `Instant` | `Instant` (same) |
| **@Table name** | `"users"` | `"merchants"` |
| **Sub-entities** | RefreshToken only | ApiKey, WebhookConfig, FeeConfig |
| **Financial fields** | None | `BigDecimal` (FeeConfig), `Double` (mdrRate) |
| **Array columns** | None | `TEXT[]` (WebhookConfig.events) |
| **Password storage** | `passwordHash` (BCrypt) | `keyHash` (SHA-256) |
| **Annotations** | `@Enumerated(EnumType.STRING)` for Role | No enums |
| **FK approach** | Plain `String userId` | Plain `UUID merchantId` |

---

## 9. Common Mistakes and How to Avoid Them

| Mistake | What Happens | Fix |
|---|---|---|
| Forget `@NoArgsConstructor` | Hibernate: "No default constructor" | Always include it with `@Builder` |
| Forget `@Builder.Default` on `active` | `Builder().build()` sets active=null (not true) | Add `@Builder.Default` before `private Boolean active = true` |
| Use `double` for money | `1000.50 * 2.0 / 100 = 20.009999...` | Use `BigDecimal` |
| Forget `name = "snake_case"` on @Column | Column name = "businessType" (camelCase in DB) | Add `@Column(name = "business_type")` |
| Use `@ManyToOne` for FK | Lazy-loading complexity, N+1 queries | Use plain `UUID merchantId` |
| Forget `@Entity` | Hibernate ignores the class entirely | Always add `@Entity` |
| Use `javax.persistence` (old) | Compilation error in Spring Boot 3 | Use `jakarta.persistence` |

---

## 10. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **@Entity + @Table** | Mark a class as a JPA entity and set the table name |
| 2 | **@Id + @GeneratedValue(UUID)** | Auto-generated UUID primary key |
| 3 | **@Column(nullable, unique, name, length)** | Control column constraints and naming |
| 4 | **@Builder.Default** | Set default value when using Lombok Builder (critical for booleans!) |
| 5 | **UUID vs String ID** | Both work; UUID gives type safety, String is simpler |
| 6 | **Boolean vs boolean** | Wrapper (Boolean) can be null; primitive (boolean) defaults to false |
| 7 | **BigDecimal for money** | NEVER use double/float for financial calculations |
| 8 | **precision and scale** | `DECIMAL(5,2)` = up to 999.99 |
| 9 | **TEXT[] PostgreSQL array** | Native array column — simpler than a join table for small fixed sets |
| 10 | **columnDefinition** | Override the default SQL type JPA would generate |
| 11 | **Plain UUID FK vs @ManyToOne** | Plain UUID is simpler — FK constraint enforced in SQL migration |
| 12 | **unique on FK** | `merchant_id UNIQUE` enforces 1:1 relationship (FeeConfig) |
| 13 | **@CreationTimestamp / @UpdateTimestamp** | Hibernate auto-manages timestamps — never set them manually |
| 14 | **Instant vs LocalDateTime** | `Instant` = UTC (no timezone ambiguity) — use for payment systems |
| 15 | **jakarta.persistence vs javax.persistence** | Spring Boot 3 = jakarta; Spring Boot 2 = javax |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part07-merchant-service-overview.md) | Merchant Service Overview |
| [Part 7a](./phase4-part07a-merchant-project-setup.md) | Project Setup |
| **Part 7b** | **Entities (Data Models)** (You are here) |
| [Part 7c](./phase4-part07c-merchant-migrations.md) | Flyway Migrations |
| [Part 7d](./phase4-part07d-merchant-repositories.md) | Repositories |
| [Part 7e](./phase4-part07e-merchant-dtos-mapper.md) | DTOs + Mapper |
| [Part 7f](./phase4-part07f-merchant-services-tests.md) | Services + Tests |
| [Part 7g](./phase4-part07g-merchant-controller-docker.md) | Controller + Docker |

---

*Next: [Part 7c — Flyway Migrations (Database Tables)](./phase4-part07c-merchant-migrations.md) →*
