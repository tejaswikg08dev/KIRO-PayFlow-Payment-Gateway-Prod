# 🏗️ Phase 4 Part 8b: Payment Service — Entities (Data Models)

> **"In a payment system, every field matters. A missing authorization code or a wrong decimal scale can mean lost money."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8b — Entities (Data Models) |
| **What You Build** | Order.java, Payment.java, PaymentMethodEntity.java, Refund.java |
| **Previous** | [Part 8a — Project Setup](./phase4-part08a-payment-project-setup.md) |
| **Next** | [Part 8c — Flyway Migrations](./phase4-part08c-payment-migrations.md) |

---

## 📖 Table of Contents

1. [What's Different from Merchant Entities](#1-whats-different-from-merchant-entities)
2. [The 4 Entities and How They Relate](#2-the-4-entities-and-how-they-relate)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: Order.java](#4-step-by-step-orderjava)
5. [Step-by-Step: Payment.java](#5-step-by-step-paymentjava)
6. [Step-by-Step: PaymentMethodEntity.java](#6-step-by-step-paymentmethodentityjava)
7. [Step-by-Step: Refund.java](#7-step-by-step-refundjava)
8. [Entity Relationship Diagram](#8-entity-relationship-diagram)
9. [Key Differences from Merchant Entities](#9-key-differences-from-merchant-entities)
10. [What You Learned](#10-what-you-learned)

---

## 1. What's Different from Merchant Entities

Payment entities introduce several new patterns you haven't seen before:

| New Pattern | Where | Why |
|---|---|---|
| **String ID (not UUID)** | Order, Payment, Refund | Prefixed IDs: `order_abc123`, `pay_xyz789`, `rfnd_def456` |
| **`@Id` without `@GeneratedValue`** | Order, Payment, Refund | ID is generated in SERVICE code (IdGenerator), not by Hibernate |
| **`Long` auto-increment ID** | PaymentMethodEntity | Only this entity uses traditional IDENTITY strategy |
| **Enums from common-lib** | Order (OrderStatus), Payment (PaymentStatus, PaymentMethod) | Shared enums defined in common-lib, not locally |
| **`BigDecimal(19,4)`** | Order, Payment, Refund | Payment amounts need higher precision than fee percentages |
| **`expiresAt` field** | Order | Orders expire after 30 minutes (TTL pattern) |
| **Polymorphic fields** | PaymentMethodEntity | Card OR UPI OR Net Banking — all in one table with nullable columns |
| **No `active` flag** | All 4 entities | Payments aren't "deactivated" — they have STATUS state machines |
| **No `@UpdateTimestamp`** | Refund, PaymentMethodEntity | Refunds are immutable (created once, never updated) |

---

## 2. The 4 Entities and How They Relate

```
MERCHANT creates ORDER → CUSTOMER authorizes PAYMENT → PAYMENT stores METHOD → MERCHANT issues REFUND

Order (1) ←── (N) Payment (1) ←── (1) PaymentMethodEntity
                    │
                    └── (N) Refund
```

| Entity | Real-World Analogy | Purpose |
|---|---|---|
| **Order** | The bill/invoice | "Customer owes ₹1,000 for these items" |
| **Payment** | The card swipe / UPI tap | "Customer attempted to pay ₹1,000 with Visa card" |
| **PaymentMethodEntity** | Card details on the receipt | "Visa ending 4242, expires 12/25" |
| **Refund** | The return receipt | "₹500 returned to customer (partial refund)" |

---

## 3. Folder Structure After This Part

```
backend/payment-service/src/main/java/com/payflow/payment/
├── PaymentServiceApplication.java    ← from 8a
├── config/SecurityConfig.java        ← from 8a
└── model/                            ← YOU CREATE THIS FOLDER
    ├── Order.java                    ← YOU CREATE THIS
    ├── Payment.java                  ← YOU CREATE THIS
    ├── PaymentMethodEntity.java      ← YOU CREATE THIS
    └── Refund.java                   ← YOU CREATE THIS
```

**CREATE ORDER:** Order → Payment → PaymentMethodEntity → Refund (follow the FK chain).

---

## 4. Step-by-Step: Order.java

**File:** `src/main/java/com/payflow/payment/model/Order.java`

### Full Source Code

```java
package com.payflow.payment.model;

import com.payflow.common.constant.OrderStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
```

**NEW IMPORT: `com.payflow.common.constant.OrderStatus`**

The `OrderStatus` enum lives in **common-lib** (not in payment-service), because other services also need to know about order statuses (e.g., webhook events include order status).

```java
/**
 * Represents a payment order created by a merchant.
 * An order can have multiple payment attempts but only one successful payment.
 */
@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
```

Same Lombok + JPA annotations as merchant entities. Nothing new here.

```java
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 30)
    private String id;
```

**🆕 MAJOR DIFFERENCE FROM MERCHANT:**

| Merchant Entity | Payment Entity |
|---|---|
| `@Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;` | `@Id @Column(length = 30) private String id;` |
| Hibernate generates UUID automatically | **NO `@GeneratedValue`** — ID set in service code |

**WHY NO `@GeneratedValue`?**
Payment uses **prefixed IDs** from `IdGenerator` in common-lib:
```java
// In OrderService.createOrder():
order.setId(IdGenerator.generateOrderId());
// Result: "order_7a3f9b2c1d4e"  ← human-readable, prefixed

// Merchant used:
// @GeneratedValue(strategy = GenerationType.UUID)
// Result: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"  ← raw UUID
```

**WHY PREFIXED IDs?**
```
Looking at a UUID:     "a1b2c3d4-e5f6-7890"  ← Is this an order? Payment? Refund? WHO KNOWS.
Looking at a prefix:   "order_7a3f9b2c1d4e"  ← Obviously an order.
                       "pay_8b4c2d1e3f5a"    ← Obviously a payment.
                       "rfnd_9c5d3e2f4a6b"   ← Obviously a refund.
```

Stripe, Razorpay, and most payment gateways use this pattern. It makes logs, debugging, and customer support much easier.

**`length = 30`:** The prefix (`order_`) + 12 hex chars = ~18 chars. 30 gives room to spare.

```java
    @Column(name = "merchant_id", nullable = false, length = 30)
    private String merchantId;
```

Which merchant created this order. Stored as String (matches the merchant's UUID converted to string, or a prefixed ID if merchant used that format).

```java
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
```

**🆕 `precision = 19, scale = 4` — WHY DIFFERENT FROM MERCHANT'S `5,2`?**

| Entity | Precision | Scale | Max Value | Use Case |
|---|---|---|---|---|
| FeeConfig (merchant) | 5 | 2 | 999.99 | MDR/GST percentages (never > 100%) |
| **Order (payment)** | **19** | **4** | **999,999,999,999,999.9999** | Payment amounts (can be very large) |

**WHY 19 digits?** A payment in Indian Rupees could be ₹99,99,99,99,999.9999 (₹99.99 billion). For international currencies, amounts can be huge. 19 digits covers all real-world scenarios.

**WHY 4 decimal places?** Some currencies (like Bahraini Dinar) use 3 decimal places. 4 gives headroom. Most payment gateways use 4 decimal places internally.

```java
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
```

ISO 4217 currency code: `"INR"`, `"USD"`, `"EUR"`, `"GBP"`. Always exactly 3 characters.

**WHY String NOT enum?** New currencies can be supported without code changes. The DTO validates `@Size(min=3, max=3)`.

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;
```

**`OrderStatus` from common-lib:**
```java
// In common-lib/src/main/java/com/payflow/common/constant/OrderStatus.java:
public enum OrderStatus {
    CREATED,     // Order placed, waiting for payment
    ATTEMPTED,   // Payment authorization attempted
    PAID,        // Payment captured successfully
    EXPIRED      // Order timed out (30 minutes)
}
```

**WHY IN common-lib?** Because `PaymentEvent` (also in common-lib) includes the order status. If the enum was local to payment-service, common-lib couldn't reference it.

```java
    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "receipt_number", length = 100)
    private String receiptNumber;
```

**ALL THREE ARE OPTIONAL** (no `nullable = false`):
- `customerEmail` — for sending payment receipts
- `description` — what the payment is for ("Premium Plan - 1 Year")
- `receiptNumber` — merchant's internal receipt number

```java
    @Column(name = "expires_at")
    private Instant expiresAt;
```

**🆕 TTL PATTERN — NEW CONCEPT**

Orders expire after 30 minutes. If a customer doesn't pay within 30 minutes, the order becomes EXPIRED and can't be paid.

**HOW IT'S SET (in OrderService.createOrder()):**
```java
order.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
```

**HOW IT'S CHECKED (in PaymentService.authorizePayment()):**
```java
if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(Instant.now())) {
    throw new PayflowException("Order has expired");
}
```

**WHY 30 MINUTES?**
- Too short (5 min): Customer loses time filling payment details
- Too long (24 hours): Price/inventory could change; fraud risk
- 30 minutes: Industry standard (Razorpay uses 30 min, Stripe uses 24 hours)

**WHY NOT JUST DELETE EXPIRED ORDERS?**
Expired orders are kept for auditing. The `POST /v1/orders/expire` endpoint (admin/cron) bulk-updates CREATED→EXPIRED for all past-due orders.

```java
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

Same timestamp pattern as merchant entities.

---

## 5. Step-by-Step: Payment.java

**File:** `src/main/java/com/payflow/payment/model/Payment.java`

### Full Source Code

```java
package com.payflow.payment.model;

import com.payflow.common.constant.PaymentMethod;
import com.payflow.common.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
```

**TWO NEW IMPORTS FROM common-lib:**
- `PaymentStatus` — CREATED, AUTHORIZED, CAPTURED, VOIDED, FAILED, REFUNDED
- `PaymentMethod` — CARD, UPI, NET_BANKING, WALLET

```java
/**
 * Represents a payment attempt against an order.
 * Tracks the full lifecycle: CREATED → AUTHORIZED → CAPTURED/VOIDED/FAILED.
 */
@Entity
@Table(name = "payments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 30)
    private String id;
```

Same prefixed ID pattern as Order: `"pay_8b4c2d1e3f5a"`.

```java
    @Column(name = "order_id", nullable = false, length = 30)
    private String orderId;

    @Column(name = "merchant_id", nullable = false, length = 30)
    private String merchantId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
```

Same patterns as Order. The `amount` here might DIFFER from the order amount (partial capture scenario).

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;
```

**`PaymentStatus` from common-lib:**
```java
public enum PaymentStatus {
    CREATED,     // Payment record created, about to call bank
    AUTHORIZED,  // Bank approved — money reserved
    CAPTURED,    // Money collected from customer
    VOIDED,      // Authorization cancelled (before capture)
    FAILED,      // Bank declined
    REFUNDED     // Money returned to customer (after capture)
}
```

**STATE TRANSITIONS (enforced in PaymentService):**
```
CREATED → AUTHORIZED   (bank says "approved")
CREATED → FAILED       (bank says "declined")
AUTHORIZED → CAPTURED  (merchant says "collect the money")
AUTHORIZED → VOIDED    (merchant says "cancel, don't charge")
CAPTURED → REFUNDED    (merchant says "give money back")
```

**INVALID TRANSITIONS (service throws exception):**
```
FAILED → CAPTURED    ❌ Can't capture a failed payment
VOIDED → CAPTURED    ❌ Can't capture a voided payment
CAPTURED → VOIDED    ❌ Can't void after capture (must refund instead)
REFUNDED → CAPTURED  ❌ Can't re-capture a refund
```

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;
```

**`PaymentMethod` from common-lib:**
```java
public enum PaymentMethod {
    CARD,          // Visa, Mastercard, RuPay, Amex
    UPI,           // UPI (Google Pay, PhonePe, etc.)
    NET_BANKING,   // Internet banking
    WALLET         // Digital wallets (Paytm, etc.)
}
```

```java
    @Column(name = "authorization_code", length = 50)
    private String authorizationCode;
```

**🆕 BANK AUTHORIZATION CODE**

When the bank approves a payment, it returns a 6-digit authorization code (like `"AUTH-847291"`). This code:
- Proves the bank approved the transaction
- Is needed for capture and settlement
- Appears on the customer's bank statement
- Is null when payment is CREATED or FAILED

```java
    @Column(name = "bank_reference_id", length = 100)
    private String bankReferenceId;
```

**🆕 BANK REFERENCE ID**

The bank's own transaction identifier (like `"BNK-TXN-20240115-847291"`). Different from our paymentId:
- Our ID: `"pay_8b4c2d1e3f5a"` (PayFlow's identifier)
- Bank's ID: `"BNK-TXN-20240115-847291"` (bank's identifier)

Both are needed for reconciliation — matching PayFlow's records with the bank's records.

```java
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
```

**🆕 FAILURE REASON**

When the bank DECLINES a payment, it returns a reason. Stored here for:
- Customer communication: "Your card was declined: insufficient funds"
- Analytics: "30% of failures are due to expired cards"
- Debugging: "Why did this payment fail?"

**Only populated when `status = FAILED`.** Null for all other statuses.

```java
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

---

## 6. Step-by-Step: PaymentMethodEntity.java

**File:** `src/main/java/com/payflow/payment/model/PaymentMethodEntity.java`

This entity is **very different** from everything you've seen.

### Full Source Code

```java
package com.payflow.payment.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Stores payment method details (card, UPI, or net banking) for a payment.
 * Card details are stored in masked/tokenized form — never raw PAN.
 */
@Entity
@Table(name = "payment_methods")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
```

**🆕 THREE DIFFERENCES AT ONCE:**

**Difference 1: `Long` ID (not String/UUID)**
```
Order:                private String id;    ← prefixed string "order_abc"
Merchant:             private UUID id;      ← Java UUID
PaymentMethodEntity:  private Long id;      ← simple numeric 1, 2, 3, 4...
```

**Difference 2: `GenerationType.IDENTITY` (not UUID)**
```
IDENTITY = PostgreSQL auto-increment (SERIAL/BIGSERIAL)
  → First row: id=1, second: id=2, third: id=3...
  → Database generates the number (not Java)
```

**Difference 3: WHY DIFFERENT?**
- PaymentMethodEntity is an INTERNAL detail — customers/merchants never see this ID
- No need for prefixed IDs (nobody references `"pm_123"` in API calls)
- Simple auto-increment is fine for internal tables
- It's a 1:1 relationship with Payment — looked up by `paymentId`, not by its own ID

```java
    @Column(name = "payment_id", nullable = false, length = 30)
    private String paymentId;
```

FK to the payments table. Each payment has exactly ONE payment method record.

```java
    @Column(name = "type", nullable = false, length = 20)
    private String type;
```

`"CARD"`, `"UPI"`, or `"NET_BANKING"`. Determines which fields below are populated.

```java
    // ─── Card Fields (populated when type = "CARD") ───

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @Column(name = "card_expiry_month", length = 2)
    private String cardExpiryMonth;

    @Column(name = "card_expiry_year", length = 4)
    private String cardExpiryYear;
```

**🆕 PCI COMPLIANCE — WHY `last4` NOT FULL CARD NUMBER?**

```
❌ NEVER STORE:
  cardNumber: "4111111111111111"  ← PCI violation! Full card number

✅ WHAT WE STORE:
  cardLast4: "1111"              ← Safe to store (PCI compliant)
  cardBrand: "VISA"              ← Detected from card number prefix
  cardExpiryMonth: "12"          ← Needed for display
  cardExpiryYear: "2025"         ← Needed for display

The FULL card number is sent to the bank during authorization,
but NEVER stored in our database.
```

**PCI DSS (Payment Card Industry Data Security Standard):** If you store full card numbers, you must pass a PCI audit ($50K-$500K/year). By storing only last4, you avoid that requirement.

```java
    // ─── UPI Fields (populated when type = "UPI") ───

    @Column(name = "upi_id", length = 100)
    private String upiId;
```

Example: `"customer@oksbi"`, `"9876543210@paytm"`.

```java
    // ─── Net Banking Fields (populated when type = "NET_BANKING") ───

    @Column(name = "bank_code", length = 20)
    private String bankCode;

    @Column(name = "bank_name", length = 100)
    private String bankName;
}
```

Example: `bankCode = "HDFC"`, `bankName = "HDFC Bank"`.

### 🆕 Polymorphic Storage Pattern

```
WHEN type = "CARD":
  cardLast4 = "4242"         ← populated
  cardBrand = "VISA"         ← populated
  cardExpiryMonth = "12"     ← populated
  cardExpiryYear = "2025"    ← populated
  upiId = null               ← empty
  bankCode = null             ← empty
  bankName = null             ← empty

WHEN type = "UPI":
  cardLast4 = null            ← empty
  cardBrand = null            ← empty
  cardExpiryMonth = null      ← empty
  cardExpiryYear = null       ← empty
  upiId = "customer@oksbi"  ← populated
  bankCode = null             ← empty
  bankName = null             ← empty

WHEN type = "NET_BANKING":
  cardLast4 = null            ← empty
  cardBrand = null            ← empty
  cardExpiryMonth = null      ← empty
  cardExpiryYear = null       ← empty
  upiId = null                ← empty
  bankCode = "HDFC"          ← populated
  bankName = "HDFC Bank"     ← populated
```

**WHY ONE TABLE NOT THREE?**
| Approach | Tables | Complexity |
|---|---|---|
| **Single table (our choice)** | 1 table, nullable columns | Simple queries, one JOIN max |
| Table per type | 3 tables (card_methods, upi_methods, bank_methods) | Complex: must query ALL 3 tables to find method |
| JPA inheritance | 1 table + discriminator column | JPA inheritance adds complexity, similar to our approach |

For a small number of types (3), single table with nullable columns is simplest.

### No Timestamps

Notice: **no `@CreationTimestamp` or `@UpdateTimestamp`**. Payment methods are created once with the payment and never updated. If the customer pays with a different card, a new Payment + PaymentMethodEntity pair is created.

---

## 7. Step-by-Step: Refund.java

**File:** `src/main/java/com/payflow/payment/model/Refund.java`

### Full Source Code

```java
package com.payflow.payment.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a refund issued against a captured payment.
 * Supports both full and partial refunds.
 */
@Entity
@Table(name = "refunds")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Refund {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 30)
    private String id;
```

Prefixed ID: `"rfnd_9c5d3e2f4a6b"`.

```java
    @Column(name = "payment_id", nullable = false, length = 30)
    private String paymentId;

    @Column(name = "merchant_id", nullable = false, length = 30)
    private String merchantId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
```

**PARTIAL REFUND SUPPORT:**
```
Payment amount: ₹1,000
Refund 1: ₹300 (partial)    ← amount = 300
Refund 2: ₹200 (partial)    ← amount = 200
Refund 3: ₹500 (remaining)  ← amount = 500
Total refunded: ₹1,000      ← equals payment amount → payment status → REFUNDED
```

Multiple Refund records can exist for ONE payment. RefundService tracks the cumulative total.

```java
    @Column(name = "reason", length = 500)
    private String reason;
```

Optional: "Customer returned the product", "Duplicate charge", "Service not rendered".

```java
    @Column(name = "status", nullable = false, length = 20)
    private String status;
```

**NOTE: This is `String`, NOT `@Enumerated` enum.** The refund status is stored as a plain string (e.g., `"COMPLETED"`, `"PROCESSING"`). Unlike Order/Payment which use common-lib enums.

```java
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

**🆕 NO `@UpdateTimestamp`**

Refund has ONLY `createdAt`, no `updatedAt`. Why?

**Refunds are immutable.** Once created, a refund record is never modified:
- You can't change the refund amount after creation
- You can't change the reason after creation
- If something goes wrong, you create a NEW refund, not modify the old one

This is a common pattern in financial systems: **append-only records** for audit trail integrity.

---

## 8. Entity Relationship Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    PAYMENT SERVICE DATABASE (payflow_payment)                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  ┌─────────────────────────┐                                                   │
│  │       orders            │                                                   │
│  ├─────────────────────────┤                                                   │
│  │ PK  id       VARCHAR(30)│  "order_7a3f9b2c1d4e"                            │
│  │     merchant_id  VARCHAR│                                                   │
│  │     amount    DECIMAL   │  BigDecimal(19,4)                                 │
│  │     currency  VARCHAR(3)│  "INR", "USD"                                     │
│  │     status    VARCHAR   │  CREATED/ATTEMPTED/PAID/EXPIRED                   │
│  │     customer_email      │  Optional                                         │
│  │     description         │  Optional                                         │
│  │     receipt_number      │  Optional                                         │
│  │     expires_at TIMESTAMP│  NOW() + 30 minutes                               │
│  │     created_at          │                                                   │
│  │     updated_at          │                                                   │
│  └──────────┬──────────────┘                                                   │
│             │ 1:N                                                               │
│             ▼                                                                   │
│  ┌─────────────────────────┐         ┌─────────────────────────┐              │
│  │      payments           │         │   payment_methods       │              │
│  ├─────────────────────────┤         ├─────────────────────────┤              │
│  │ PK  id       VARCHAR(30)│ 1:1 ──►│ PK  id       BIGSERIAL  │              │
│  │ FK  order_id VARCHAR(30)│         │ FK  payment_id VARCHAR  │              │
│  │     merchant_id         │         │     type       VARCHAR  │              │
│  │     amount    DECIMAL   │         │     card_last4          │              │
│  │     currency  VARCHAR(3)│         │     card_brand          │              │
│  │     status    VARCHAR   │         │     card_expiry_month   │              │
│  │     payment_method      │         │     card_expiry_year    │              │
│  │     authorization_code  │         │     upi_id              │              │
│  │     bank_reference_id   │         │     bank_code           │              │
│  │     failure_reason      │         │     bank_name           │              │
│  │     created_at          │         └─────────────────────────┘              │
│  │     updated_at          │                                                   │
│  └──────────┬──────────────┘                                                   │
│             │ 1:N                                                               │
│             ▼                                                                   │
│  ┌─────────────────────────┐                                                   │
│  │       refunds           │                                                   │
│  ├─────────────────────────┤                                                   │
│  │ PK  id       VARCHAR(30)│  "rfnd_9c5d3e2f4a6b"                            │
│  │ FK  payment_id VARCHAR  │                                                   │
│  │     merchant_id         │                                                   │
│  │     amount    DECIMAL   │  Partial refund amount                            │
│  │     reason    VARCHAR   │  Optional                                         │
│  │     status    VARCHAR   │  "COMPLETED"                                      │
│  │     created_at          │  NO updated_at (immutable)                        │
│  └─────────────────────────┘                                                   │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Key Differences from Merchant Entities

| Feature | Merchant Entities | Payment Entities |
|---|---|---|
| **ID type** | `UUID` (auto-generated) | `String` (prefixed, set in code) — Order, Payment, Refund |
| **ID generation** | `@GeneratedValue(UUID)` | No `@GeneratedValue` — `IdGenerator.generateOrderId()` in service |
| **PaymentMethodEntity ID** | — | `Long` + `GenerationType.IDENTITY` (auto-increment) |
| **Amount precision** | `BigDecimal(5,2)` (fee %) | `BigDecimal(19,4)` (payment amounts) |
| **Enums** | None (String types) | `OrderStatus`, `PaymentStatus`, `PaymentMethod` from common-lib |
| **Active/inactive** | `Boolean active` flag | No active flag — uses STATUS state machine |
| **TTL/expiry** | None | `expiresAt` on Order (30-minute TTL) |
| **Polymorphic fields** | None | PaymentMethodEntity (card OR UPI OR bank in one table) |
| **Immutable records** | None | Refund has no `@UpdateTimestamp` (created once, never changed) |
| **Bank fields** | None | `authorizationCode`, `bankReferenceId`, `failureReason` on Payment |
| **PCI compliance** | Not applicable | `cardLast4` only — never store full card numbers |

---

## 10. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Prefixed String IDs** | `"order_abc"`, `"pay_xyz"` — human-readable, set in service code via `IdGenerator` |
| 2 | **No @GeneratedValue** | When you generate the ID yourself, don't use `@GeneratedValue` — just `@Id` |
| 3 | **GenerationType.IDENTITY** | Auto-increment Long — for internal entities that don't need prefixed IDs |
| 4 | **BigDecimal(19,4)** | High precision for payment amounts — 4 decimal places, up to trillions |
| 5 | **Enums from common-lib** | `OrderStatus`, `PaymentStatus`, `PaymentMethod` shared across services |
| 6 | **expiresAt TTL pattern** | Orders expire after 30 minutes — checked before authorizing payment |
| 7 | **Polymorphic entity** | One table, nullable columns — card OR UPI OR bank fields per row |
| 8 | **PCI compliance** | NEVER store full card numbers — only `last4` + `brand` + expiry |
| 9 | **Bank response fields** | `authorizationCode` (bank's approval) + `bankReferenceId` (bank's transaction ID) |
| 10 | **failureReason** | Stored when bank declines — for customer communication + analytics |
| 11 | **Immutable records** | Refund has no `@UpdateTimestamp` — financial records should be append-only |
| 12 | **String status vs enum** | Refund uses plain String; Order/Payment use @Enumerated enums from common-lib |
| 13 | **1:N payment attempts** | One order can have multiple payment attempts (failed first, succeeded second) |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part08-payment-service-overview.md) | Payment Service Overview |
| [Part 8a](./phase4-part08a-payment-project-setup.md) | Project Setup |
| **Part 8b** | **Entities** (You are here) |
| [Part 8c](./phase4-part08c-payment-migrations.md) | Flyway Migrations |
| [Part 8d](./phase4-part08d-payment-repositories.md) | Repositories |
| [Part 8e](./phase4-part08e-payment-dtos-mappers.md) | DTOs + Mappers |
| [Part 8f](./phase4-part08f-payment-configs.md) | Config Classes |
| [Part 8g](./phase4-part08g-payment-order-refund-services.md) | OrderService + RefundService |
| [Part 8h](./phase4-part08h-payment-idempotency-events.md) | IdempotencyService + Events |
| [Part 8i](./phase4-part08i-payment-engine.md) | PaymentService Core Engine |
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + Docker |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | Connections & Flows |

---

*Next: [Part 8c — Flyway Migrations (Database Tables)](./phase4-part08c-payment-migrations.md) →*
