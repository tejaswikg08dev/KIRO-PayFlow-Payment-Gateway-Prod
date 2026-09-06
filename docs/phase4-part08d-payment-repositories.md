# 🏗️ Phase 4 Part 8d: Payment Service — Repositories (Data Access)

> **"Four repositories, four different ID types, four different access patterns. Each one is purpose-built."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8d — Repositories |
| **What You Build** | OrderRepository.java, PaymentRepository.java, PaymentMethodRepository.java, RefundRepository.java |
| **Previous** | [Part 8c — Flyway Migrations](./phase4-part08c-payment-migrations.md) |
| **Next** | [Part 8e — DTOs + Mappers](./phase4-part08e-payment-dtos-mappers.md) |

---

## 📖 Table of Contents

1. [Overview — 4 Repos, 4 Patterns](#1-overview--4-repos-4-patterns)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: OrderRepository.java](#3-step-by-step-orderrepositoryjava)
4. [Step-by-Step: PaymentRepository.java](#4-step-by-step-paymentrepositoryjava)
5. [Step-by-Step: PaymentMethodRepository.java](#5-step-by-step-paymentmethodrepositoryjava)
6. [Step-by-Step: RefundRepository.java](#6-step-by-step-refundrepositoryjava)
7. [How Repos Map to Indexes](#7-how-repos-map-to-indexes)
8. [All Queries at a Glance](#8-all-queries-at-a-glance)
9. [What You Learned](#9-what-you-learned)

---

## 1. Overview — 4 Repos, 4 Patterns

| Repository | Entity | PK Type | Custom Methods | Key Pattern |
|---|---|---|---|---|
| `OrderRepository` | Order | `String` | 3 | Enum parameter (`OrderStatus`) |
| `PaymentRepository` | Payment | `String` | 3 | Enum parameter (`PaymentStatus`) |
| `PaymentMethodRepository` | PaymentMethodEntity | `Long` | 1 | 1:1 lookup by parent ID |
| `RefundRepository` | Refund | `String` | 2 | Simple parent lookups |

**WHAT'S NEW vs MERCHANT REPOSITORIES:**

| Feature | Merchant Repos | Payment Repos |
|---|---|---|
| PK types | All `UUID` | `String` (3 repos) + `Long` (1 repo) |
| Enum parameters | None (no enums in merchant) | `OrderStatus`, `PaymentStatus` from common-lib |
| Combined queries | `findByMerchantIdAndActiveTrue` | `findByMerchantIdAndStatus(id, enum)` |
| Number of repos | 3 | 4 |

---

## 2. Folder Structure After This Part

```
backend/payment-service/src/main/java/com/payflow/payment/
├── PaymentServiceApplication.java    ← from 8a
├── config/SecurityConfig.java        ← from 8a
├── model/                            ← from 8b
│   ├── Order.java, Payment.java, PaymentMethodEntity.java, Refund.java
└── repository/                       ← YOU CREATE THIS FOLDER
    ├── OrderRepository.java          ← YOU CREATE THIS
    ├── PaymentRepository.java        ← YOU CREATE THIS
    ├── PaymentMethodRepository.java  ← YOU CREATE THIS
    └── RefundRepository.java         ← YOU CREATE THIS
```

---

## 3. Step-by-Step: OrderRepository.java

**File:** `src/main/java/com/payflow/payment/repository/OrderRepository.java`

```java
package com.payflow.payment.repository;

import com.payflow.common.constant.OrderStatus;
import com.payflow.payment.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
```

**NEW IMPORT: `com.payflow.common.constant.OrderStatus`**

This is the first repository that imports an ENUM as a query parameter. In merchant repos, all parameters were simple types (String, UUID). Here, the `OrderStatus` enum is used directly in method signatures.

```java
@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
```

**`JpaRepository<Order, String>`** — Entity=`Order`, PK type=`String`.

**WHY `String` NOT `UUID`?** Order uses prefixed String IDs (`"order_7a3f9b2c1d4e"`), not UUIDs.

**INHERITED FREE METHODS (from JpaRepository):**

| Method | SQL | Used By |
|---|---|---|
| `save(order)` | `INSERT` or `UPDATE` | OrderService.createOrder() |
| `findById("order_abc")` | `SELECT WHERE id = ?` | OrderService.getOrder() |
| `findAll()` | `SELECT *` | (not currently used) |
| `existsById("order_abc")` | `SELECT COUNT(*) > 0` | (not currently used) |

```java
    List<Order> findByMerchantId(String merchantId);
```

**GENERATED SQL:** `SELECT * FROM orders WHERE merchant_id = ?`

**RETURN: `List<Order>`** — one merchant has many orders.

**USED BY:** OrderService — "List all orders for this merchant" (merchant dashboard).

**INDEX:** `idx_orders_merchant_id` speeds this up.

```java
    List<Order> findByStatus(OrderStatus status);
```

**🆕 ENUM AS PARAMETER**

```java
// You call:
List<Order> createdOrders = orderRepository.findByStatus(OrderStatus.CREATED);

// Spring generates:
// SELECT * FROM orders WHERE status = 'CREATED'
//                                      ^^^^^^^^
// Spring converts OrderStatus.CREATED → "CREATED" string automatically
// (because the entity has @Enumerated(EnumType.STRING))
```

**HOW DOES `OrderStatus.CREATED` BECOME `'CREATED'` IN SQL?**
1. Entity has `@Enumerated(EnumType.STRING)` on the `status` field
2. Spring Data JPA knows to convert the enum to its `.name()` string
3. SQL query uses the string `'CREATED'`

**USED BY:** OrderService.expireOrders() — "Find all CREATED orders" (to check if any expired).

**INDEX:** `idx_orders_status` speeds this up.

```java
    List<Order> findByMerchantIdAndStatus(String merchantId, OrderStatus status);
```

**GENERATED SQL:** `SELECT * FROM orders WHERE merchant_id = ? AND status = ?`

**TWO PARAMETERS — HOW SPRING MAPS THEM:**
```
findByMerchantIdAndStatus(String merchantId, OrderStatus status)
       ^^^^^^^^^^    ^^^^^^
       1st param     2nd param

→ WHERE merchant_id = {1st param} AND status = {2nd param}
```

Parameters are mapped LEFT TO RIGHT in the method signature to the `By...And...` keywords.

**USED BY:** "Show all PAID orders for merchant X" (dashboard query).

**INDEX:** `idx_orders_merchant_status` (composite index) speeds this up.

```java
}
```

**3 custom methods. All return `List` (multiple results expected).**

---

## 4. Step-by-Step: PaymentRepository.java

**File:** `src/main/java/com/payflow/payment/repository/PaymentRepository.java`

```java
package com.payflow.payment.repository;

import com.payflow.common.constant.PaymentStatus;
import com.payflow.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {
```

Same pattern — `String` PK for prefixed IDs.

```java
    Optional<Payment> findByOrderId(String orderId);
```

**GENERATED SQL:** `SELECT * FROM payments WHERE order_id = ?`

**RETURN: `Optional<Payment>`** — WHY Optional here but List on OrderRepository?

```
Order → Payment relationship:
  One order can have multiple payment ATTEMPTS (failed first, succeeded second).
  BUT in practice, findByOrderId is used to find THE active/successful payment.
  Optional means "might not exist yet" (order created but not yet paid).
```

**IMPORTANT:** If multiple payments exist for one order (e.g., one FAILED + one AUTHORIZED), this returns the FIRST match. The service code handles the logic of finding the right one.

**USED BY:** PaymentService.authorizePayment() — "Does this order already have a payment attempt?"

**INDEX:** `idx_payments_order_id` speeds this up.

```java
    List<Payment> findByMerchantIdAndStatus(String merchantId, PaymentStatus status);
```

**GENERATED SQL:** `SELECT * FROM payments WHERE merchant_id = ? AND status = ?`

**ENUM PARAMETER:** Same pattern as OrderRepository — `PaymentStatus.CAPTURED` → `'CAPTURED'` in SQL.

**USED BY:** Settlement Service (future) — "Find all CAPTURED payments for merchant X to calculate settlement."

**INDEX:** `idx_payments_merchant_status` (composite) speeds this up.

```java
    List<Payment> findByMerchantId(String merchantId);
```

**GENERATED SQL:** `SELECT * FROM payments WHERE merchant_id = ?`

**USED BY:** Dashboard — "Show all payment attempts for this merchant."

**INDEX:** `idx_payments_merchant_id` speeds this up.

```java
}
```

**3 custom methods. Mix of Optional (single lookup) and List (multiple results).**

---

## 5. Step-by-Step: PaymentMethodRepository.java

**File:** `src/main/java/com/payflow/payment/repository/PaymentMethodRepository.java`

```java
package com.payflow.payment.repository;

import com.payflow.payment.model.PaymentMethodEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethodEntity, Long> {
```

**🆕 `JpaRepository<PaymentMethodEntity, Long>`**

| Repo | PK Type | Why |
|---|---|---|
| OrderRepository | `String` | Prefixed ID: "order_abc" |
| PaymentRepository | `String` | Prefixed ID: "pay_xyz" |
| RefundRepository | `String` | Prefixed ID: "rfnd_def" |
| **PaymentMethodRepository** | **`Long`** | Auto-increment: 1, 2, 3 (internal, never exposed) |

This is the only repository with a `Long` primary key. The entity uses `@GeneratedValue(IDENTITY)` (BIGSERIAL in PostgreSQL).

```java
    Optional<PaymentMethodEntity> findByPaymentId(String paymentId);
```

**GENERATED SQL:** `SELECT * FROM payment_methods WHERE payment_id = ?`

**RETURN: `Optional`** — 1:1 relationship. Each payment has exactly ONE payment method record. Optional because the method record might not be created yet (during the brief moment between creating Payment and saving PaymentMethodEntity).

**USED BY:** PaymentService — when building the payment response, include the card/UPI/bank details.

**INDEX:** `idx_payment_methods_payment_id` speeds this up.

```java
}
```

**THE SIMPLEST REPOSITORY — just 1 custom method.** All the heavy lifting is done by the inherited `save()` method.

---

## 6. Step-by-Step: RefundRepository.java

**File:** `src/main/java/com/payflow/payment/repository/RefundRepository.java`

```java
package com.payflow.payment.repository;

import com.payflow.payment.model.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundRepository extends JpaRepository<Refund, String> {

    List<Refund> findByPaymentId(String paymentId);
```

**GENERATED SQL:** `SELECT * FROM refunds WHERE payment_id = ?`

**RETURN: `List<Refund>`** — one payment can have MULTIPLE refunds (partial refund scenario):

```
Payment: pay_abc (amount = ₹1,000, status = CAPTURED)
  Refund 1: rfnd_001 (amount = ₹300)   ← partial
  Refund 2: rfnd_002 (amount = ₹200)   ← partial
  Refund 3: rfnd_003 (amount = ₹500)   ← remaining

findByPaymentId("pay_abc") → [rfnd_001, rfnd_002, rfnd_003]

RefundService uses this to calculate:
  totalRefunded = 300 + 200 + 500 = ₹1,000
  If totalRefunded == payment.amount → payment status → REFUNDED
```

**THIS IS THE CRITICAL QUERY FOR OVER-REFUND PREVENTION.** RefundService sums all refund amounts for a payment before allowing a new refund.

**INDEX:** `idx_refunds_payment_id` speeds this up.

```java
    List<Refund> findByMerchantId(String merchantId);
```

**GENERATED SQL:** `SELECT * FROM refunds WHERE merchant_id = ?`

**USED BY:** Dashboard — "Show all refunds for this merchant."

**INDEX:** `idx_refunds_merchant_id` speeds this up.

```java
}
```

**2 custom methods. Both return List (multiple results).**

---

## 7. How Repos Map to Indexes

Every custom query method benefits from an index we created in Part 8c:

| Repository Method | Index Used | Type |
|---|---|---|
| `orderRepo.findByMerchantId(id)` | `idx_orders_merchant_id` | Single |
| `orderRepo.findByStatus(enum)` | `idx_orders_status` | Single |
| `orderRepo.findByMerchantIdAndStatus(id, enum)` | `idx_orders_merchant_status` | Composite |
| `paymentRepo.findByOrderId(id)` | `idx_payments_order_id` | Single |
| `paymentRepo.findByMerchantId(id)` | `idx_payments_merchant_id` | Single |
| `paymentRepo.findByMerchantIdAndStatus(id, enum)` | `idx_payments_merchant_status` | Composite |
| `paymentMethodRepo.findByPaymentId(id)` | `idx_payment_methods_payment_id` | Single |
| `refundRepo.findByPaymentId(id)` | `idx_refunds_payment_id` | Single |
| `refundRepo.findByMerchantId(id)` | `idx_refunds_merchant_id` | Single |

**9 custom methods → 9 indexes.** Every query has a matching index. No full table scans.

The 2 remaining indexes (`idx_orders_expires_at` partial + `idx_payments_status`) are used by service-layer queries (expiry + batch operations) not exposed through repository methods.

---

## 8. All Queries at a Glance

### OrderRepository (3 custom + inherited)

| Method | SQL | Return | Used For |
|---|---|---|---|
| `save(order)` | INSERT/UPDATE | Order | Create/update orders |
| `findById(id)` | WHERE id = ? | Optional | Get single order |
| `findByMerchantId(id)` | WHERE merchant_id = ? | List | Dashboard: all merchant's orders |
| `findByStatus(enum)` | WHERE status = ? | List | Find CREATED orders for expiry |
| `findByMerchantIdAndStatus(id, enum)` | WHERE merchant_id = ? AND status = ? | List | Dashboard: filtered orders |

### PaymentRepository (3 custom + inherited)

| Method | SQL | Return | Used For |
|---|---|---|---|
| `save(payment)` | INSERT/UPDATE | Payment | Create/update payments |
| `findById(id)` | WHERE id = ? | Optional | Get single payment |
| `findByOrderId(id)` | WHERE order_id = ? | Optional | Find payment for an order |
| `findByMerchantId(id)` | WHERE merchant_id = ? | List | Dashboard: all payments |
| `findByMerchantIdAndStatus(id, enum)` | WHERE merchant_id = ? AND status = ? | List | Settlement: CAPTURED payments |

### PaymentMethodRepository (1 custom + inherited)

| Method | SQL | Return | Used For |
|---|---|---|---|
| `save(method)` | INSERT | PaymentMethodEntity | Store card/UPI/bank details |
| `findByPaymentId(id)` | WHERE payment_id = ? | Optional | Get method details for display |

### RefundRepository (2 custom + inherited)

| Method | SQL | Return | Used For |
|---|---|---|---|
| `save(refund)` | INSERT | Refund | Create refund record |
| `findById(id)` | WHERE id = ? | Optional | Get single refund |
| `findByPaymentId(id)` | WHERE payment_id = ? | List | Calculate total refunded + list |
| `findByMerchantId(id)` | WHERE merchant_id = ? | List | Dashboard: all refunds |

---

## 9. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **String PK in JpaRepository** | `JpaRepository<Order, String>` — for prefixed IDs like "order_abc" |
| 2 | **Long PK in JpaRepository** | `JpaRepository<PaymentMethodEntity, Long>` — for auto-increment IDs |
| 3 | **Enum as query parameter** | `findByStatus(OrderStatus status)` — Spring converts enum → string in SQL |
| 4 | **How enum conversion works** | `@Enumerated(STRING)` + `.name()` → `OrderStatus.CREATED` → `'CREATED'` |
| 5 | **Combined enum + string query** | `findByMerchantIdAndStatus(String, PaymentStatus)` — two params mapped left-to-right |
| 6 | **Optional for 1:1 lookups** | `findByOrderId` → Optional (one payment per order, might not exist yet) |
| 7 | **List for 1:N lookups** | `findByPaymentId` → List (multiple refunds per payment) |
| 8 | **Every query has a matching index** | 9 methods → 9 indexes (no full table scans) |
| 9 | **Refund list for over-refund check** | Sum all refund amounts to ensure total ≤ payment amount |
| 10 | **Simplest repo: 1 method** | PaymentMethodRepository has just `findByPaymentId` — minimal interface |
| 11 | **No cross-database FK** | `merchant_id` has no FK constraint — different DB, validated by application |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part08-payment-service-overview.md) | Payment Service Overview |
| [Part 8a](./phase4-part08a-payment-project-setup.md) | Project Setup |
| [Part 8b](./phase4-part08b-payment-entities.md) | Entities |
| [Part 8c](./phase4-part08c-payment-migrations.md) | Flyway Migrations |
| **Part 8d** | **Repositories** (You are here) |
| [Part 8e](./phase4-part08e-payment-dtos-mappers.md) | DTOs + Mappers |
| [Part 8f](./phase4-part08f-payment-configs.md) | Config Classes |
| [Part 8g](./phase4-part08g-payment-order-refund-services.md) | OrderService + RefundService |
| [Part 8h](./phase4-part08h-payment-idempotency-events.md) | IdempotencyService + Events |
| [Part 8i](./phase4-part08i-payment-engine.md) | PaymentService Core Engine |
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + Docker |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | Connections & Flows |

---

*Next: [Part 8e — DTOs + Mappers (7 DTOs, 2 MapStruct Mappers)](./phase4-part08e-payment-dtos-mappers.md) →*
