# 🏗️ Phase 4 Part 7a: Merchant Service — Entities

> **"A merchant is more than a name — it's a verified business identity with keys, hooks, and fees."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Previous** | [phase4-part06c-identity-controller-tests.md](./phase4-part06c-identity-controller-tests.md) |
| **Next** | [phase4-part07b-merchant-services.md](./phase4-part07b-merchant-services.md) |

---

## 📖 Table of Contents

1. [Overview](#1-overview)
2. [MerchantStatus Enum](#2-merchantstatus-enum)
3. [Merchant Entity](#3-merchant-entity)
4. [ApiKey Entity](#4-apikey-entity)
5. [WebhookConfig Entity](#5-webhookconfig-entity)
6. [FeeConfig Entity](#6-feeconfig-entity)
7. [Entity Relationship Diagram](#7-entity-relationship-diagram)
8. [Flyway Migration V1 — Merchants Table](#8-flyway-migration-v1--merchants-table)
9. [Flyway Migration V2 — API Keys Table](#9-flyway-migration-v2--api-keys-table)
10. [Flyway Migration V3 — Webhook & Fee Config](#10-flyway-migration-v3--webhook--fee-config)
11. [What You Learned](#11-what-you-learned)

---

## 1. Overview

The Merchant Service manages the business entities that accept payments through PayFlow.
Each merchant goes through an onboarding process and receives API keys for integration.

**Merchant Domain Model:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                      MERCHANT SERVICE                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐  1:N  ┌──────────────┐                           │
│  │   Merchant   │───────│    ApiKey     │                           │
│  │              │       └──────────────┘                           │
│  │  userId      │                                                   │
│  │  businessName│  1:N  ┌──────────────┐                           │
│  │  panNumber   │───────│WebhookConfig │                           │
│  │  gstNumber   │       └──────────────┘                           │
│  │  bankAccount │                                                   │
│  │  status      │  1:1  ┌──────────────┐                           │
│  │              │───────│  FeeConfig   │                           │
│  └──────────────┘       └──────────────┘                           │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**File Structure:**

```
backend/merchant-service/src/main/java/com/payflow/merchant/
├── model/
│   ├── Merchant.java
│   ├── ApiKey.java
│   ├── WebhookConfig.java
│   ├── FeeConfig.java
│   └── MerchantStatus.java
├── repository/
│   ├── MerchantRepository.java
│   ├── ApiKeyRepository.java
│   ├── WebhookConfigRepository.java
│   └── FeeConfigRepository.java
└── ...
```

---

## 2. MerchantStatus Enum

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/MerchantStatus.java`

```java
package com.payflow.merchant.model;

/**
 * Represents the lifecycle status of a merchant account.
 * 
 * State transitions:
 *   PENDING → ACTIVE (after KYC verification)
 *   ACTIVE → SUSPENDED (policy violation or risk trigger)
 *   SUSPENDED → ACTIVE (after review)
 *   ACTIVE → DEACTIVATED (merchant request or permanent ban)
 *   SUSPENDED → DEACTIVATED (permanent closure)
 */
public enum MerchantStatus {

    /** Initial state — KYC documents submitted, awaiting verification */
    PENDING,

    /** Verified and operational — can process payments */
    ACTIVE,

    /** Temporarily frozen — pending investigation */
    SUSPENDED,

    /** Permanently closed — no further transactions allowed */
    DEACTIVATED;

    /**
     * Validates whether a state transition is allowed.
     */
    public boolean canTransitionTo(MerchantStatus target) {
        return switch (this) {
            case PENDING -> target == ACTIVE;
            case ACTIVE -> target == SUSPENDED || target == DEACTIVATED;
            case SUSPENDED -> target == ACTIVE || target == DEACTIVATED;
            case DEACTIVATED -> false; // Terminal state
        };
    }
}
```

**State Machine Diagram:**

```
         ┌──────────────────────────────────────────────┐
         │           MERCHANT STATUS LIFECYCLE           │
         ├──────────────────────────────────────────────┤
         │                                              │
         │  ┌─────────┐    KYC Approved    ┌────────┐  │
         │  │ PENDING │───────────────────▶│ ACTIVE │  │
         │  └─────────┘                    └───┬────┘  │
         │                                     │    ▲   │
         │                          Suspend    │    │   │
         │                                     ▼    │   │
         │                               ┌──────────┴┐  │
         │                               │ SUSPENDED │  │
         │                               └─────┬─────┘  │
         │                                     │        │
         │               Deactivate            ▼        │
         │                               ┌───────────┐  │
         │                               │DEACTIVATED│  │
         │                               └───────────┘  │
         │                               (Terminal)     │
         └──────────────────────────────────────────────┘
```

---

## 3. Merchant Entity

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/Merchant.java`

```java
package com.payflow.merchant.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core merchant entity representing a business on the PayFlow platform.
 * 
 * Design decisions:
 * - userId links to Identity Service (no FK across services — eventual consistency)
 * - PAN/GST for Indian KYC compliance
 * - Bank details for settlement payouts
 * - webhookUrl/webhookSecret for real-time notifications
 */
@Entity
@Table(
    name = "merchants",
    indexes = {
        @Index(name = "idx_merchants_user_id", columnList = "user_id", unique = true),
        @Index(name = "idx_merchants_status", columnList = "status"),
        @Index(name = "idx_merchants_pan", columnList = "pan_number")
    }
)
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "business_name", nullable = false, length = 200)
    private String businessName;

    @Column(name = "business_type", nullable = false, length = 50)
    private String businessType;

    @Column(name = "pan_number", nullable = false, length = 10)
    private String panNumber;

    @Column(name = "gst_number", length = 15)
    private String gstNumber;

    @Column(name = "bank_account", nullable = false, length = 20)
    private String bankAccount;

    @Column(name = "bank_ifsc", nullable = false, length = 11)
    private String bankIfsc;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MerchantStatus status = MerchantStatus.PENDING;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    @Column(name = "webhook_secret", length = 64)
    private String webhookSecret;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ─── Constructors ────────────────────────────────────────────

    protected Merchant() {}

    public Merchant(UUID userId, String businessName, String businessType,
                    String panNumber, String gstNumber,
                    String bankAccount, String bankIfsc) {
        this.userId = userId;
        this.businessName = businessName;
        this.businessType = businessType;
        this.panNumber = panNumber;
        this.gstNumber = gstNumber;
        this.bankAccount = bankAccount;
        this.bankIfsc = bankIfsc;
        this.status = MerchantStatus.PENDING;
    }

    // ─── Business Methods ────────────────────────────────────────

    public void activate() {
        if (!status.canTransitionTo(MerchantStatus.ACTIVE)) {
            throw new IllegalStateException(
                "Cannot activate merchant in status: " + status);
        }
        this.status = MerchantStatus.ACTIVE;
    }

    public void suspend() {
        if (!status.canTransitionTo(MerchantStatus.SUSPENDED)) {
            throw new IllegalStateException(
                "Cannot suspend merchant in status: " + status);
        }
        this.status = MerchantStatus.SUSPENDED;
    }

    public void deactivate() {
        if (!status.canTransitionTo(MerchantStatus.DEACTIVATED)) {
            throw new IllegalStateException(
                "Cannot deactivate merchant in status: " + status);
        }
        this.status = MerchantStatus.DEACTIVATED;
    }

    // ─── Getters & Setters ───────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public String getPanNumber() { return panNumber; }
    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String gstNumber) { this.gstNumber = gstNumber; }
    public String getBankAccount() { return bankAccount; }
    public void setBankAccount(String bankAccount) { this.bankAccount = bankAccount; }
    public String getBankIfsc() { return bankIfsc; }
    public void setBankIfsc(String bankIfsc) { this.bankIfsc = bankIfsc; }
    public MerchantStatus getStatus() { return status; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

---

## 4. ApiKey Entity

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/ApiKey.java`

```java
package com.payflow.merchant.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API Key entity for merchant authentication.
 * 
 * Security design:
 * - Only the SHA-256 HASH of the key is stored (never the raw key)
 * - keyPrefix (first 8 chars) allows identification without revealing the key
 * - Raw key is shown to merchant ONCE at creation time
 * - Keys can be revoked instantly
 */
@Entity
@Table(
    name = "api_keys",
    indexes = {
        @Index(name = "idx_api_keys_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_api_keys_key_hash", columnList = "key_hash", unique = true),
        @Index(name = "idx_api_keys_key_prefix", columnList = "key_prefix")
    }
)
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 8)
    private String keyPrefix;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ─── Constructors ────────────────────────────────────────────

    protected ApiKey() {}

    public ApiKey(UUID merchantId, String keyHash, String keyPrefix, String name) {
        this.merchantId = merchantId;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.name = name;
        this.active = true;
    }

    // ─── Business Methods ────────────────────────────────────────

    public void revoke() {
        this.active = false;
    }

    public void recordUsage() {
        this.lastUsedAt = LocalDateTime.now();
    }

    // ─── Getters ─────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getKeyHash() { return keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

**API Key Security Model:**

```
┌─────────────────────────────────────────────────────────────────┐
│                  API KEY LIFECYCLE                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Generation:                                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Raw Key: pk_live_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6       │   │
│  │          ├──────┤├────────────────────────────────────┤   │   │
│  │          prefix        random payload (32 bytes)       │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Storage:                                                       │
│  ┌─────────────────────────────────────────┐                    │
│  │ key_prefix: "pk_live_"                  │                    │
│  │ key_hash:   SHA-256(raw_key) → 64 hex   │                    │
│  │ name:       "Production Key"            │                    │
│  └─────────────────────────────────────────┘                    │
│                                                                 │
│  Validation:                                                    │
│  1. Client sends: X-Api-Key: pk_live_a1b2c3d4...               │
│  2. Gateway computes: SHA-256(received_key)                     │
│  3. Lookup: SELECT * FROM api_keys WHERE key_hash = ?           │
│  4. Check: active = true AND merchant.status = ACTIVE           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. WebhookConfig Entity

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/WebhookConfig.java`

```java
package com.payflow.merchant.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Webhook configuration for real-time event notifications to merchants.
 * 
 * Events are signed with HMAC-SHA256 using the webhook secret,
 * allowing merchants to verify the authenticity of incoming webhooks.
 */
@Entity
@Table(
    name = "webhook_configs",
    indexes = {
        @Index(name = "idx_webhook_configs_merchant_id", columnList = "merchant_id")
    }
)
public class WebhookConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "secret", nullable = false, length = 64)
    private String secret;

    @ElementCollection
    @CollectionTable(
        name = "webhook_events",
        joinColumns = @JoinColumn(name = "webhook_config_id")
    )
    @Column(name = "event_type", length = 50)
    private List<String> events;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ─── Constructors ────────────────────────────────────────────

    protected WebhookConfig() {}

    public WebhookConfig(UUID merchantId, String url, String secret, List<String> events) {
        this.merchantId = merchantId;
        this.url = url;
        this.secret = secret;
        this.events = events;
        this.active = true;
    }

    // ─── Getters & Setters ───────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecret() { return secret; }
    public List<String> getEvents() { return events; }
    public void setEvents(List<String> events) { this.events = events; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

**Supported Webhook Events:**

| Event | Trigger |
|-------|---------|
| `payment.authorized` | Payment successfully authorized |
| `payment.captured` | Payment captured (money debited) |
| `payment.failed` | Payment authorization failed |
| `payment.refunded` | Refund processed successfully |
| `order.created` | New order created |
| `order.expired` | Order expired without payment |

---

## 6. FeeConfig Entity

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/model/FeeConfig.java`

```java
package com.payflow.merchant.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fee configuration for a merchant.
 * 
 * Fee calculation:
 *   Total Fee = (transaction_amount × platformFeePercent / 100)
 *             + (platform_fee × gstPercent / 100)
 *             + fixedFeePerTxn
 * 
 * Example for ₹1000 transaction with 2% platform + 18% GST + ₹2 fixed:
 *   Platform fee = 1000 × 0.02 = ₹20
 *   GST on fee   = 20 × 0.18  = ₹3.60
 *   Fixed fee    = ₹2.00
 *   Total fee    = ₹25.60
 *   Merchant receives = ₹974.40
 */
@Entity
@Table(
    name = "fee_configs",
    indexes = {
        @Index(name = "idx_fee_configs_merchant_id", columnList = "merchant_id", unique = true)
    }
)
public class FeeConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private UUID merchantId;

    @Column(name = "platform_fee_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal platformFeePercent;

    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercent;

    @Column(name = "fixed_fee_per_txn", nullable = false, precision = 10, scale = 2)
    private BigDecimal fixedFeePerTxn;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ─── Constructors ────────────────────────────────────────────

    protected FeeConfig() {}

    public FeeConfig(UUID merchantId, BigDecimal platformFeePercent,
                     BigDecimal gstPercent, BigDecimal fixedFeePerTxn) {
        this.merchantId = merchantId;
        this.platformFeePercent = platformFeePercent;
        this.gstPercent = gstPercent;
        this.fixedFeePerTxn = fixedFeePerTxn;
    }

    // ─── Fee Calculation ─────────────────────────────────────────

    /**
     * Calculates the total fee for a given transaction amount.
     */
    public BigDecimal calculateTotalFee(BigDecimal transactionAmount) {
        BigDecimal platformFee = transactionAmount
            .multiply(platformFeePercent)
            .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);

        BigDecimal gstOnFee = platformFee
            .multiply(gstPercent)
            .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);

        return platformFee.add(gstOnFee).add(fixedFeePerTxn);
    }

    // ─── Getters & Setters ───────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public BigDecimal getPlatformFeePercent() { return platformFeePercent; }
    public void setPlatformFeePercent(BigDecimal p) { this.platformFeePercent = p; }
    public BigDecimal getGstPercent() { return gstPercent; }
    public void setGstPercent(BigDecimal g) { this.gstPercent = g; }
    public BigDecimal getFixedFeePerTxn() { return fixedFeePerTxn; }
    public void setFixedFeePerTxn(BigDecimal f) { this.fixedFeePerTxn = f; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

---

## 7. Entity Relationship Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    MERCHANT SERVICE DATABASE SCHEMA                       │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────────────────────┐                                         │
│  │         merchants           │                                         │
│  ├─────────────────────────────┤                                         │
│  │ PK  id            UUID      │                                         │
│  │     user_id       UUID (UQ) │ ◄── From Identity Service               │
│  │     business_name VARCHAR   │                                         │
│  │     business_type VARCHAR   │                                         │
│  │     pan_number    VARCHAR   │                                         │
│  │     gst_number    VARCHAR   │                                         │
│  │     bank_account  VARCHAR   │                                         │
│  │     bank_ifsc     VARCHAR   │                                         │
│  │     status        VARCHAR   │ ◄── PENDING|ACTIVE|SUSPENDED|DEACTIVATED│
│  │     webhook_url   VARCHAR   │                                         │
│  │     webhook_secret VARCHAR  │                                         │
│  │     created_at    TIMESTAMP │                                         │
│  │     updated_at    TIMESTAMP │                                         │
│  └───────────┬─────────────────┘                                         │
│              │                                                           │
│    ┌─────────┼─────────────────────────────────┐                         │
│    │         │                                 │                         │
│    ▼ 1:N     ▼ 1:N                             ▼ 1:1                     │
│  ┌───────────────┐  ┌──────────────────┐  ┌──────────────────┐          │
│  │   api_keys    │  │ webhook_configs  │  │   fee_configs    │          │
│  ├───────────────┤  ├──────────────────┤  ├──────────────────┤          │
│  │PK id     UUID │  │PK id       UUID  │  │PK id        UUID │          │
│  │FK merchant_id │  │FK merchant_id    │  │FK merchant_id(UQ)│          │
│  │   key_hash    │  │   url            │  │   platform_fee_% │          │
│  │   key_prefix  │  │   secret         │  │   gst_percent    │          │
│  │   name        │  │   events (list)  │  │   fixed_fee_txn  │          │
│  │   active      │  │   active         │  │   created_at     │          │
│  │   last_used_at│  │   created_at     │  │   updated_at     │          │
│  │   created_at  │  │   updated_at     │  └──────────────────┘          │
│  └───────────────┘  └──────────────────┘                                 │
│                            │                                             │
│                            ▼ 1:N                                         │
│                     ┌──────────────────┐                                 │
│                     │  webhook_events  │ (ElementCollection)             │
│                     ├──────────────────┤                                 │
│                     │FK webhook_config_id│                               │
│                     │   event_type     │                                 │
│                     └──────────────────┘                                 │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Flyway Migration V1 — Merchants Table

**File:** `backend/merchant-service/src/main/resources/db/migration/V1__create_merchants_table.sql`

```sql
-- ============================================================================
-- V1__create_merchants_table.sql
-- Creates the core merchants table for the PayFlow Merchant Service
-- ============================================================================

CREATE TABLE merchants (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID            NOT NULL,
    business_name   VARCHAR(200)    NOT NULL,
    business_type   VARCHAR(50)     NOT NULL,
    pan_number      VARCHAR(10)     NOT NULL,
    gst_number      VARCHAR(15),
    bank_account    VARCHAR(20)     NOT NULL,
    bank_ifsc       VARCHAR(11)     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    webhook_url     VARCHAR(500),
    webhook_secret  VARCHAR(64),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints
    CONSTRAINT uq_merchants_user_id UNIQUE (user_id),
    CONSTRAINT chk_merchants_status CHECK (
        status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')
    ),
    CONSTRAINT chk_merchants_pan CHECK (
        pan_number ~ '^[A-Z]{5}[0-9]{4}[A-Z]{1}$'
    ),
    CONSTRAINT chk_merchants_ifsc CHECK (
        bank_ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$'
    )
);

-- ─── Indexes ─────────────────────────────────────────────────────────────────

CREATE UNIQUE INDEX idx_merchants_user_id ON merchants (user_id);
CREATE INDEX idx_merchants_status ON merchants (status);
CREATE INDEX idx_merchants_pan ON merchants (pan_number);
CREATE INDEX idx_merchants_business_name ON merchants (business_name);

-- ─── Comments ────────────────────────────────────────────────────────────────

COMMENT ON TABLE merchants IS 'Business entities that accept payments via PayFlow';
COMMENT ON COLUMN merchants.pan_number IS 'Indian Permanent Account Number (10 chars)';
COMMENT ON COLUMN merchants.gst_number IS 'GST Identification Number (15 chars, optional)';
COMMENT ON COLUMN merchants.bank_ifsc IS 'Indian Financial System Code (11 chars)';
```

---

## 9. Flyway Migration V2 — API Keys Table

**File:** `backend/merchant-service/src/main/resources/db/migration/V2__create_api_keys_table.sql`

```sql
-- ============================================================================
-- V2__create_api_keys_table.sql
-- Creates the API keys table for merchant authentication
-- ============================================================================

CREATE TABLE api_keys (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID            NOT NULL,
    key_hash        VARCHAR(64)     NOT NULL,
    key_prefix      VARCHAR(8)      NOT NULL,
    name            VARCHAR(100)    NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    last_used_at    TIMESTAMP,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Foreign Key
    CONSTRAINT fk_api_keys_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (id)
        ON DELETE CASCADE,

    -- Unique hash
    CONSTRAINT uq_api_keys_key_hash UNIQUE (key_hash)
);

-- ─── Indexes ─────────────────────────────────────────────────────────────────

CREATE INDEX idx_api_keys_merchant_id ON api_keys (merchant_id);
CREATE UNIQUE INDEX idx_api_keys_key_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_key_prefix ON api_keys (key_prefix);
CREATE INDEX idx_api_keys_active ON api_keys (active) WHERE active = TRUE;

-- ─── Comments ────────────────────────────────────────────────────────────────

COMMENT ON TABLE api_keys IS 'SHA-256 hashed API keys for merchant authentication';
COMMENT ON COLUMN api_keys.key_hash IS 'SHA-256 hash of the raw API key (never store raw)';
COMMENT ON COLUMN api_keys.key_prefix IS 'First 8 chars of raw key for identification';
```

---

## 10. Flyway Migration V3 — Webhook & Fee Config

**File:** `backend/merchant-service/src/main/resources/db/migration/V3__create_webhook_fee_tables.sql`

```sql
-- ============================================================================
-- V3__create_webhook_fee_tables.sql
-- Creates webhook configuration and fee configuration tables
-- ============================================================================

-- ─── Webhook Configs ─────────────────────────────────────────────────────────

CREATE TABLE webhook_configs (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID            NOT NULL,
    url             VARCHAR(500)    NOT NULL,
    secret          VARCHAR(64)     NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_webhook_configs_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_webhook_configs_merchant_id ON webhook_configs (merchant_id);

-- ─── Webhook Events (ElementCollection) ──────────────────────────────────────

CREATE TABLE webhook_events (
    webhook_config_id UUID         NOT NULL,
    event_type        VARCHAR(50)  NOT NULL,

    CONSTRAINT fk_webhook_events_config
        FOREIGN KEY (webhook_config_id) REFERENCES webhook_configs (id)
        ON DELETE CASCADE,

    CONSTRAINT uq_webhook_events UNIQUE (webhook_config_id, event_type)
);

-- ─── Fee Configs ─────────────────────────────────────────────────────────────

CREATE TABLE fee_configs (
    id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id             UUID            NOT NULL,
    platform_fee_percent    DECIMAL(5,2)    NOT NULL DEFAULT 2.00,
    gst_percent             DECIMAL(5,2)    NOT NULL DEFAULT 18.00,
    fixed_fee_per_txn       DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fee_configs_merchant
        FOREIGN KEY (merchant_id) REFERENCES merchants (id)
        ON DELETE CASCADE,

    CONSTRAINT uq_fee_configs_merchant_id UNIQUE (merchant_id),

    CONSTRAINT chk_fee_configs_platform_fee CHECK (
        platform_fee_percent >= 0 AND platform_fee_percent <= 10
    ),
    CONSTRAINT chk_fee_configs_gst CHECK (
        gst_percent >= 0 AND gst_percent <= 30
    )
);

CREATE UNIQUE INDEX idx_fee_configs_merchant_id ON fee_configs (merchant_id);

-- ─── Comments ────────────────────────────────────────────────────────────────

COMMENT ON TABLE webhook_configs IS 'Webhook endpoints for merchant event notifications';
COMMENT ON TABLE fee_configs IS 'Per-merchant fee configuration for transaction charges';
COMMENT ON COLUMN fee_configs.platform_fee_percent IS 'PayFlow platform fee (0-10%)';
COMMENT ON COLUMN fee_configs.gst_percent IS 'GST on platform fee (typically 18%)';
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | Merchant status lifecycle | State machine with validated transitions (no direct PENDING→DEACTIVATED) |
| 2 | API Key security | Store SHA-256 hash only; show raw key once at creation |
| 3 | Key prefix pattern | First 8 chars identify key without revealing it |
| 4 | WebhookConfig with events | `@ElementCollection` maps list to separate table |
| 5 | FeeConfig calculation | Platform fee + GST on fee + fixed fee per transaction |
| 6 | CHECK constraints in SQL | Validate PAN format, IFSC format, fee ranges at DB level |
| 7 | Cross-service references | `userId` links to Identity Service without FK (eventual consistency) |
| 8 | BigDecimal for money | Never use float/double for financial calculations |

---

## 📚 Document Index

| Document | Title |
|----------|-------|
| [Phase 4 Part 6c](./phase4-part06c-identity-controller-tests.md) | Identity Service — Controller & Tests |
| **Phase 4 Part 7a** | **Merchant Service — Entities** (You are here) |
| [Phase 4 Part 7b](./phase4-part07b-merchant-services.md) | Merchant Service — Services & Controller |
| [Phase 4 Part 8a](./phase4-part08a-payment-entities.md) | Payment Service — Entities |
| [Phase 4 Part 8b](./phase4-part08b-payment-services-kafka.md) | Payment Service — Services & Kafka |

---

## 🚀 Next Steps

In **[Phase 4 Part 7b](./phase4-part07b-merchant-services.md)**, we will implement:

1. `MerchantService` — CRUD with status transitions
2. `ApiKeyService` — Generation with SHA-256 hashing
3. Controller endpoints for merchant management
4. MapStruct mapper for entity ↔ DTO conversion
5. Kafka event publishing on merchant onboarding
