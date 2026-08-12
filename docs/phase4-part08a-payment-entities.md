# 🏗️ Phase 4 Part 8a: Payment Service — Entities

> **"Money moves through states, not steps — authorize, capture, settle, and every transition tells a story."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Previous** | [phase4-part07b-merchant-services.md](./phase4-part07b-merchant-services.md) |
| **Next** | [phase4-part08b-payment-services-kafka.md](./phase4-part08b-payment-services-kafka.md) |

---

## 📖 Table of Contents

1. [Overview](#1-overview)
2. [Domain Model Architecture](#2-domain-model-architecture)
3. [OrderStatus Enum](#3-orderstatus-enum)
4. [PaymentStatus Enum](#4-paymentstatus-enum)
5. [RefundStatus Enum](#5-refundstatus-enum)
6. [PaymentMethod Enum](#6-paymentmethod-enum)
7. [Order Entity](#7-order-entity)
8. [Payment Entity](#8-payment-entity)
9. [Refund Entity](#9-refund-entity)
10. [Payment State Machine Diagram](#10-payment-state-machine-diagram)
11. [Flyway Migrations](#11-flyway-migrations)
12. [What You Learned](#12-what-you-learned)

---

## 1. Overview

The Payment Service is the core transaction engine of PayFlow. It manages the complete
lifecycle of a payment from order creation through authorization, capture, and refund.

**Key Design Principles:**

| Principle | Implementation |
|-----------|---------------|
| Idempotency | Every order has a unique `idempotencyKey` — retry-safe |
| State machine | Payments follow strict state transitions |
| Separation | Orders (intent) vs Payments (execution) vs Refunds (reversal) |
| Audit trail | Every state change is timestamped |
| Financial precision | All money fields use `BigDecimal` with 2 decimal places |

**File Structure:**

```
backend/payment-service/src/main/java/com/payflow/payment/
├── model/
│   ├── Order.java
│   ├── Payment.java
│   ├── Refund.java
│   ├── OrderStatus.java
│   ├── PaymentStatus.java
│   ├── RefundStatus.java
│   └── PaymentMethod.java
├── repository/
│   ├── OrderRepository.java
│   ├── PaymentRepository.java
│   └── RefundRepository.java
└── ...
```

---

## 2. Domain Model Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    PAYMENT SERVICE DOMAIN                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────┐                                                    │
│  │      Order      │ "I want to pay ₹1000"                             │
│  ├─────────────────┤                                                    │
│  │ merchantId      │                                                    │
│  │ amount          │                                                    │
│  │ currency        │     1:N                                            │
│  │ status          │─────────────┐                                      │
│  │ idempotencyKey  │             │                                      │
│  │ expiresAt       │             ▼                                      │
│  └─────────────────┘    ┌─────────────────┐                             │
│                         │     Payment     │ "Card was charged ₹1000"    │
│                         ├─────────────────┤                             │
│                         │ orderId         │                             │
│                         │ amount          │                             │
│                         │ capturedAmount  │    1:N                      │
│                         │ refundedAmount  │────────────┐                │
│                         │ status          │            │                │
│                         │ paymentMethod   │            ▼                │
│                         │ cardLastFour    │   ┌─────────────────┐       │
│                         │ cardNetwork     │   │     Refund      │       │
│                         └─────────────────┘   ├─────────────────┤       │
│                                               │ paymentId       │       │
│                                               │ amount          │       │
│                                               │ status          │       │
│                                               │ reason          │       │
│                                               │ idempotencyKey  │       │
│                                               └─────────────────┘       │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. OrderStatus Enum

**File:** `backend/payment-service/src/main/java/com/payflow/payment/model/OrderStatus.java`

```java
package com.payflow.payment.model;

/**
 * Represents the lifecycle of a payment order.
 * 
 * State transitions:
 *   CREATED → ATTEMPTED (payment started)
 *   ATTEMPTED → PAID (payment captured successfully)
 *   ATTEMPTED → FAILED (all payment attempts failed)
 *   CREATED → EXPIRED (no payment within expiry window)
 *   PAID → REFUNDED (full refund processed)
 */
public enum OrderStatus {
    /** Order created, awaiting payment attempt */
    CREATED,

    /** Payment attempt in progress */
    ATTEMPTED,

    /** Payment successfully captured */
    PAID,

    /** All payment attempts failed */
    FAILED,

    /** Order expired without payment (default: 15 minutes) */
    EXPIRED,

    /** Fully refunded after successful payment */
    REFUNDED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == ATTEMPTED || target == EXPIRED;
            case ATTEMPTED -> target == PAID || target == FAILED;
            case PAID -> target == REFUNDED;
            case FAILED, EXPIRED, REFUNDED -> false; // Terminal states
        };
    }
}
```

---

## 4. PaymentStatus Enum

**File:** `backend/payment-service/src/main/java/com/payflow/payment/model/PaymentStatus.java`

```java
package com.payflow.payment.model;

/**
 * Represents the lifecycle of a payment transaction.
 * 
 * Follows the standard two-phase payment flow:
 *   Phase 1: Authorization (reserve funds)
 *   Phase 2: Capture (actually debit funds)
 * 
 * State transitions:
 *   CREATED → AUTHORIZED (bank approved, funds reserved)
 *   AUTHORIZED → CAPTURED (funds debited to merchant)
 *   AUTHORIZED → VOIDED (authorization cancelled before capture)
 *   CREATED → FAILED (bank declined)
 *   CAPTURED → REFUNDED (full refund)
 *   CAPTURED → PARTIALLY_REFUNDED (partial refund)
 */
public enum PaymentStatus {
    /** Payment created, not yet sent to bank */
    CREATED,

    /** Bank authorized — funds reserved on customer's account */
    AUTHORIZED,

    /** Funds captured — money moved to merchant */
    CAPTURED,

    /** Authorization voided — funds released back to customer */
    VOIDED,

    /** Bank declined the transaction */
    FAILED,

    /** Full amount refunded to customer */
    REFUNDED,

    /** Partial amount refunded */
    PARTIALLY_REFUNDED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case CREATED -> target == AUTHORIZED || target == FAILED;
            case AUTHORIZED -> target == CAPTURED || target == VOIDED;
            case CAPTURED -> target == REFUNDED || target == PARTIALLY_REFUNDED;
            case PARTIALLY_REFUNDED -> target == REFUNDED || target == PARTIALLY_REFUNDED;
            case VOIDED, FAILED, REFUNDED -> false; // Terminal states
        };
    }
}
```

---

## 5. RefundStatus Enum

```java
package com.payflow.payment.model;

/**
 * Represents the status of a refund request.
 */
public enum RefundStatus {
    /** Refund initiated, processing with bank */
    PENDING,

    /** Refund successfully processed — money returned to customer */
    PROCESSED,

    /** Refund failed (insufficient balance, bank error) */
    FAILED;
}
```

---

## 6. PaymentMethod Enum

```java
package com.payflow.payment.model;

/**
 * Supported payment methods in PayFlow.
 */
public enum PaymentMethod {
    /** Credit/Debit card payment (Visa, Mastercard, RuPay) */
    CARD,

    /** UPI (Unified Payments Interface) — India-specific */
    UPI,

    /** Net banking (online bank transfer) */
    NET_BANKING,

    /** Digital wallets (Paytm, PhonePe, etc.) */
    WALLET;
}
```

---

## 7. Order Entity

**File:** `backend/payment-service/src/main/java/com/payflow/payment/model/Order.java`

```java
package com.payflow.payment.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a payment order — the merchant's intent to collect a payment.
 * 
 * Design decisions:
 * - idempotencyKey: allows safe retries without duplicate orders
 * - expiresAt: orders auto-expire (default 15 min) to prevent stale state
 * - receiptNumber: merchant-provided reference for reconciliation
 * - Amount is in smallest currency unit concept but stored as BigDecimal
 */
@Entity
@Table(
    name = "orders",
    indexes = {
        @Index(name = "idx_orders_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_orders_idempotency_key", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_orders_status", columnList = "status"),
        @Index(name = "idx_orders_created_at", columnList = "created_at")
    }
)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "customer_phone", length = 15)
    private String customerPhone;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "receipt_number", length = 40)
    private String receiptNumber;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ─── Constructors ────────────────────────────────────────────

    protected Order() {}

    public Order(UUID merchantId, BigDecimal amount, String currency,
                 String customerEmail, String customerPhone,
                 String description, String receiptNumber,
                 String idempotencyKey, LocalDateTime expiresAt) {
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.customerEmail = customerEmail;
        this.customerPhone = customerPhone;
        this.description = description;
        this.receiptNumber = receiptNumber;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = expiresAt;
        this.status = OrderStatus.CREATED;
    }

    // ─── Business Methods ────────────────────────────────────────

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public void transitionTo(OrderStatus newStatus) {
        if (!this.status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                String.format("Cannot transition order from %s to %s", status, newStatus));
        }
        this.status = newStatus;
    }

    // ─── Getters & Setters ───────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public OrderStatus getStatus() { return status; }
    public String getCustomerEmail() { return customerEmail; }
    public String getCustomerPhone() { return customerPhone; }
    public String getDescription() { return description; }
    public String getReceiptNumber() { return receiptNumber; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

---

## 8. Payment Entity

**File:** `backend/payment-service/src/main/java/com/payflow/payment/model/Payment.java`

```java
package com.payflow.payment.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a payment attempt against an order.
 * 
 * One order can have multiple payment attempts (if first fails),
 * but only one can reach AUTHORIZED/CAPTURED state.
 * 
 * Design:
 * - capturedAmount: allows partial capture scenarios
 * - refundedAmount: tracks how much has been refunded (for partial refunds)
 * - authCode/rrn: bank references for reconciliation
 * - cardLastFour/cardNetwork: stored for display (PCI DSS compliance)
 */
@Entity
@Table(
    name = "payments",
    indexes = {
        @Index(name = "idx_payments_order_id", columnList = "order_id"),
        @Index(name = "idx_payments_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_payments_status", columnList = "status"),
        @Index(name = "idx_payments_rrn", columnList = "rrn")
    }
)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "captured_amount", precision = 12, scale = 2)
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    @Column(name = "refunded_amount", precision = 12, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private PaymentStatus status = PaymentStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Column(name = "auth_code", length = 6)
    private String authCode;

    @Column(name = "rrn", length = 12)
    private String rrn;

    @Column(name = "bank_reference", length = 50)
    private String bankReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "card_last_four", length = 4)
    private String cardLastFour;

    @Column(name = "card_network", length = 20)
    private String cardNetwork;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ─── Constructors ────────────────────────────────────────────

    protected Payment() {}

    public Payment(UUID orderId, UUID merchantId, BigDecimal amount,
                   String currency, PaymentMethod paymentMethod) {
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.CREATED;
    }

    // ─── Business Methods ────────────────────────────────────────

    public void authorize(String authCode, String rrn, String bankReference) {
        transitionTo(PaymentStatus.AUTHORIZED);
        this.authCode = authCode;
        this.rrn = rrn;
        this.bankReference = bankReference;
    }

    public void capture(BigDecimal captureAmount) {
        transitionTo(PaymentStatus.CAPTURED);
        this.capturedAmount = captureAmount;
    }

    public void voidPayment() {
        transitionTo(PaymentStatus.VOIDED);
    }

    public void fail(String reason) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    public void addRefund(BigDecimal refundAmount) {
        this.refundedAmount = this.refundedAmount.add(refundAmount);
        if (this.refundedAmount.compareTo(this.capturedAmount) >= 0) {
            transitionTo(PaymentStatus.REFUNDED);
        } else {
            transitionTo(PaymentStatus.PARTIALLY_REFUNDED);
        }
    }

    public BigDecimal getRefundableAmount() {
        return capturedAmount.subtract(refundedAmount);
    }

    private void transitionTo(PaymentStatus newStatus) {
        if (!this.status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                String.format("Cannot transition payment from %s to %s", status, newStatus));
        }
        this.status = newStatus;
    }

    // ─── Getters & Setters ───────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getMerchantId() { return merchantId; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getCapturedAmount() { return capturedAmount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public String getAuthCode() { return authCode; }
    public String getRrn() { return rrn; }
    public String getBankReference() { return bankReference; }
    public String getFailureReason() { return failureReason; }
    public String getCardLastFour() { return cardLastFour; }
    public void setCardLastFour(String cardLastFour) { this.cardLastFour = cardLastFour; }
    public String getCardNetwork() { return cardNetwork; }
    public void setCardNetwork(String cardNetwork) { this.cardNetwork = cardNetwork; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

---

## 9. Refund Entity

**File:** `backend/payment-service/src/main/java/com/payflow/payment/model/Refund.java`

```java
package com.payflow.payment.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a refund against a captured payment.
 * 
 * Supports both full and partial refunds:
 * - Full refund: refund.amount == payment.capturedAmount
 * - Partial refund: refund.amount < payment.capturedAmount
 * 
 * Multiple partial refunds are allowed up to the captured amount.
 */
@Entity
@Table(
    name = "refunds",
    indexes = {
        @Index(name = "idx_refunds_payment_id", columnList = "payment_id"),
        @Index(name = "idx_refunds_order_id", columnList = "order_id"),
        @Index(name = "idx_refunds_idempotency_key", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_refunds_status", columnList = "status")
    }
)
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status = RefundStatus.PENDING;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "bank_reference", length = 50)
    private String bankReference;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ─── Constructors ────────────────────────────────────────────

    protected Refund() {}

    public Refund(UUID paymentId, UUID orderId, BigDecimal amount,
                  String reason, String idempotencyKey) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.amount = amount;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.status = RefundStatus.PENDING;
    }

    // ─── Business Methods ────────────────────────────────────────

    public void markProcessed(String bankReference) {
        this.status = RefundStatus.PROCESSED;
        this.bankReference = bankReference;
    }

    public void markFailed() {
        this.status = RefundStatus.FAILED;
    }

    // ─── Getters ─────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public RefundStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public String getBankReference() { return bankReference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

---

## 10. Payment State Machine Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    PAYMENT STATE MACHINE                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│                           ┌─────────┐                                    │
│                           │ CREATED │                                    │
│                           └────┬────┘                                    │
│                                │                                         │
│               ┌────────────────┼────────────────┐                        │
│               │ Bank Approved  │                │ Bank Declined           │
│               ▼                │                ▼                         │
│        ┌─────────────┐        │         ┌──────────┐                     │
│        │ AUTHORIZED  │        │         │  FAILED  │ (Terminal)           │
│        └──────┬──────┘        │         └──────────┘                     │
│               │                                                          │
│    ┌──────────┼──────────┐                                               │
│    │ Capture  │          │ Void                                          │
│    ▼          │          ▼                                               │
│ ┌──────────┐ │    ┌──────────┐                                           │
│ │ CAPTURED │ │    │  VOIDED  │ (Terminal)                                 │
│ └────┬─────┘ │    └──────────┘                                           │
│      │                                                                   │
│      │ Refund                                                            │
│      ▼                                                                   │
│ ┌─────────────────────┐     ┌──────────────┐                            │
│ │ PARTIALLY_REFUNDED  │────▶│   REFUNDED   │ (Terminal)                  │
│ └─────────────────────┘     └──────────────┘                            │
│    (more refunds possible)    (full amount returned)                     │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ORDER STATUS TRANSITIONS:                                               │
│                                                                          │
│  ┌─────────┐    Payment     ┌───────────┐    Capture    ┌────────┐      │
│  │ CREATED │───started──────▶│ ATTEMPTED │───success────▶│  PAID  │      │
│  └────┬────┘                └─────┬─────┘               └───┬────┘      │
│       │                          │                          │            │
│       │ Timeout                  │ All failed               │ Full refund│
│       ▼                          ▼                          ▼            │
│  ┌─────────┐              ┌──────────┐              ┌──────────┐        │
│  │ EXPIRED │              │  FAILED  │              │ REFUNDED │        │
│  └─────────┘              └──────────┘              └──────────┘        │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 11. Flyway Migrations

### V1 — Orders Table

**File:** `backend/payment-service/src/main/resources/db/migration/V1__create_orders_table.sql`

```sql
-- ============================================================================
-- V1__create_orders_table.sql
-- Creates the orders table for the PayFlow Payment Service
-- ============================================================================

CREATE TABLE orders (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID            NOT NULL,
    amount          DECIMAL(12,2)   NOT NULL,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'INR',
    status          VARCHAR(20)     NOT NULL DEFAULT 'CREATED',
    customer_email  VARCHAR(255),
    customer_phone  VARCHAR(15),
    description     VARCHAR(500),
    receipt_number  VARCHAR(40),
    idempotency_key VARCHAR(64)     NOT NULL,
    expires_at      TIMESTAMP       NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_orders_amount CHECK (amount > 0),
    CONSTRAINT chk_orders_currency CHECK (currency IN ('INR', 'USD', 'EUR', 'GBP')),
    CONSTRAINT chk_orders_status CHECK (
        status IN ('CREATED', 'ATTEMPTED', 'PAID', 'FAILED', 'EXPIRED', 'REFUNDED')
    )
);

CREATE INDEX idx_orders_merchant_id ON orders (merchant_id);
CREATE UNIQUE INDEX idx_orders_idempotency_key ON orders (idempotency_key);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);
CREATE INDEX idx_orders_expires_at ON orders (expires_at) WHERE status = 'CREATED';
```

### V2 — Payments Table

**File:** `backend/payment-service/src/main/resources/db/migration/V2__create_payments_table.sql`

```sql
-- ============================================================================
-- V2__create_payments_table.sql
-- Creates the payments table for tracking payment attempts
-- ============================================================================

CREATE TABLE payments (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID            NOT NULL,
    merchant_id     UUID            NOT NULL,
    amount          DECIMAL(12,2)   NOT NULL,
    captured_amount DECIMAL(12,2)   DEFAULT 0.00,
    refunded_amount DECIMAL(12,2)   DEFAULT 0.00,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'INR',
    status          VARCHAR(25)     NOT NULL DEFAULT 'CREATED',
    payment_method  VARCHAR(20)     NOT NULL,
    auth_code       VARCHAR(6),
    rrn             VARCHAR(12),
    bank_reference  VARCHAR(50),
    failure_reason  VARCHAR(500),
    card_last_four  VARCHAR(4),
    card_network    VARCHAR(20),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_payments_status CHECK (
        status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'VOIDED',
                   'FAILED', 'REFUNDED', 'PARTIALLY_REFUNDED')
    ),
    CONSTRAINT chk_payments_method CHECK (
        payment_method IN ('CARD', 'UPI', 'NET_BANKING', 'WALLET')
    )
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
CREATE INDEX idx_payments_merchant_id ON payments (merchant_id);
CREATE INDEX idx_payments_status ON payments (status);
CREATE INDEX idx_payments_rrn ON payments (rrn);
CREATE INDEX idx_payments_created_at ON payments (created_at DESC);
```

### V3 — Refunds Table

**File:** `backend/payment-service/src/main/resources/db/migration/V3__create_refunds_table.sql`

```sql
-- ============================================================================
-- V3__create_refunds_table.sql
-- Creates the refunds table for tracking refund requests
-- ============================================================================

CREATE TABLE refunds (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID            NOT NULL,
    order_id        UUID            NOT NULL,
    amount          DECIMAL(12,2)   NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    reason          VARCHAR(500),
    bank_reference  VARCHAR(50),
    idempotency_key VARCHAR(64)     NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_refunds_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uq_refunds_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_refunds_amount CHECK (amount > 0),
    CONSTRAINT chk_refunds_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'))
);

CREATE INDEX idx_refunds_payment_id ON refunds (payment_id);
CREATE INDEX idx_refunds_order_id ON refunds (order_id);
CREATE UNIQUE INDEX idx_refunds_idempotency_key ON refunds (idempotency_key);
CREATE INDEX idx_refunds_status ON refunds (status);
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | Order vs Payment separation | Order = intent, Payment = execution; one order can have multiple attempts |
| 2 | Two-phase payments | Authorize (reserve) → Capture (debit) allows flexibility |
| 3 | Idempotency keys | Unique constraint prevents duplicate orders/refunds on retries |
| 4 | State machine pattern | Enum with `canTransitionTo()` enforces valid transitions |
| 5 | BigDecimal for money | Never use float/double — financial precision requires BigDecimal |
| 6 | Partial refunds | Track `refundedAmount` vs `capturedAmount` for remaining balance |
| 7 | PCI DSS compliance | Store only `cardLastFour` and `cardNetwork`, never full card number |
| 8 | Order expiry | Scheduled job marks CREATED orders as EXPIRED after timeout |
| 9 | Bank references | `authCode`, `rrn`, `bankReference` for reconciliation |

---

## 📚 Document Index

| Document | Title |
|----------|-------|
| [Phase 4 Part 7a](./phase4-part07a-merchant-entities.md) | Merchant Service — Entities |
| [Phase 4 Part 7b](./phase4-part07b-merchant-services.md) | Merchant Service — Services & Controller |
| **Phase 4 Part 8a** | **Payment Service — Entities** (You are here) |
| [Phase 4 Part 8b](./phase4-part08b-payment-services-kafka.md) | Payment Service — Services & Kafka |
| [Phase 4 Part 8c](./phase4-part08c-payment-controllers.md) | Payment Service — Controllers & Tests |

---

## 🚀 Next Steps

In **[Phase 4 Part 8b](./phase4-part08b-payment-services-kafka.md)**, we will implement:

1. `PaymentService` — State machine-driven payment processing
2. `IdempotencyService` — Redis-based duplicate detection
3. `KafkaProducer` — Payment event publishing
4. Feign clients for routing-service and merchant-service
5. Error handling and compensation logic
