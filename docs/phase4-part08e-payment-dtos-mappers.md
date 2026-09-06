# 🏗️ Phase 4 Part 8e: Payment Service — DTOs + Mappers (Input/Output Contracts)

> **"A card number goes IN as 16 digits. It comes OUT as last 4. That transformation is the DTO's job."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8e — DTOs + Mappers |
| **What You Build** | 4 Request DTOs, 3 Response DTOs, 2 MapStruct Mappers |
| **Previous** | [Part 8d — Repositories](./phase4-part08d-payment-repositories.md) |
| **Next** | [Part 8f — Config Classes](./phase4-part08f-payment-configs.md) |

---

## 📖 Table of Contents

1. [Overview — 7 DTOs + 2 Mappers](#1-overview--7-dtos--2-mappers)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: CreateOrderRequest.java](#3-step-by-step-createorderrequestjava)
4. [Step-by-Step: AuthorizePaymentRequest.java](#4-step-by-step-authorizepaymentrequestjava)
5. [Step-by-Step: CapturePaymentRequest.java](#5-step-by-step-capturepaymentrequestjava)
6. [Step-by-Step: RefundRequest.java](#6-step-by-step-refundrequestjava)
7. [Step-by-Step: OrderResponse.java](#7-step-by-step-orderresponsejava)
8. [Step-by-Step: PaymentResponse.java](#8-step-by-step-paymentresponsejava)
9. [Step-by-Step: RefundResponse.java](#9-step-by-step-refundresponsejava)
10. [Step-by-Step: OrderMapper.java](#10-step-by-step-ordermapperjava)
11. [Step-by-Step: PaymentMapper.java](#11-step-by-step-paymentmapperjava)
12. [Validation Annotations Summary](#12-validation-annotations-summary)
13. [What You Learned](#13-what-you-learned)

---

## 1. Overview — 7 DTOs + 2 Mappers

### Input DTOs (what clients send)

| DTO | Endpoint | Key Validations |
|---|---|---|
| `CreateOrderRequest` | POST /v1/orders | `@DecimalMin("0.01")`, `@Size(3,3)` currency, `@Digits(15,4)` |
| `AuthorizePaymentRequest` | POST /v1/payments/authorize | Card `@Size(13,19)`, CVV `@Size(3,4)`, polymorphic fields |
| `CapturePaymentRequest` | POST /v1/payments/capture | Optional amount (null=full, value=partial) |
| `RefundRequest` | POST /v1/refunds | Required amount `@DecimalMin("0.01")` |

### Output DTOs (what clients receive)

| DTO | Endpoint | Key Fields |
|---|---|---|
| `OrderResponse` | All order endpoints | status as String (not enum) |
| `PaymentResponse` | All payment endpoints | authorizationCode, bankReferenceId, failureReason |
| `RefundResponse` | All refund endpoints | No updatedAt (immutable) |

### Mappers

| Mapper | Converts | Special Feature |
|---|---|---|
| `OrderMapper` | Order → OrderResponse | `expression` for enum→String |
| `PaymentMapper` | Payment → PaymentResponse | TWO `expression` mappings (status + paymentMethod) |

---

## 2. Folder Structure After This Part

```
backend/payment-service/src/main/java/com/payflow/payment/
├── ... (from 8a-8d)
├── dto/                                  ← YOU CREATE THIS FOLDER
│   ├── CreateOrderRequest.java           ← YOU CREATE THIS
│   ├── AuthorizePaymentRequest.java      ← YOU CREATE THIS
│   ├── CapturePaymentRequest.java        ← YOU CREATE THIS
│   ├── RefundRequest.java                ← YOU CREATE THIS
│   ├── OrderResponse.java                ← YOU CREATE THIS
│   ├── PaymentResponse.java              ← YOU CREATE THIS
│   └── RefundResponse.java               ← YOU CREATE THIS
└── mapper/                               ← YOU CREATE THIS FOLDER
    ├── OrderMapper.java                  ← YOU CREATE THIS
    └── PaymentMapper.java                ← YOU CREATE THIS
```

---

## 3. Step-by-Step: CreateOrderRequest.java

**File:** `src/main/java/com/payflow/payment/dto/CreateOrderRequest.java`

```java
package com.payflow.payment.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for creating a new payment order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {

    @NotBlank(message = "Merchant ID is required")
    private String merchantId;
```

Which merchant is creating this order. The service will validate the merchant exists.

```java
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
    @Digits(integer = 15, fraction = 4, message = "Amount format is invalid")
    private BigDecimal amount;
```

**🆕 THREE VALIDATIONS ON ONE FIELD — NEW CONCEPT**

| Annotation | What It Checks | Rejects |
|---|---|---|
| `@NotNull` | Field must be present | `null` (no amount provided) |
| `@DecimalMin("0.01")` | Value must be ≥ 0.01 | `0`, `0.00`, `-5` (zero/negative amounts) |
| `@Digits(integer=15, fraction=4)` | Max 15 digits before decimal, 4 after | `12345678901234567.89` (too many digits) |

**WHY `@NotNull` NOT `@NotBlank`?**
- `@NotBlank` is for **Strings** (checks whitespace)
- `@NotNull` is for **any type** including BigDecimal, Integer, etc.
- Using `@NotBlank` on BigDecimal → compilation error

**WHY `@DecimalMin("0.01")` NOT `@Positive`?**
Both reject zero and negative. But `@DecimalMin` lets you specify the exact minimum:
```
@Positive:         accepts 0.001 (any positive number)
@DecimalMin("0.01"): rejects 0.001 (must be at least 1 paisa/cent)
```

In a payment system, the minimum charge is 1 paisa (₹0.01). Sub-paisa amounts don't exist.

**WHY `@Digits(integer=15, fraction=4)`?**
Prevents overflow before it reaches the database:
```
DB column: NUMERIC(19,4) → max 15 integer digits + 4 fraction digits = 19 total
DTO validation: @Digits(integer=15, fraction=4) → matches the DB constraint
```

If you accepted 20 integer digits, the INSERT would fail at the DB level with a cryptic error. Catching it in validation gives a clean 400 error message.

```java
    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
    private String currency;
```

**`@Size(min = 3, max = 3)`** — exactly 3 characters. ISO 4217 currency codes: `"INR"`, `"USD"`, `"EUR"`.

```java
    @Email(message = "Customer email must be a valid email address")
    private String customerEmail;

    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    @Size(max = 100, message = "Receipt number must be at most 100 characters")
    private String receiptNumber;
}
```

All three OPTIONAL (no `@NotBlank` / `@NotNull`). `@Email` and `@Size` pass on null values (only validate if present).

---

## 4. Step-by-Step: AuthorizePaymentRequest.java

**File:** `src/main/java/com/payflow/payment/dto/AuthorizePaymentRequest.java`

This is the **most complex DTO** in the entire project — it handles 3 different payment methods in one class.

```java
package com.payflow.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for authorizing a payment against an order.
 * One of card, UPI, or net banking details must be provided based on paymentMethod.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizePaymentRequest {

    @NotBlank(message = "Order ID is required")
    private String orderId;
```

Which order to pay for. The service validates: order exists, not expired, not already paid.

```java
    @NotBlank(message = "Payment method is required")
    private String paymentMethod;
```

`"CARD"`, `"UPI"`, or `"NET_BANKING"`. Determines which fields below are used.

**WHY String NOT enum?** Validation is done in service code (after deserialization), not at the DTO level. This keeps the DTO simple and avoids deserialization errors for unknown values.

```java
    // --- Card details (when paymentMethod = CARD) ---
    @Size(min = 13, max = 19, message = "Card number must be between 13 and 19 digits")
    private String cardNumber;
```

**`@Size(min=13, max=19)`** — Card number length validation:

| Card Network | Length | Example |
|---|---|---|
| Visa | 13 or 16 | `4111111111111111` |
| Mastercard | 16 | `5500000000000004` |
| Amex | 15 | `378282246310005` |
| RuPay | 16 | `6521234567890123` |

Min=13 (old Visa) to max=19 (some newer cards). Stored in the DTO for authorization — NEVER stored in DB (only last 4 digits saved).

**WHY STRING NOT LONG?** Card numbers can start with `0` and might have leading zeros stripped if stored as numbers. String preserves the exact format.

```java
    @Size(min = 3, max = 4, message = "CVV must be 3 or 4 digits")
    private String cardCvv;
```

| Card Network | CVV Length |
|---|---|
| Visa, Mastercard, RuPay | 3 digits (back of card) |
| Amex | 4 digits (front of card, called CID) |

**🔒 CVV IS NEVER STORED — NOT EVEN IN LOGS.** The service sends it to the bank during authorization and immediately discards it. Storing CVV violates PCI DSS.

```java
    @Size(min = 2, max = 2, message = "Card expiry month must be 2 digits")
    private String cardExpiryMonth;

    @Size(min = 4, max = 4, message = "Card expiry year must be 4 digits")
    private String cardExpiryYear;

    private String cardHolderName;
```

`cardExpiryMonth`: `"01"` to `"12"`. `cardExpiryYear`: `"2025"`. `cardHolderName`: optional, no validation.

```java
    // --- UPI details (when paymentMethod = UPI) ---
    private String upiId;
```

Example: `"customer@oksbi"`, `"9876543210@paytm"`. No validation annotation — validated in service code.

```java
    // --- Net Banking details (when paymentMethod = NET_BANKING) ---
    private String bankCode;
    private String bankName;
}
```

**POLYMORPHIC DTO — HOW IT WORKS:**

```json
// CARD payment:
{
  "orderId": "order_abc",
  "paymentMethod": "CARD",
  "cardNumber": "4111111111111111",
  "cardCvv": "123",
  "cardExpiryMonth": "12",
  "cardExpiryYear": "2025",
  "cardHolderName": "Tejaswi Kumar"
}

// UPI payment:
{
  "orderId": "order_abc",
  "paymentMethod": "UPI",
  "upiId": "tejaswi@oksbi"
}

// Net Banking payment:
{
  "orderId": "order_abc",
  "paymentMethod": "NET_BANKING",
  "bankCode": "HDFC",
  "bankName": "HDFC Bank"
}
```

**The card fields are null when UPI is used, and vice versa.** The service code checks `paymentMethod` and reads only the relevant fields.

---

## 5. Step-by-Step: CapturePaymentRequest.java

**File:** `src/main/java/com/payflow/payment/dto/CapturePaymentRequest.java`

```java
package com.payflow.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for capturing an authorized payment.
 * If amount is null, full authorized amount is captured.
 * If amount is provided, a partial capture is performed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapturePaymentRequest {

    @NotBlank(message = "Payment ID is required")
    private String paymentId;

    @DecimalMin(value = "0.01", message = "Capture amount must be at least 0.01")
    @Digits(integer = 15, fraction = 4, message = "Amount format is invalid")
    private BigDecimal amount;
}
```

**THE CRITICAL DESIGN: `amount` IS OPTIONAL**

```
amount = null     → FULL CAPTURE (charge the entire authorized amount)
amount = 800.00   → PARTIAL CAPTURE (charge only ₹800 of ₹1000 authorized)
```

**NO `@NotNull` ON amount** — that's intentional. Null means "capture everything."

**REAL-WORLD EXAMPLE:**
```
Hotel: Authorized ₹10,000 at check-in
Checkout: Actual bill is ₹8,500
→ Capture ₹8,500 (partial capture)
→ Remaining ₹1,500 authorization is released back to customer
```

---

## 6. Step-by-Step: RefundRequest.java

**File:** `src/main/java/com/payflow/payment/dto/RefundRequest.java`

```java
package com.payflow.payment.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for creating a refund.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {

    @NotBlank(message = "Payment ID is required")
    private String paymentId;

    @NotNull(message = "Refund amount is required")
    @DecimalMin(value = "0.01", message = "Refund amount must be at least 0.01")
    @Digits(integer = 15, fraction = 4, message = "Amount format is invalid")
    private BigDecimal amount;

    @Size(max = 500, message = "Reason must be at most 500 characters")
    private String reason;
}
```

**DIFFERENCE FROM CaptureRequest:** Amount is REQUIRED (`@NotNull`). You must always specify how much to refund — there's no "refund everything" shortcut. This prevents accidental full refunds.

**`reason` IS OPTIONAL** — but good practice for the merchant to explain why ("Customer returned item", "Duplicate charge").

---

## 7. Step-by-Step: OrderResponse.java

**File:** `src/main/java/com/payflow/payment/dto/OrderResponse.java`

```java
package com.payflow.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO representing an order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    private String id;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String status;          // ← String, NOT OrderStatus enum
    private String customerEmail;
    private String description;
    private String receiptNumber;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
}
```

**KEY: `status` is `String` NOT `OrderStatus` enum.**

| Entity (Order.java) | Response DTO |
|---|---|
| `private OrderStatus status;` (enum) | `private String status;` (string) |

**WHY String IN RESPONSE?** The JSON output should be `"status": "CREATED"` — a plain string. If you return an enum, Jackson serializes it the same way, but using String in the DTO:
1. Decouples the DTO from the enum (DTO can outlive enum changes)
2. Makes the mapper explicit about the conversion (no hidden magic)
3. Simpler for API consumers (they parse a string, not a Java enum)

The MapStruct mapper handles the conversion: `order.getStatus().name()` → `"CREATED"`.

---

## 8. Step-by-Step: PaymentResponse.java

**File:** `src/main/java/com/payflow/payment/dto/PaymentResponse.java`

```java
package com.payflow.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO representing a payment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private String id;
    private String orderId;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String status;            // "AUTHORIZED", "CAPTURED", etc.
    private String paymentMethod;     // "CARD", "UPI", "NET_BANKING"
    private String authorizationCode; // Bank's approval code (null if not authorized)
    private String bankReferenceId;   // Bank's transaction ID (null if not authorized)
    private String failureReason;     // Why it failed (null if not failed)
    private Instant createdAt;
    private Instant updatedAt;
}
```

**TWO ENUM FIELDS → TWO STRINGS:** Both `status` and `paymentMethod` are Strings in the DTO but enums in the entity. The PaymentMapper converts both.

**CONDITIONAL FIELDS:** `authorizationCode`, `bankReferenceId`, and `failureReason` are null in most states. The `@JsonInclude(NON_NULL)` annotation (from common-lib's ApiResponse) omits null fields from JSON output.

---

## 9. Step-by-Step: RefundResponse.java

**File:** `src/main/java/com/payflow/payment/dto/RefundResponse.java`

```java
package com.payflow.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO representing a refund.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundResponse {

    private String id;
    private String paymentId;
    private String merchantId;
    private BigDecimal amount;
    private String reason;
    private String status;
    private Instant createdAt;
    // NO updatedAt — refunds are immutable
}
```

**No `updatedAt`** — matches the entity (refunds are created once, never modified).

---

## 10. Step-by-Step: OrderMapper.java

**File:** `src/main/java/com/payflow/payment/mapper/OrderMapper.java`

```java
package com.payflow.payment.mapper;

import com.payflow.payment.dto.OrderResponse;
import com.payflow.payment.model.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for Order entity ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "status", expression = "java(order.getStatus().name())")
    OrderResponse toResponse(Order order);
}
```

**🆕 `expression = "java(...)"` — NEW MAPSTRUCT CONCEPT**

**THE PROBLEM:** Entity has `OrderStatus status` (enum). DTO has `String status`. MapStruct can't auto-map enum → String.

**THE SOLUTION:** `expression` tells MapStruct to use a JAVA EXPRESSION instead of auto-mapping:

```java
expression = "java(order.getStatus().name())"
//                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                  Java code that runs at runtime

// OrderStatus.CREATED.name() → "CREATED" (String)
// OrderStatus.PAID.name()    → "PAID" (String)
```

**WHAT MAPSTRUCT GENERATES:**
```java
// Generated OrderMapperImpl.java:
@Override
public OrderResponse toResponse(Order order) {
    if (order == null) return null;
    
    OrderResponse.OrderResponseBuilder response = OrderResponse.builder();
    response.id(order.getId());                    // auto-mapped (same name + type)
    response.merchantId(order.getMerchantId());     // auto-mapped
    response.amount(order.getAmount());             // auto-mapped
    response.currency(order.getCurrency());         // auto-mapped
    response.status(order.getStatus().name());      // ← FROM EXPRESSION
    response.customerEmail(order.getCustomerEmail()); // auto-mapped
    response.description(order.getDescription());   // auto-mapped
    response.receiptNumber(order.getReceiptNumber()); // auto-mapped
    response.expiresAt(order.getExpiresAt());       // auto-mapped
    response.createdAt(order.getCreatedAt());       // auto-mapped
    response.updatedAt(order.getUpdatedAt());       // auto-mapped
    return response.build();
}
```

10 fields auto-mapped, 1 field via expression. MapStruct generates all of this from a 2-line interface.

---

## 11. Step-by-Step: PaymentMapper.java

**File:** `src/main/java/com/payflow/payment/mapper/PaymentMapper.java`

```java
package com.payflow.payment.mapper;

import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.model.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for Payment entity ↔ DTO conversions.
 */
@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "status", expression = "java(payment.getStatus().name())")
    @Mapping(target = "paymentMethod", expression = "java(payment.getPaymentMethod().name())")
    PaymentResponse toResponse(Payment payment);
}
```

**TWO EXPRESSIONS — TWO ENUM CONVERSIONS:**

| Entity Field | Type | Expression | DTO Field | Type |
|---|---|---|---|---|
| `payment.getStatus()` | `PaymentStatus` enum | `.name()` → `"AUTHORIZED"` | `status` | `String` |
| `payment.getPaymentMethod()` | `PaymentMethod` enum | `.name()` → `"CARD"` | `paymentMethod` | `String` |

All other fields (id, orderId, merchantId, amount, currency, authorizationCode, bankReferenceId, failureReason, createdAt, updatedAt) are auto-mapped by matching name.

**WHY NO RefundMapper?**
Refund entity uses `String status` (not enum) and all field names match the DTO. Manual mapping in RefundService is simpler:
```java
RefundResponse.builder()
    .id(refund.getId())
    .paymentId(refund.getPaymentId())
    // ... all fields match directly
    .build();
```

For just 7 fields with matching names and types, a mapper interface adds overhead without benefit.

---

## 12. Validation Annotations Summary

### All Annotations Used in Payment DTOs

| Annotation | On Field | What | Null Handling |
|---|---|---|---|
| `@NotBlank` | merchantId, orderId, paymentId, paymentMethod, currency | Not null/empty/whitespace | null → FAIL |
| `@NotNull` | amount (order, refund) | Not null | null → FAIL |
| `@DecimalMin("0.01")` | amount (order, capture, refund) | ≥ 0.01 | null → PASS |
| `@Digits(15,4)` | amount (all) | Max 15 integer + 4 fraction digits | null → PASS |
| `@Size(min,max)` | currency (3,3), cardNumber (13,19), cardCvv (3,4), expiryMonth (2,2), expiryYear (4,4) | Length range | null → PASS |
| `@Email` | customerEmail | Valid email format | null → PASS |
| `@Size(max)` | description (500), receiptNumber (100), reason (500) | Max length | null → PASS |

### The "Optional Amount" Pattern

| DTO | Amount Required? | null Means |
|---|---|---|
| CreateOrderRequest | ✅ `@NotNull` | Error — must specify order amount |
| CapturePaymentRequest | ❌ No @NotNull | Full capture (charge entire authorized amount) |
| RefundRequest | ✅ `@NotNull` | Error — must specify refund amount (safety) |

---

## 13. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **@NotNull for BigDecimal** | Use @NotNull (not @NotBlank) for non-String types |
| 2 | **@DecimalMin("0.01")** | Minimum payment amount — no zero or negative charges |
| 3 | **@Digits(integer,fraction)** | Matches DB precision — prevents overflow at validation layer |
| 4 | **@Size(min=3,max=3)** | Exact length for ISO currency codes |
| 5 | **@Size(min=13,max=19)** | Card number length varies by network (Visa 13-16, Amex 15, etc.) |
| 6 | **CVV: 3 or 4 digits** | Most cards: 3. Amex: 4. NEVER stored in DB or logs. |
| 7 | **Polymorphic DTO** | One DTO, multiple payment methods — null fields for unused types |
| 8 | **Optional amount** | null = full capture. @NotNull required on refund for safety. |
| 9 | **String status in DTOs** | Entity uses enum, DTO uses String — mapper converts via `.name()` |
| 10 | **MapStruct expression** | `java(entity.getEnum().name())` — inline Java code for custom mapping |
| 11 | **Two expressions in one mapper** | PaymentMapper converts both `status` AND `paymentMethod` enums |
| 12 | **No RefundMapper** | Simple entities with matching types don't need MapStruct — manual builder is simpler |
| 13 | **Card number as String** | Never Long/Integer — preserves leading zeros, exact format |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part08-payment-service-overview.md) | Payment Service Overview |
| [Part 8a](./phase4-part08a-payment-project-setup.md) | Project Setup |
| [Part 8b](./phase4-part08b-payment-entities.md) | Entities |
| [Part 8c](./phase4-part08c-payment-migrations.md) | Flyway Migrations |
| [Part 8d](./phase4-part08d-payment-repositories.md) | Repositories |
| **Part 8e** | **DTOs + Mappers** (You are here) |
| [Part 8f](./phase4-part08f-payment-configs.md) | Config Classes |
| [Part 8g](./phase4-part08g-payment-order-refund-services.md) | OrderService + RefundService |
| [Part 8h](./phase4-part08h-payment-idempotency-events.md) | IdempotencyService + Events |
| [Part 8i](./phase4-part08i-payment-engine.md) | PaymentService Core Engine |
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + Docker |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | Connections & Flows |

---

*Next: [Part 8f — Config Classes (Redis, Kafka, Feign)](./phase4-part08f-payment-configs.md) →*
