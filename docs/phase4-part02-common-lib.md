# 🏗️ Phase 4 Part 2: Common Library

> **"A well-designed shared library prevents code duplication and ensures consistency across every microservice in your gateway."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 2 of 12 |
| **Module** | `common-lib` |
| **Package** | `com.payflow.common` |
| **Previous** | [Phase 4 Part 1: Parent POM & Maven Setup](./phase4-part01-parent-pom-and-maven-setup.md) |
| **Next** | [Phase 4 Part 3: Service Registry (Eureka)](./phase4-part03-service-registry.md) |
| **Technologies** | Java 17, Lombok, Jackson, Jakarta Validation, MapStruct |
| **Packaging** | JAR (library — no Spring Boot repackage) |

---

## 📖 Table of Contents

1. [Overview](#1-overview)
2. [Project Structure](#2-project-structure)
3. [POM Configuration](#3-pom-configuration)
4. [Shared DTOs](#4-shared-dtos)
5. [Custom Exception Classes](#5-custom-exception-classes)
6. [Kafka Event Classes](#6-kafka-event-classes)
7. [Constants & Enums](#7-constants--enums)
8. [Utility Classes](#8-utility-classes)
9. [How Services Consume common-lib](#9-how-services-consume-common-lib)
10. [What You Learned](#10-what-you-learned)
11. [Document Index](#11-document-index)
12. [Next Steps](#12-next-steps)

---

## 1. Overview

The `common-lib` module is a plain JAR (not a runnable Spring Boot app) that contains all shared code used across PayFlow microservices:

- **DTOs** — Request/response objects used by controllers and service layers
- **Exceptions** — Application-specific exceptions with error codes mapped to HTTP statuses
- **Events** — Kafka event payloads for asynchronous communication between services
- **Constants** — Enums for payment statuses, currencies, roles, webhook types
- **Utilities** — Helper classes for dates, IDs, HMAC, masking

Every microservice declares `common-lib` as a Maven dependency, so any change here propagates to all services on the next build.

---

## 2. Project Structure

```
backend/common-lib/
├── pom.xml
└── src/main/java/com/payflow/common/
    ├── constant/
    │   ├── CurrencyCode.java         # Supported currencies (INR, USD, EUR, GBP)
    │   ├── OrderStatus.java          # Order lifecycle states
    │   ├── PaymentMethod.java        # CARD, UPI, NET_BANKING, WALLET
    │   ├── PaymentStatus.java        # CREATED → AUTHORIZED → CAPTURED → REFUNDED
    │   ├── UserRole.java             # ADMIN, MERCHANT roles
    │   └── WebhookEventType.java     # payment.authorized, payment.captured, etc.
    ├── dto/
    │   ├── ApiResponse.java          # Generic success/error wrapper
    │   ├── ErrorResponse.java        # Structured error payload
    │   ├── PagedResponse.java        # Paginated list wrapper
    │   └── ValidationError.java      # Field-level validation errors
    ├── event/
    │   ├── NotificationEvent.java    # Email/SMS trigger events
    │   ├── PaymentEvent.java         # Payment state change events
    │   ├── SettlementEvent.java      # Settlement batch events
    │   └── WebhookEvent.java         # Webhook delivery events
    ├── exception/
    │   ├── DuplicateResourceException.java
    │   ├── ForbiddenException.java
    │   ├── IdempotencyConflictException.java
    │   ├── PayflowException.java     # Base exception class
    │   ├── PaymentDeclinedException.java
    │   ├── RateLimitExceededException.java
    │   ├── ResourceNotFoundException.java
    │   └── UnauthorizedException.java
    └── util/
        ├── DateUtils.java            # IST date/time helpers
        ├── HmacUtils.java            # HMAC-SHA256 for webhook signatures
        ├── IdGenerator.java          # Prefixed unique ID generation
        └── MaskingUtils.java         # PAN/email/UPI masking
```

---

## 3. POM Configuration

The `common-lib` POM inherits from the parent but disables Spring Boot repackaging since this is a library JAR, not a standalone app.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.payflow</groupId>
        <artifactId>payflow-payment-gateway</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>common-lib</artifactId>
    <name>PayFlow Common Library</name>
    <description>Shared DTOs, events, exceptions, constants, and utilities</description>

    <dependencies>
        <!-- Spring Boot Web (provides @RestControllerAdvice, ResponseEntity, etc.) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Spring Boot Validation (Jakarta Bean Validation) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Jackson (JSON serialization — already included via web, explicit for clarity) -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>

        <!-- MapStruct for DTO ↔ Entity mapping -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
        </dependency>
    </dependencies>

    <!-- common-lib is a library JAR, not a Spring Boot app — disable repackage -->
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <skip>true</skip> <!-- Critical: prevents fat JAR creation -->
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

**Key decisions:**
- `spring-boot-maven-plugin` → `<skip>true</skip>` — Without this, Maven would try to create an executable JAR that other modules cannot use as a dependency.
- Lombok is inherited from the parent POM (annotation processor configured globally).
- No `spring-boot-starter` dependency — individual services provide their own starters.

---

## 4. Shared DTOs

### 4.1 ApiResponse — Universal Response Wrapper

Every endpoint in PayFlow returns this structure for consistency across all services:

```java
package com.payflow.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Generic API response wrapper used by ALL services.
 * Every endpoint returns this structure for consistency.
 *
 * Success: {success: true, data: {...}, timestamp: "..."}
 * Error:   {success: false, error: {...}, timestamp: "..."}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)  // Omit null fields from JSON output
public class ApiResponse<T> {

    private boolean success;       // true for 2xx, false for 4xx/5xx
    private T data;                // Response payload (null on error)
    private ErrorResponse error;   // Error details (null on success)

    @Builder.Default
    private Instant timestamp = Instant.now();  // When the response was generated

    private String path;           // Request path (included on errors)

    /** Factory method for successful responses */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(Instant.now())
                .build();
    }

    /** Factory method for error responses */
    public static <T> ApiResponse<T> error(ErrorResponse error) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(error)
                .timestamp(Instant.now())
                .build();
    }

    /** Factory method for error responses with path */
    public static <T> ApiResponse<T> error(ErrorResponse error, String path) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(error)
                .path(path)
                .timestamp(Instant.now())
                .build();
    }
}
```

### 4.2 ErrorResponse — Structured Error Payload

```java
package com.payflow.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured error response returned when an API call fails.
 * Contains error code, human-readable message, and optional field-level details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private String code;                    // Machine-readable error code (e.g., "RESOURCE_NOT_FOUND")
    private String message;                 // Human-readable description
    private List<ValidationError> details;  // Field-level validation errors (optional)

    public static ErrorResponse of(String code, String message) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .build();
    }

    public static ErrorResponse withDetails(String code, String message, List<ValidationError> details) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .details(details)
                .build();
    }
}
```

### 4.3 PagedResponse — Pagination Wrapper

```java
package com.payflow.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated response wrapper for list endpoints.
 * Used when returning collections (transactions, merchants, settlements).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    private List<T> items;         // Current page data
    private int page;              // Current page number (0-indexed)
    private int size;              // Items per page
    private long totalElements;    // Total items across all pages
    private int totalPages;        // Total number of pages
    private boolean hasNext;       // Is there a next page?
    private boolean hasPrevious;   // Is there a previous page?
}
```

---

## 5. Custom Exception Classes

### 5.1 Base Exception — PayflowException

All custom exceptions extend this base class, which carries an error code:

```java
package com.payflow.common.exception;

import lombok.Getter;

/**
 * Base exception for all PayFlow application-specific errors.
 * All custom exceptions extend this class.
 */
@Getter
public class PayflowException extends RuntimeException {

    private final String errorCode;  // Machine-readable code for client consumption

    public PayflowException(String message) {
        super(message);
        this.errorCode = "PAYFLOW_ERROR";
    }

    public PayflowException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PayflowException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
```

### 5.2 Exception Hierarchy

| Exception Class | Error Code | HTTP Status | Use Case |
|----------------|-----------|-------------|----------|
| `ResourceNotFoundException` | `RESOURCE_NOT_FOUND` | 404 | Payment, merchant, order not found |
| `DuplicateResourceException` | `DUPLICATE_RESOURCE` | 409 | Email already registered, duplicate key |
| `UnauthorizedException` | `UNAUTHORIZED` | 401 | Invalid credentials, expired token |
| `PaymentDeclinedException` | `PAYMENT_DECLINED` | 402 | Bank declined the transaction |
| `ForbiddenException` | `FORBIDDEN` | 403 | Insufficient permissions |
| `IdempotencyConflictException` | `IDEMPOTENCY_CONFLICT` | 409 | Duplicate idempotency key with different payload |
| `RateLimitExceededException` | `RATE_LIMIT_EXCEEDED` | 429 | Too many requests |

### 5.3 ResourceNotFoundException

```java
package com.payflow.common.exception;

/**
 * Thrown when a requested resource (payment, order, merchant) does not exist.
 * Maps to HTTP 404.
 */
public class ResourceNotFoundException extends PayflowException {

    public ResourceNotFoundException(String resource, String id) {
        super("RESOURCE_NOT_FOUND", String.format("%s not found with id: %s", resource, id));
    }

    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}
```

### 5.4 DuplicateResourceException

```java
package com.payflow.common.exception;

/**
 * Thrown when attempting to create a resource that already exists.
 * Example: registering with an email already in use.
 * Maps to HTTP 409 Conflict.
 */
public class DuplicateResourceException extends PayflowException {

    public DuplicateResourceException(String resource, String field, String value) {
        super("DUPLICATE_RESOURCE",
                String.format("%s already exists with %s: %s", resource, field, value));
    }

    public DuplicateResourceException(String message) {
        super("DUPLICATE_RESOURCE", message);
    }
}
```

### 5.5 PaymentDeclinedException

```java
package com.payflow.common.exception;

import lombok.Getter;

/**
 * Thrown when a payment is declined by the bank/issuer.
 * Contains the decline reason code from the bank.
 * Maps to HTTP 402 Payment Required.
 */
@Getter
public class PaymentDeclinedException extends PayflowException {

    private final String declineCode;    // Bank's decline code (e.g., "DO_NOT_HONOR")
    private final String declineReason;  // Human-readable decline reason

    public PaymentDeclinedException(String declineCode, String declineReason) {
        super("PAYMENT_DECLINED",
                String.format("Payment declined: %s (%s)", declineReason, declineCode));
        this.declineCode = declineCode;
        this.declineReason = declineReason;
    }
}
```

### 5.6 UnauthorizedException

```java
package com.payflow.common.exception;

/**
 * Thrown when authentication fails (invalid credentials, expired token).
 * Maps to HTTP 401 Unauthorized.
 */
public class UnauthorizedException extends PayflowException {

    public UnauthorizedException(String message) {
        super("UNAUTHORIZED", message);
    }

    public UnauthorizedException() {
        super("UNAUTHORIZED", "Authentication required");
    }
}
```

---

## 6. Kafka Event Classes

### 6.1 PaymentEvent — Payment State Changes

Published to Kafka topics when a payment transitions between states:

```java
package com.payflow.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kafka event payload for payment state changes.
 * Published to topics: payment.authorized, payment.captured, payment.failed, payment.refunded
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {

    private String eventId;         // Unique event ID (evt_xxxxxxxxxxxx)
    private String eventType;       // e.g., "payment.authorized"
    private String paymentId;       // PayFlow payment ID (pay_xxxxxxxxxxxx)
    private String orderId;         // PayFlow order ID (order_xxxxxxxxxxxx)
    private String merchantId;      // Merchant who owns this payment
    private BigDecimal amount;      // Transaction amount
    private String currency;        // ISO 4217 currency code
    private String status;          // New payment status
    private String paymentMethod;   // CARD, UPI, NET_BANKING, WALLET
    private Instant timestamp;      // When the event occurred
    private String metadata;        // Additional JSON metadata
}
```

### 6.2 NotificationEvent — Email/SMS Triggers

```java
package com.payflow.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Kafka event payload for email/SMS notifications.
 * Consumed by notification-service to send communications to customers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    private String eventId;                    // Unique event ID
    private String type;                       // EMAIL or SMS
    private String recipient;                  // email address or phone number
    private String templateName;               // e.g., "payment-success", "refund-processed"
    private Map<String, String> templateData;  // Template variables
    private Instant timestamp;                 // When event was created
}
```

### 6.3 SettlementEvent — Settlement Batches

```java
package com.payflow.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Kafka event payload for settlement triggers.
 * Published when a settlement batch completes or a payout is initiated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementEvent {

    private String eventId;          // Unique event ID
    private String eventType;        // settlement.completed, settlement.payout
    private String batchId;          // Settlement batch identifier
    private String merchantId;       // Target merchant
    private BigDecimal grossAmount;  // Total amount before deductions
    private BigDecimal netAmount;    // Amount after MDR + GST
    private BigDecimal mdrAmount;    // Merchant Discount Rate fee
    private BigDecimal gstAmount;    // GST on MDR
    private LocalDate settlementDate;// Settlement date
    private String status;           // COMPLETED, PAYOUT_INITIATED
    private Instant timestamp;       // Event timestamp
}
```

### 6.4 WebhookEvent — Merchant Webhook Delivery

```java
package com.payflow.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Kafka event payload for webhook delivery.
 * Consumed by webhook-service to deliver notifications to merchant endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEvent {

    private String eventId;      // Unique event ID
    private String eventType;    // e.g., "payment.authorized"
    private String merchantId;   // Target merchant
    private String webhookUrl;   // Merchant's webhook endpoint URL
    private String payload;      // JSON payload to deliver
    private String secret;       // Signing secret for HMAC verification
    private Instant timestamp;   // When the event was created
}
```

---

## 7. Constants & Enums

### 7.1 PaymentStatus — State Machine

```java
package com.payflow.common.constant;

/**
 * Payment lifecycle states (state machine).
 * CREATED → AUTHORIZED → CAPTURED → REFUNDED
 *                      → VOIDED
 * CREATED → FAILED
 */
public enum PaymentStatus {
    CREATED,     // Payment initiated, not yet sent to bank
    AUTHORIZED,  // Bank approved — money reserved but not collected
    CAPTURED,    // Money collected from customer's account
    REFUNDED,    // Money returned to customer (after capture)
    VOIDED,      // Authorization cancelled (before capture)
    FAILED       // Bank declined or error occurred
}
```

**State Machine Diagram:**

```
┌──────────┐     authorize()      ┌────────────┐     capture()      ┌──────────┐
│  CREATED │ ──────────────────► │ AUTHORIZED │ ──────────────────► │ CAPTURED │
└──────────┘                      └────────────┘                      └──────────┘
     │                                  │                                  │
     │ fail()                           │ void()                           │ refund()
     ▼                                  ▼                                  ▼
┌──────────┐                      ┌──────────┐                      ┌──────────┐
│  FAILED  │                      │  VOIDED  │                      │ REFUNDED │
└──────────┘                      └──────────┘                      └──────────┘
```

### 7.2 WebhookEventType

```java
package com.payflow.common.constant;

/**
 * Types of events that can be delivered via webhooks to merchants.
 */
public enum WebhookEventType {
    PAYMENT_AUTHORIZED("payment.authorized"),
    PAYMENT_CAPTURED("payment.captured"),
    PAYMENT_FAILED("payment.failed"),
    PAYMENT_REFUNDED("payment.refunded"),
    SETTLEMENT_COMPLETED("settlement.completed"),
    SETTLEMENT_PAYOUT("settlement.payout");

    private final String value;

    WebhookEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static WebhookEventType fromValue(String value) {
        for (WebhookEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown webhook event type: " + value);
    }
}
```

---

## 8. Utility Classes

### 8.1 IdGenerator — Prefixed Unique IDs

PayFlow uses readable prefixed IDs (like Stripe) instead of raw UUIDs:

```java
package com.payflow.common.util;

import java.util.UUID;

/**
 * Generates unique IDs for all PayFlow entities.
 * Format: prefix_uuid (e.g., pay_abc123, order_xyz789, mer_def456)
 */
public final class IdGenerator {

    private IdGenerator() {
        // Utility class — prevent instantiation
    }

    public static String generatePaymentId()    { return "pay_" + shortUuid(); }
    public static String generateOrderId()      { return "order_" + shortUuid(); }
    public static String generateMerchantId()   { return "mer_" + shortUuid(); }
    public static String generateRefundId()     { return "rfnd_" + shortUuid(); }
    public static String generateSettlementId() { return "stl_" + shortUuid(); }
    public static String generatePayoutId()     { return "pout_" + shortUuid(); }
    public static String generateWebhookId()    { return "whk_" + shortUuid(); }
    public static String generateEventId()      { return "evt_" + shortUuid(); }
    public static String generateApiKeyId()     { return "key_" + shortUuid(); }

    /**
     * Generates a shortened UUID (first 12 chars, no dashes).
     * Provides sufficient uniqueness for this application.
     */
    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Generates a full UUID when maximum uniqueness is required. */
    public static String fullUuid() {
        return UUID.randomUUID().toString();
    }
}
```

**Example IDs generated:**
| Entity | Prefix | Example |
|--------|--------|---------|
| Payment | `pay_` | `pay_7a3f9b2c1d4e` |
| Order | `order_` | `order_b2c1d4e7a3f9` |
| Merchant | `mer_` | `mer_1d4e7a3f9b2c` |
| Refund | `rfnd_` | `rfnd_4e7a3f9b2c1d` |
| API Key | `key_` | `key_9b2c1d4e7a3f` |

### 8.2 DateUtils — IST Date/Time Helpers

```java
package com.payflow.common.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Date/time utility methods used across services.
 */
public final class DateUtils {

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private DateUtils() {}

    public static String formatInstant(Instant instant) {
        return ISO_FORMAT.format(instant);
    }

    public static LocalDate todayIST() {
        return LocalDate.now(IST);
    }

    public static Instant startOfDayIST(LocalDate date) {
        return date.atStartOfDay(IST).toInstant();
    }

    public static Instant endOfDayIST(LocalDate date) {
        return date.plusDays(1).atStartOfDay(IST).toInstant().minusMillis(1);
    }

    public static boolean isExpired(Instant expiresAt) {
        return Instant.now().isAfter(expiresAt);
    }
}
```

### 8.3 HmacUtils — Webhook Signature Verification

```java
package com.payflow.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 computation utility.
 * Used for webhook signature verification and API key hashing.
 */
public final class HmacUtils {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacUtils() {}

    /**
     * Computes HMAC-SHA256 of the given payload using the secret.
     * Returns the result as a lowercase hex string.
     */
    public static String computeHmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }

    /**
     * Verifies an HMAC signature using constant-time comparison
     * to prevent timing attacks.
     */
    public static boolean verifySignature(String payload, String secret, String expectedSignature) {
        String computed = computeHmacSha256(payload, secret);
        return constantTimeEquals(computed, expectedSignature);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
```

### 8.4 MaskingUtils — PCI Compliance

```java
package com.payflow.common.util;

/**
 * Utility for masking sensitive data (card numbers, emails).
 * Used to prevent full PAN exposure in logs and API responses.
 */
public final class MaskingUtils {

    private MaskingUtils() {}

    /**
     * Masks a card number showing only first 4 and last 4 digits.
     * Example: 4111111111111111 → 4111****1111
     */
    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) return "****";
        String cleaned = cardNumber.replaceAll("\\s+", "");
        return cleaned.substring(0, 4) + "****" + cleaned.substring(cleaned.length() - 4);
    }

    /**
     * Masks an email address.
     * Example: john.doe@example.com → j***e@example.com
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "****";
        String[] parts = email.split("@");
        String local = parts[0];
        if (local.length() <= 2) return local.charAt(0) + "***@" + parts[1];
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + "@" + parts[1];
    }

    /**
     * Masks a UPI ID.
     * Example: user@oksbi → u***r@oksbi
     */
    public static String maskUpiId(String upiId) {
        if (upiId == null || !upiId.contains("@")) return "****";
        String[] parts = upiId.split("@");
        String local = parts[0];
        if (local.length() <= 2) return local.charAt(0) + "***@" + parts[1];
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + "@" + parts[1];
    }
}
```

---

## 9. How Services Consume common-lib

Each microservice declares a dependency on `common-lib` in its POM:

```xml
<dependency>
    <groupId>com.payflow</groupId>
    <artifactId>common-lib</artifactId>
    <version>${project.version}</version>
</dependency>
```

Then uses shared classes directly:

```java
// In payment-service controller
import com.payflow.common.dto.ApiResponse;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.common.event.PaymentEvent;
import com.payflow.common.util.IdGenerator;

@GetMapping("/v1/payments/{id}")
public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@PathVariable String id) {
    Payment payment = paymentRepository.findByPaymentId(id)
        .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    return ResponseEntity.ok(ApiResponse.success(mapper.toResponse(payment)));
}
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | Library vs App JAR | Set `spring-boot-maven-plugin` skip=true for library modules |
| 2 | Generic Response Wrapper | `ApiResponse<T>` ensures consistent JSON structure across all endpoints |
| 3 | Exception Hierarchy | Base `PayflowException` carries error codes; subclasses map to HTTP statuses |
| 4 | Kafka Event Design | Events are flat DTOs with `eventId`, `eventType`, and `timestamp` for tracing |
| 5 | Prefixed IDs | `pay_`, `order_`, `mer_` prefixes make IDs human-readable and debuggable |
| 6 | Constant-time Comparison | `HmacUtils.constantTimeEquals()` prevents timing attacks on signature verification |
| 7 | PCI Masking | Never log or expose full card numbers — use `MaskingUtils` everywhere |
| 8 | State Machine Enums | `PaymentStatus` documents valid transitions in comments |
| 9 | IST Date Handling | `DateUtils` centralizes timezone logic to avoid scattered `ZoneId` usage |
| 10 | `@JsonInclude(NON_NULL)` | Omits null fields from JSON output for cleaner API responses |

---

## 📚 Document Index

| Part | Title | Status |
|------|-------|--------|
| 4.01 | Parent POM & Maven Setup | ✅ Complete |
| **4.02** | **Common Library** | **📍 You are here** |
| 4.03 | Service Registry (Eureka) | ⏭️ Next |
| 4.04 | Config Server | 🔲 Pending |
| 4.05 | API Gateway | 🔲 Pending |
| 4.06a | Identity Service — Entities | 🔲 Pending |
| 4.06b | Identity Service — Auth Logic | 🔲 Pending |
| 4.07 | Merchant Service | 🔲 Pending |
| 4.08 | Payment Service | 🔲 Pending |
| 4.09 | Settlement Service | 🔲 Pending |
| 4.10 | Notification & Webhook Services | 🔲 Pending |
| 4.11 | Docker Compose & Integration | 🔲 Pending |
| 4.12 | Testing Strategy | 🔲 Pending |

---

## 🚀 Next Steps

In **Phase 4 Part 3**, we build the **Service Registry (Eureka Server)** — the backbone of service discovery that allows all microservices to find each other without hardcoded URLs.

→ [Continue to Phase 4 Part 3: Service Registry (Eureka)](./phase4-part03-service-registry.md)
