# 🏗️ Phase 4 Part 7c: Merchant Service — Flyway Migrations (Database Tables)

> **"Your entities define the dream. Migrations make it real — one versioned SQL file at a time."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7c — Flyway Migrations |
| **What You Build** | V1__create_merchants_table.sql, V2__create_api_keys_table.sql, V3__create_webhook_configs_table.sql, V4__create_fee_configs_table.sql |
| **Previous** | [Part 7b — Entities](./phase4-part07b-merchant-entities.md) |
| **Next** | [Part 7d — Repositories](./phase4-part07d-merchant-repositories.md) |

---

## 📖 Table of Contents

1. [What Is Flyway and Why Do We Need It?](#1-what-is-flyway-and-why-do-we-need-it)
2. [How Flyway Works — Step by Step](#2-how-flyway-works--step-by-step)
3. [Flyway Naming Rules](#3-flyway-naming-rules)
4. [Folder Structure After This Part](#4-folder-structure-after-this-part)
5. [Step-by-Step: V1 — Merchants Table](#5-step-by-step-v1--merchants-table)
6. [Step-by-Step: V2 — API Keys Table](#6-step-by-step-v2--api-keys-table)
7. [Step-by-Step: V3 — Webhook Configs Table](#7-step-by-step-v3--webhook-configs-table)
8. [Step-by-Step: V4 — Fee Configs Table](#8-step-by-step-v4--fee-configs-table)
9. [How Migrations Map to Entities](#9-how-migrations-map-to-entities)
10. [What Happens on Application Startup](#10-what-happens-on-application-startup)
11. [Common Mistakes and Rules](#11-common-mistakes-and-rules)
12. [What You Learned](#12-what-you-learned)

---

## 1. What Is Flyway and Why Do We Need It?

Flyway is a **database migration tool**. It runs SQL scripts in order to create or modify your database schema.

### The Problem Without Flyway

```
Developer A: "I created the merchants table manually on my machine"
Developer B: "I also created it, but with different column sizes"
Production:  "Nobody ran the SQL yet — the table doesn't exist"
                → Application starts → Hibernate validation FAILS → CRASH
```

### The Solution With Flyway

```
V1__create_merchants_table.sql     ← Everyone runs the EXACT same SQL
V2__create_api_keys_table.sql      ← Applied in ORDER (V1 before V2)
V3__create_webhook_configs_table.sql
V4__create_fee_configs_table.sql

On every machine (dev, staging, production):
  App starts → Flyway checks "which migrations are new?" → Runs them → Done
```

### Real-World Analogy

Flyway is like a **recipe book** for your database:
- Each recipe (migration) is numbered: Recipe 1, Recipe 2, Recipe 3...
- You follow them in order
- You never modify a recipe after it's been cooked (deployed)
- To change something, you add a NEW recipe at the end

---

## 2. How Flyway Works — Step by Step

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLYWAY STARTUP SEQUENCE                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. Application starts                                                     │
│     └── Spring Boot detects flyway-core dependency in pom.xml             │
│     └── Auto-configures Flyway with datasource from application.yml       │
│                                                                             │
│  2. Flyway connects to payflow_merchant database                           │
│                                                                             │
│  3. Flyway looks for table: flyway_schema_history                          │
│     ├── NOT FOUND (first run) → Creates the tracking table                │
│     └── FOUND → Reads which migrations already applied                    │
│                                                                             │
│  4. Flyway scans: classpath:db/migration/                                  │
│     └── Finds: V1, V2, V3, V4 SQL files                                  │
│                                                                             │
│  5. Flyway compares:                                                       │
│     "Which versions are in my files but NOT in flyway_schema_history?"    │
│                                                                             │
│     flyway_schema_history:        Files on disk:                           │
│     (empty — first run)           V1__create_merchants_table.sql          │
│                                   V2__create_api_keys_table.sql           │
│                                   V3__create_webhook_configs_table.sql    │
│                                   V4__create_fee_configs_table.sql        │
│                                                                             │
│     → V1 is NEW → Run it                                                  │
│     → V2 is NEW → Run it                                                  │
│     → V3 is NEW → Run it                                                  │
│     → V4 is NEW → Run it                                                  │
│                                                                             │
│  6. After running each migration, Flyway records it:                       │
│     INSERT INTO flyway_schema_history (version, description, success, ...)│
│                                                                             │
│  7. Next time app starts:                                                  │
│     flyway_schema_history: V1 ✓, V2 ✓, V3 ✓, V4 ✓                      │
│     Files: V1, V2, V3, V4                                                 │
│     → Nothing new → Skip all → Continue startup                           │
│                                                                             │
│  8. Hibernate validates: Entity fields match DB columns? ✓ → App ready   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Flyway Naming Rules

```
V1__create_merchants_table.sql
│ │  │                      │
│ │  │                      └── .sql extension (required)
│ │  └── Description (underscores for spaces)
│ └── Double underscore __ separator (REQUIRED — single underscore FAILS)
└── Version number (must be sequential: V1, V2, V3...)

EXAMPLES:
✅ V1__create_merchants_table.sql        ← correct
✅ V2__create_api_keys_table.sql         ← correct
❌ V1_create_merchants_table.sql         ← WRONG (single underscore)
❌ v1__create_merchants_table.sql        ← WRONG (lowercase v)
❌ V1__Create_Merchants_Table.SQL        ← Works but convention is lowercase
```

### The Golden Rules

| Rule | Why |
|---|---|
| **NEVER edit a migration after it's deployed** | Flyway checksums the file. If content changes, Flyway throws "Migration checksum mismatch" and REFUSES to start |
| **NEVER delete a migration** | Flyway expects all versions to exist. Deleting V2 when V3 references it = schema corruption |
| **NEVER reorder migrations** | V1 must run before V2 (V2's FK references V1's table) |
| **To fix a mistake, create a NEW migration** | E.g., V5__add_phone_to_merchants.sql with `ALTER TABLE merchants ADD COLUMN phone VARCHAR(20)` |
| **V numbers must be sequential** | V1, V2, V3, V4 (gaps are OK: V1, V3, V5 works too) |

---

## 4. Folder Structure After This Part

```
backend/merchant-service/src/main/resources/
├── application.yml                          ← from 7a
└── db/
    └── migration/                           ← YOU CREATE THIS FOLDER
        ├── V1__create_merchants_table.sql    ← YOU CREATE THIS
        ├── V2__create_api_keys_table.sql     ← YOU CREATE THIS
        ├── V3__create_webhook_configs_table.sql ← YOU CREATE THIS
        └── V4__create_fee_configs_table.sql  ← YOU CREATE THIS
```

**Path matters!** Flyway's default search location is `classpath:db/migration`. If you put files in a different folder, Flyway won't find them.

---

## 5. Step-by-Step: V1 — Merchants Table

**File:** `src/main/resources/db/migration/V1__create_merchants_table.sql`

This is the PARENT table — all other tables reference it.

### Line-by-Line

```sql
-- V1: Create merchants table
```

**SQL comments** start with `--`. This is documentation only — PostgreSQL ignores it.

```sql
CREATE TABLE merchants (
```

**`CREATE TABLE merchants`:**
"Create a new table named `merchants` in the current database (payflow_merchant)."

If the table already exists → error. That's why Flyway tracks what's already run — it never runs V1 twice.

```sql
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
```

**WORD BY WORD:**

| Part | Meaning |
|---|---|
| `id` | Column name |
| `UUID` | Data type — PostgreSQL native UUID (128-bit, stored as 16 bytes) |
| `PRIMARY KEY` | This column uniquely identifies each row. Cannot be null. Creates an index automatically. |
| `DEFAULT gen_random_uuid()` | If no value provided during INSERT, PostgreSQL generates a random UUID |

**WHY `gen_random_uuid()` AND `@GeneratedValue(UUID)` — isn't that double?**
Belt and suspenders:
- `@GeneratedValue(UUID)` → Hibernate generates UUID in Java before INSERT
- `DEFAULT gen_random_uuid()` → PostgreSQL generates UUID if Hibernate somehow doesn't

In practice, Hibernate always provides the UUID. The DEFAULT is a safety net for manual SQL inserts.

```sql
    name            VARCHAR(255) NOT NULL,
```

| Part | Meaning |
|---|---|
| `name` | Column name |
| `VARCHAR(255)` | Variable-length string, max 255 characters. Uses only as much storage as the actual string length. |
| `NOT NULL` | This column CANNOT be empty. INSERT without a name → error. |

**WHY 255?** It's a convention — enough for any business name. PostgreSQL doesn't waste space for unused characters (VARCHAR is variable-length).

```sql
    email           VARCHAR(255) NOT NULL UNIQUE,
```

| Part | Meaning |
|---|---|
| `UNIQUE` | No two rows can have the same email. PostgreSQL automatically creates an index for UNIQUE columns. |

**TWO CONSTRAINTS ON ONE COLUMN:**
- `NOT NULL` → email must be provided
- `UNIQUE` → email must be different from all other rows

```sql
    business_type   VARCHAR(100) NOT NULL,
```

Shorter max length (100) because business types are short strings like "RETAIL", "E_COMMERCE", "SAAS".

```sql
    mdr_rate        DOUBLE PRECISION,
```

| Part | Meaning |
|---|---|
| `DOUBLE PRECISION` | PostgreSQL's 64-bit floating point (Java `Double`) |
| No `NOT NULL` | This column CAN be null (MDR rate might not be set at registration) |

**WHY NO `NOT NULL`?** Matches the entity: `private Double mdrRate` (nullable wrapper type). The MDR rate is optional at registration time.

```sql
    active          BOOLEAN NOT NULL DEFAULT TRUE,
```

| Part | Meaning |
|---|---|
| `BOOLEAN` | true or false |
| `NOT NULL` | Must have a value |
| `DEFAULT TRUE` | If not specified during INSERT, defaults to true |

**MAPS TO:** `@Builder.Default private Boolean active = true` in the entity.

```sql
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
```

**`TIMESTAMP WITH TIME ZONE` (aka `TIMESTAMPTZ`):**
Stores a point in time in UTC. When you read it, PostgreSQL converts to your session's timezone.

**WHY WITH TIME ZONE?**
```
WITHOUT timezone: "2024-01-15 10:30:00"  ← Which timezone? IST? UTC? EST? Ambiguous!
WITH timezone:    "2024-01-15 10:30:00+05:30" ← Clearly IST (stored as UTC internally)
```

For a payment system serving multiple timezones, ALWAYS use `TIMESTAMP WITH TIME ZONE`.

**`DEFAULT NOW()`:**
If not provided during INSERT, use the current timestamp. Maps to `@CreationTimestamp` / `@UpdateTimestamp` in the entity.

```sql
);
```

Closes the CREATE TABLE statement.

```sql
CREATE INDEX idx_merchants_email ON merchants(email);
```

**WHAT:** Creates a B-tree index on the `email` column.

**WHY?** Every login/lookup does `WHERE email = ?`. Without an index, PostgreSQL scans ALL rows (slow). With an index, it finds the row in O(log n) time (fast).

**BUT WAIT — UNIQUE already creates an index!**
Yes, the `UNIQUE` constraint on email already creates an implicit unique index. This explicit index is technically redundant but makes the intent clear. Some teams prefer being explicit.

```sql
CREATE INDEX idx_merchants_active ON merchants(active);
```

**WHY?** Admin queries like "show all active merchants" or "show deactivated merchants" filter by `active`. An index on this column speeds up those queries.

---

## 6. Step-by-Step: V2 — API Keys Table

**File:** `src/main/resources/db/migration/V2__create_api_keys_table.sql`

### Line-by-Line

```sql
-- V2: Create api_keys table
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash        VARCHAR(64) NOT NULL UNIQUE,
```

**`VARCHAR(64)`:** SHA-256 produces exactly 64 hexadecimal characters. No more, no less.

```
SHA-256("pk_YWJjZGVm...") = "a3f8b2c1d4e7f6a5b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7"
                               │                                                            │
                               └──────────────── exactly 64 hex characters ─────────────────┘
```

**`UNIQUE`:** Each API key produces a unique hash. The constraint prevents (theoretically impossible) hash collisions.

```sql
    prefix          VARCHAR(8) NOT NULL,
```

**`VARCHAR(8)`:** Exactly 8 characters — the first 8 characters of the raw key after the `pk_` prefix. Used for identification in the dashboard.

```sql
    merchant_id     UUID NOT NULL,
```

The foreign key column — stores the UUID of the merchant this key belongs to.

```sql
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
```

Same pattern as merchants table.

```sql
    CONSTRAINT fk_api_keys_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants(id) ON DELETE CASCADE
```

**THIS IS THE MOST IMPORTANT LINE.**

**WORD BY WORD:**

| Part | Meaning |
|---|---|
| `CONSTRAINT fk_api_keys_merchant` | Give this constraint a name (for error messages and debugging) |
| `FOREIGN KEY (merchant_id)` | "The `merchant_id` column in THIS table..." |
| `REFERENCES merchants(id)` | "...must match an `id` value in the `merchants` table" |
| `ON DELETE CASCADE` | "If a merchant is deleted, automatically delete all their API keys" |

**WHY FOREIGN KEY?**
Without FK: You could INSERT an api_key with `merchant_id = 'uuid-that-doesnt-exist'` → orphaned record, data corruption.
With FK: PostgreSQL rejects the INSERT → `foreign key constraint "fk_api_keys_merchant" violated`.

**WHY ON DELETE CASCADE?**
```
WITHOUT CASCADE:
  DELETE FROM merchants WHERE id = 'uuid-123';
  → ERROR: "Cannot delete merchant — api_keys still reference it"
  → You must delete all keys first, then the merchant

WITH CASCADE:
  DELETE FROM merchants WHERE id = 'uuid-123';
  → PostgreSQL automatically deletes all api_keys where merchant_id = 'uuid-123'
  → Clean, atomic, no orphaned records
```

**WHY V2 DEPENDS ON V1:**
V2 says `REFERENCES merchants(id)`. If the `merchants` table doesn't exist yet, this fails. That's why V1 (merchants) runs before V2 (api_keys). **Flyway version order matters!**

```sql
);
```

```sql
CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);
```

**THE MOST IMPORTANT INDEX IN THE MERCHANT SERVICE.**

Every single API call to PayFlow includes an API key:
```
X-API-Key: pk_YWJjZGVm...
```

The Payment Service validates it by:
1. Hash the key: SHA-256("pk_YWJjZGVm...") → "a3f8b2c1..."
2. Look up: `SELECT * FROM api_keys WHERE key_hash = 'a3f8b2c1...'`

This query runs on EVERY API request. Without an index → full table scan. With an index → instant lookup.

```sql
CREATE INDEX idx_api_keys_merchant_id ON api_keys(merchant_id);
```

For queries like "list all API keys for merchant X" → `WHERE merchant_id = ?`.

```sql
CREATE INDEX idx_api_keys_prefix ON api_keys(prefix);
```

For admin lookups: "find the key with prefix YWJjZGVm" → `WHERE prefix = ?`.

---

## 7. Step-by-Step: V3 — Webhook Configs Table

**File:** `src/main/resources/db/migration/V3__create_webhook_configs_table.sql`

### Line-by-Line

```sql
-- V3: Create webhook_configs table
CREATE TABLE webhook_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    url             VARCHAR(2048) NOT NULL,
```

**`VARCHAR(2048)`:** Webhook URLs can be long (with query parameters, paths, etc.). 2048 is the max URL length most browsers/servers support.

```sql
    secret          VARCHAR(255) NOT NULL,
```

The HMAC signing secret (format: `"whsec_YWJjZGVm..."`). 255 chars is enough for base64-encoded 32-byte secret + prefix.

```sql
    events          TEXT[] NOT NULL DEFAULT '{}',
```

**THIS IS POSTGRESQL-SPECIFIC.**

| Part | Meaning |
|---|---|
| `TEXT[]` | PostgreSQL native array of text strings |
| `NOT NULL` | The array itself can't be null |
| `DEFAULT '{}'` | Default is an empty array (not null, just empty) |

**WHAT'S STORED:**
```sql
-- Row 1: Merchant listens for 3 events
events = '{payment.authorized,payment.captured,payment.refunded}'

-- Row 2: Merchant only cares about captures
events = '{payment.captured}'

-- Row 3: New webhook, no events configured yet
events = '{}'
```

**WHY TEXT[] NOT A SEPARATE TABLE?**

| Approach | Tables | Query |
|---|---|---|
| **TEXT[] (our choice)** | 1 table | `SELECT * FROM webhook_configs WHERE merchant_id = ?` |
| Separate events table | 2 tables | `SELECT wc.*, we.event FROM webhook_configs wc JOIN webhook_events we ON wc.id = we.config_id WHERE wc.merchant_id = ?` |

For ~6 fixed event types, an array is simpler and faster. A join table adds complexity without benefit.

**⚠️ PORTABILITY NOTE:** `TEXT[]` is PostgreSQL-specific. If you switch to MySQL, you'd need a different approach (JSON column or separate table). For PayFlow, we're committed to PostgreSQL.

```sql
    merchant_id     UUID NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_webhook_configs_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants(id) ON DELETE CASCADE
);
```

Same FK pattern as api_keys — CASCADE delete removes webhooks when merchant is deleted.

```sql
CREATE INDEX idx_webhook_configs_merchant_id ON webhook_configs(merchant_id);
CREATE INDEX idx_webhook_configs_active ON webhook_configs(active);
```

Two indexes:
1. `merchant_id` — for "list all webhooks for merchant X"
2. `active` — for "find only active webhooks" (Webhook Service needs this when delivering events)

---

## 8. Step-by-Step: V4 — Fee Configs Table

**File:** `src/main/resources/db/migration/V4__create_fee_configs_table.sql`

### Line-by-Line

```sql
-- V4: Create fee_configs table
CREATE TABLE fee_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL UNIQUE,
```

**`NOT NULL UNIQUE`** on a foreign key — this is the **1:1 relationship enforcer**.

```
api_keys:       merchant_id NOT NULL        ← 1:N (many keys per merchant)
webhook_configs: merchant_id NOT NULL       ← 1:N (many webhooks per merchant)
fee_configs:     merchant_id NOT NULL UNIQUE ← 1:1 (ONE fee config per merchant)
```

If you try to INSERT a second fee_config for the same merchant:
```
INSERT INTO fee_configs (merchant_id, ...) VALUES ('uuid-123', ...);  ← ✓ First one works
INSERT INTO fee_configs (merchant_id, ...) VALUES ('uuid-123', ...);  ← ✗ UNIQUE violation!
```

```sql
    mdr_percent     DECIMAL(5, 2) NOT NULL,
    gst_percent     DECIMAL(5, 2) NOT NULL,
```

**`DECIMAL(5, 2)`:**
- 5 = total digits (before + after decimal point)
- 2 = digits after decimal point
- Range: -999.99 to 999.99

**Maps to:** `@Column(precision = 5, scale = 2) private BigDecimal mdrPercent` in the entity.

**WHY DECIMAL NOT DOUBLE PRECISION?**
```sql
-- DOUBLE PRECISION (floating point):
SELECT 1000.50 * 2.0 / 100;  →  20.0099999999999980  ← WRONG!

-- DECIMAL (exact arithmetic):
SELECT 1000.50::DECIMAL * 2.00::DECIMAL / 100;  →  20.01  ← CORRECT!
```

For financial calculations, ALWAYS use `DECIMAL` in the database (maps to `BigDecimal` in Java).

```sql
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_fee_configs_merchant FOREIGN KEY (merchant_id)
        REFERENCES merchants(id) ON DELETE CASCADE
);

CREATE INDEX idx_fee_configs_merchant_id ON fee_configs(merchant_id);
```

Standard FK with CASCADE and index for lookups.

---

## 9. How Migrations Map to Entities

| Migration | Table | Entity | Key Columns |
|---|---|---|---|
| V1 | `merchants` | `Merchant.java` | id, name, email (UQ), business_type, mdr_rate, active |
| V2 | `api_keys` | `ApiKey.java` | id, key_hash (UQ), prefix, merchant_id (FK) |
| V3 | `webhook_configs` | `WebhookConfig.java` | id, url, secret, events (TEXT[]), merchant_id (FK) |
| V4 | `fee_configs` | `FeeConfig.java` | id, merchant_id (FK+UQ), mdr_percent, gst_percent |

### Column Name Mapping (SQL → Java)

| SQL Column | Java Field | Why Different? |
|---|---|---|
| `business_type` | `businessType` | SQL=snake_case, Java=camelCase |
| `key_hash` | `keyHash` | Same convention |
| `merchant_id` | `merchantId` | Same convention |
| `mdr_rate` | `mdrRate` | Same convention |
| `mdr_percent` | `mdrPercent` | Same convention |
| `created_at` | `createdAt` | Same convention |

The `@Column(name = "business_type")` annotation in the entity bridges this naming gap.

---

## 10. What Happens on Application Startup

```
$ mvn spring-boot:run

Console output:
────────────────────────────────────────────────────────
Flyway Community Edition 10.x.y by Redgate

Database: jdbc:postgresql://localhost:5432/payflow_merchant (PostgreSQL 16.x)

Successfully validated 4 migrations (execution time 00:00.023s)

Creating Schema History table: "public"."flyway_schema_history"

Current version of schema "public": << Empty Schema >>

Migrating schema "public" to version "1 - create merchants table"
Migrating schema "public" to version "2 - create api keys table"
Migrating schema "public" to version "3 - create webhook configs table"
Migrating schema "public" to version "4 - create fee configs table"

Successfully applied 4 migrations to schema "public" (execution time 00:00.156s)
────────────────────────────────────────────────────────

Hibernate: validating entity mappings...
  Merchant.java ↔ merchants table ✓
  ApiKey.java ↔ api_keys table ✓
  WebhookConfig.java ↔ webhook_configs table ✓
  FeeConfig.java ↔ fee_configs table ✓

Started MerchantServiceApplication in 4.2 seconds
```

**SECOND RUN (same database):**
```
Flyway: Already at version 4. No new migrations to apply.
        ← Skips all 4 migrations (already in flyway_schema_history)
```

---

## 11. Common Mistakes and Rules

| Mistake | What Happens | Fix |
|---|---|---|
| **Edit V1 after it's deployed** | Flyway: "Checksum mismatch for migration V1" → startup FAILS | Never edit deployed migrations. Create V5 with ALTER TABLE. |
| **Delete V2 SQL file** | Flyway: "Applied migration V2 not resolved locally" → FAILS | Never delete migration files. |
| **Single underscore: `V1_name.sql`** | Flyway doesn't recognize it as a migration → table not created | Use DOUBLE underscore: `V1__name.sql` |
| **V2 runs before V1** | V2 references merchants(id) which doesn't exist yet → SQL error | Flyway runs in version order. Make sure FKs point to already-created tables. |
| **Wrong folder path** | Flyway finds 0 migrations → no tables → Hibernate validation fails | Files must be in `src/main/resources/db/migration/` |
| **TIMESTAMP without TIME ZONE** | Times stored without timezone → ambiguous in multi-timezone system | Always use `TIMESTAMP WITH TIME ZONE` |
| **DOUBLE PRECISION for money** | `1000.50 * 2.0 / 100 = 20.009999...` | Use `DECIMAL(5,2)` for money |

---

## 12. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **What Flyway does** | Runs versioned SQL scripts in order on application startup |
| 2 | **Naming: V1__description.sql** | Version + double underscore + description + .sql |
| 3 | **flyway_schema_history** | Tracking table that records which migrations have already run |
| 4 | **Never edit deployed migrations** | Flyway checksums files — any change = startup failure |
| 5 | **PRIMARY KEY** | Unique identifier for each row, auto-creates index |
| 6 | **NOT NULL** | Column cannot be empty |
| 7 | **UNIQUE** | No two rows can have the same value in this column |
| 8 | **DEFAULT** | Value used when INSERT doesn't provide one |
| 9 | **FOREIGN KEY** | Ensures referential integrity — merchant_id must exist in merchants table |
| 10 | **ON DELETE CASCADE** | Delete parent → automatically delete all children |
| 11 | **UNIQUE on FK** | Enforces 1:1 relationship (fee_configs.merchant_id) |
| 12 | **TIMESTAMP WITH TIME ZONE** | Stores UTC, converts on read — essential for multi-timezone |
| 13 | **DECIMAL(5,2)** | Exact arithmetic for money (never use DOUBLE for financial data) |
| 14 | **TEXT[]** | PostgreSQL native array — simpler than join table for small fixed sets |
| 15 | **CREATE INDEX** | Speeds up WHERE queries on frequently searched columns |
| 16 | **gen_random_uuid()** | PostgreSQL function that generates a random UUID |
| 17 | **Migration order matters** | V1 creates merchants, V2 references it — order is critical |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part07-merchant-service-overview.md) | Merchant Service Overview |
| [Part 7a](./phase4-part07a-merchant-project-setup.md) | Project Setup |
| [Part 7b](./phase4-part07b-merchant-entities.md) | Entities |
| **Part 7c** | **Flyway Migrations** (You are here) |
| [Part 7d](./phase4-part07d-merchant-repositories.md) | Repositories |
| [Part 7e](./phase4-part07e-merchant-dtos-mapper.md) | DTOs + Mapper |
| [Part 7f](./phase4-part07f-merchant-services-tests.md) | Services + Tests |
| [Part 7g](./phase4-part07g-merchant-controller-docker.md) | Controller + Docker |

---

*Next: [Part 7d — Repositories (Data Access)](./phase4-part07d-merchant-repositories.md) →*
