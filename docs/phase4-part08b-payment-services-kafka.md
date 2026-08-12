# Phase 4 Part 8b: Payment Service — Services, Kafka & Idempotency

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Hands-On Coding |
| **Part** | 8b of 16 (Payment Service — Business Logic) |
| **Previous** | [Phase 4 Part 8a: Payment Entities](./phase4-part08a-payment-entities.md) |
| **Next** | [Phase 4 Part 8c: Payment Controllers](./phase4-part08c-payment-controllers.md) |
| **Time to Complete** | 3-4 hours |
| **Difficulty** | Advanced |
| **Prerequisites** | Part 8a completed, Redis running, Kafka running |
| **What You'll Build** | Payment state machine, idempotency with Redis, Kafka event publishing |
| **Git Commit** | "Phase 4 Part 8b: Add payment services, idempotency, and Kafka events" |

---

## Table of Contents
- [Step 1: Payment State Machine](#state-machine)
- [Step 2: OrderService](#order-service)
- [Step 3: IdempotencyService (Redis)](#idempotency-service)
- [Step 4: EventPublisher Interface](#event-publisher)
- [Step 5: KafkaEventPublisher](#kafka-publisher)
- [Step 6: PaymentService (The Core)](#payment-service)
- [Step 7: RefundService](#refund-service)
- [Step 8: Configuration Classes](#config)
- [Verification](#verification)
- [What You Learned](#what-you-learned)
- [Common Errors & Fixes](#common-errors)
- [Git Commit](#git-commit)

---

## What You'll Learn in This Part
- How a payment state machine works (which transitions are valid)
- How to prevent duplicate payments using Redis idempotency
- How to publish events to Kafka when payment state changes
- How to design an abstraction (interface) for event publishing
- How Feign clients call other microservices

---

<a name="state-machine"></a>
## Step 1: Understanding the Payment State Machine

Before writing code, understand which state transitions are VALID:

```
                         ┌──────────── Bank Declines ─────────────┐
                         │                                         ▼
                   ┌─────────┐                              ┌──────────┐
  Order Created ──>│ CREATED │                              │  FAILED  │
                   └────┬────┘                              └──────────┘
                        │
                        │ Bank Approves (auth code received)
                        ▼
                 ┌──────────────┐
                 │  AUTHORIZED  │──── Merchant Voids ────> ┌────────┐
                 └──────┬───────┘     (cancel before       │ VOIDED │
                        │              capture)             └────────┘
                        │
                        │ Merchant Captures (collect money)
                        ▼
                  ┌──────────┐
                  │ CAPTURED │──── Merchant Refunds ───> ┌──────────┐
                  └──────────┘     (return money)        │ REFUNDED │
                                                         └──────────┘
```

**Valid transitions (any other transition is ILLEGAL):**

| From | To | Trigger |
|------|----|---------|
| CREATED | AUTHORIZED | Bank responds "00" (approved) |
| CREATED | FAILED | Bank responds "05", "51", etc. (declined) |
| AUTHORIZED | CAPTURED | Merchant calls capture endpoint |
| AUTHORIZED | VOIDED | Merchant calls void endpoint |
| CAPTURED | REFUNDED | Merchant calls refund endpoint |

**Invalid transitions (must REJECT with error):**
- CAPTURED → AUTHORIZED (can't un-capture)
- FAILED → AUTHORIZED (can't resurrect failed payment)
- VOIDED → CAPTURED (voided means cancelled)
- REFUNDED → CAPTURED (money already returned)

---

<a name="order-service"></a>
## Step 2: OrderService

**File:** `backend/payment-service/src/main/java/com/payflow/payment/service/OrderService.java`

```java
package com.payflow.payment.service;

import com.payflow.common.constant.OrderStatus;
// OrderStatus enum from common-lib: CREATED, ATTEMPTED, PAID, EXPIRED

import com.payflow.common.exception.ResourceNotFoundException;
// Thrown when order ID doesn't exist in database

import com.payflow.common.util.IdGenerator;
// Generates prefixed UUIDs: "order_abc123def456"

import com.payflow.payment.dto.CreateOrderRequest;
import com.payflow.payment.dto.OrderResponse;
import com.payflow.payment.mapper.OrderMapper;
import com.payflow.payment.model.Order;
import com.payflow.payment.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
// @Service tells Spring: "this is a business logic bean, manage its lifecycle"
// Spring creates ONE instance and injects it wherever needed

@RequiredArgsConstructor
// Lombok generates a constructor with all `final` fields
// Spring uses this constructor for dependency injection (constructor injection)
// WHY constructor injection over @Autowired? → testable (pass mocks in tests)

public class OrderService {

    private final OrderRepository orderRepository;
    // Spring injects the JPA repository implementation here

    private final OrderMapper orderMapper;
    // MapStruct-generated mapper (Entity ↔ DTO conversion)

    /**
     * Create a new payment order.
     * Called when merchant initiates a payment: POST /v1/orders
     */
    @Transactional
    // @Transactional: if ANY exception occurs, ALL database changes roll back
    // Without this, a half-saved order could exist in the DB
    public OrderResponse createOrder(CreateOrderRequest request) {

        // Build the Order entity
        Order order = Order.builder()
                .id(IdGenerator.generateOrderId())
                // Generates "order_" + 12-char UUID, e.g., "order_a1b2c3d4e5f6"
                .merchantId(request.getMerchantId())
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .status(OrderStatus.CREATED)
                // New orders always start in CREATED state
                .customerEmail(request.getCustomerEmail())
                .description(request.getDescription())
                .receiptNumber(request.getReceiptNumber())
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                // Orders expire in 30 minutes
                // WHY expire? Don't hold bank authorization forever
                // Customer must pay within 30 minutes
                .build();

        Order saved = orderRepository.save(order);
        // JPA INSERT into orders table

        return orderMapper.toResponse(saved);
        // Convert entity → response DTO (hide internal fields)
    }

    /**
     * Get order by ID (for display/status check).
     */
    public OrderResponse getOrder(String orderId) {
        Order order = getOrderEntity(orderId);
        return orderMapper.toResponse(order);
    }

    /**
     * Get the raw entity (used internally by PaymentService).
     * Not exposed via API — only service-to-service.
     */
    public Order getOrderEntity(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        // If order doesn't exist → 404 Not Found
    }

    /**
     * Update order status (called after payment state changes).
     */
    @Transactional
    public void updateOrderStatus(String orderId, OrderStatus newStatus) {
        Order order = getOrderEntity(orderId);
        order.setStatus(newStatus);
        orderRepository.save(order);
        // UPDATE orders SET status = 'PAID' WHERE id = 'order_abc123'
    }

    /**
     * List orders for a specific merchant (dashboard pagination).
     */
    public List<OrderResponse> getOrdersByMerchant(String merchantId) {
        return orderRepository.findByMerchantId(merchantId).stream()
                .map(orderMapper::toResponse)
                // .map(order -> orderMapper.toResponse(order)) — same thing
                // Converts each entity to a DTO
                .toList();
    }
}
```

---

<a name="idempotency-service"></a>
## Step 3: IdempotencyService — Preventing Duplicate Payments

**This is one of the most critical pieces in the entire system.**

**File:** `backend/payment-service/src/main/java/com/payflow/payment/service/IdempotencyService.java`

```java
package com.payflow.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
// Jackson — converts Java objects to/from JSON strings
// We serialize responses to JSON for caching in Redis

import com.payflow.common.exception.IdempotencyConflictException;
// Thrown when same idempotency key is already being processed

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
// StringRedisTemplate — Spring's Redis client for String key-value operations
// WHY String? Redis values are strings. We store JSON strings.

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
// @Slf4j → Lombok generates: private static final Logger log = LoggerFactory.getLogger(...)
// Use: log.info("message"), log.error("message", exception)
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;
    // Injected by Spring from RedisConfig

    private final ObjectMapper objectMapper;
    // Injected by Spring (auto-configured by Jackson starter)

    private static final String KEY_PREFIX = "idempotency:";
    // All idempotency keys stored with this prefix in Redis
    // Example key: "idempotency:abc-123-xyz"

    private static final String PROCESSING = "PROCESSING";
    // Sentinel value: means "this request is currently being executed"

    private static final Duration TTL = Duration.ofHours(24);
    // Keys expire after 24 hours
    // After 24h, same idempotency key can be reused (fresh request)

    /**
     * Check if we already have a cached response for this idempotency key.
     *
     * Returns:
     * - Empty Optional → new key, proceed with execution
     * - Optional with JSON → already completed, return cached response
     * - Throws IdempotencyConflictException → currently being processed by another thread
     */
    public Optional<String> getCachedResponse(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        String value = redisTemplate.opsForValue().get(key);
        // Redis GET command: O(1), ~0.1ms

        if (value == null) {
            // Key doesn't exist → this is a new request
            return Optional.empty();
        }

        if (PROCESSING.equals(value)) {
            // Another thread/instance is currently processing this request
            // DON'T process it again — tell caller to wait or retry
            throw new IdempotencyConflictException(idempotencyKey);
        }

        // Key exists with a JSON value → this request was already completed
        // Return the cached response (same result as first time)
        return Optional.of(value);
    }

    /**
     * Acquire a "lock" on this idempotency key.
     * Uses Redis SET NX (Set if Not Exists) — atomic operation.
     *
     * SET NX means: only set if the key does NOT already exist.
     * This prevents race conditions where two threads try to process the same key.
     *
     * Returns true if lock acquired (you can proceed).
     * Returns false if key already exists (someone else got there first).
     */
    public boolean acquireLock(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, PROCESSING, TTL);
        // Redis command: SET idempotency:abc-123 "PROCESSING" NX EX 86400
        //   NX = only set if Not eXists (atomic!)
        //   EX 86400 = expire after 86400 seconds (24 hours)
        // Returns true if set succeeded, false if key already existed

        return Boolean.TRUE.equals(acquired);
    }

    /**
     * After successful execution, cache the response.
     * Future duplicate requests will get this cached response.
     */
    public void cacheResponse(String idempotencyKey, Object response) {
        try {
            String key = KEY_PREFIX + idempotencyKey;
            String jsonResponse = objectMapper.writeValueAsString(response);
            // Convert response object → JSON string
            // e.g., PaymentResponse → {"id":"pay_001","status":"AUTHORIZED",...}

            redisTemplate.opsForValue().set(key, jsonResponse, TTL);
            // Overwrite "PROCESSING" with actual JSON response
            // Now duplicate requests get this JSON back instantly

            log.debug("Cached idempotency response for key: {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Failed to cache idempotency response for key: {}", idempotencyKey, e);
            // Non-fatal: if caching fails, next duplicate request will re-process
            // (still idempotent because DB has the record)
        }
    }

    /**
     * Release the lock (called on FAILURE — so the key can be retried).
     * If processing fails, we delete the "PROCESSING" marker
     * so the merchant can retry with the same key.
     */
    public void releaseLock(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        redisTemplate.delete(key);
        // Remove the "PROCESSING" sentinel
        // Merchant can now retry with the same idempotency key
    }

    /**
     * Deserialize a cached JSON response back to a Java object.
     */
    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached response", e);
        }
    }
}
```

**How the full idempotency flow works in the controller:**

```java
// In PaymentController (simplified):
public PaymentResponse authorize(String idempotencyKey, AuthorizeRequest request) {

    // Step 1: Check cache
    Optional<String> cached = idempotencyService.getCachedResponse(idempotencyKey);
    if (cached.isPresent()) {
        return idempotencyService.deserialize(cached.get(), PaymentResponse.class);
        // Return same response as first time (no re-processing!)
    }

    // Step 2: Acquire lock
    if (!idempotencyService.acquireLock(idempotencyKey)) {
        throw new IdempotencyConflictException(idempotencyKey);
        // Another thread is processing this right now
    }

    try {
        // Step 3: Execute business logic
        PaymentResponse response = paymentService.authorize(request);

        // Step 4: Cache the response
        idempotencyService.cacheResponse(idempotencyKey, response);

        return response;
    } catch (Exception e) {
        // Step 5: On failure, release lock (allow retry)
        idempotencyService.releaseLock(idempotencyKey);
        throw e;
    }
}
```

---

<a name="event-publisher"></a>
## Step 4: EventPublisher Interface

**Why an interface?** We want to swap implementations without changing business code:
- **Local/Production:** KafkaEventPublisher (Kafka)
- **AWS Free Tier:** SqsEventPublisher (SQS — cheaper)

**File:** `backend/payment-service/src/main/java/com/payflow/payment/service/EventPublisher.java`

```java
package com.payflow.payment.service;

import com.payflow.common.event.PaymentEvent;
// The event DTO from common-lib

/**
 * Abstraction for event publishing.
 * Business code calls this interface — doesn't know if Kafka or SQS is underneath.
 *
 * This is the STRATEGY PATTERN:
 * - Interface defines WHAT to do (publish an event)
 * - Implementations define HOW (Kafka vs SQS)
 * - Spring injects the active implementation based on profile
 */
public interface EventPublisher {

    /**
     * Publish a payment event to the messaging system.
     *
     * @param topic The topic/queue name (e.g., "payment.authorized")
     * @param event The event payload
     */
    void publishPaymentEvent(String topic, PaymentEvent event);
}
```

---

<a name="kafka-publisher"></a>
## Step 5: KafkaEventPublisher

**File:** `backend/payment-service/src/main/java/com/payflow/payment/kafka/KafkaEventPublisher.java`

```java
package com.payflow.payment.kafka;

import com.payflow.common.event.PaymentEvent;
import com.payflow.payment.service.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
// @Profile — only create this bean when a specific profile is active

import org.springframework.kafka.core.KafkaTemplate;
// KafkaTemplate — Spring's Kafka producer client
// Like RestTemplate for HTTP, but for Kafka messages

import org.springframework.stereotype.Component;

@Component
// @Component = general Spring bean (could also use @Service, same effect)

@Profile("!aws")
// This bean is created when the "aws" profile is NOT active
// In other words: use Kafka by default (local + production)
// Only switch to SQS when explicitly running with --spring.profiles.active=aws

@RequiredArgsConstructor
@Slf4j
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    // KafkaTemplate<Key, Value>
    // Key = String (we use merchantId as key for ordering)
    // Value = PaymentEvent (serialized to JSON by JsonSerializer)

    @Override
    public void publishPaymentEvent(String topic, PaymentEvent event) {
        // Send to Kafka asynchronously
        kafkaTemplate.send(topic, event.getMerchantId(), event)
                // .send(topic, key, value)
                // key = merchantId → ensures all events for same merchant
                //   go to the SAME partition → processed in order
                .whenComplete((result, exception) -> {
                    // whenComplete: callback when send completes (async)
                    if (exception != null) {
                        log.error("Failed to publish event {} to topic {}: {}",
                                event.getEventId(), topic, exception.getMessage());
                        // TODO: In production, save to outbox table for retry
                    } else {
                        log.info("Published event {} to topic {} [partition={}, offset={}]",
                                event.getEventId(), topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
```

**Why async send?** Publishing to Kafka is fast (~5ms) but we don't want to block the payment response waiting for Kafka acknowledgment. The payment is saved in our DB (source of truth). Kafka delivery can happen in the background.

---

<a name="payment-service"></a>
## Step 6: PaymentService — The Core Business Logic

**File:** `backend/payment-service/src/main/java/com/payflow/payment/service/PaymentService.java`

```java
package com.payflow.payment.service;

import com.payflow.common.constant.OrderStatus;
import com.payflow.common.constant.PaymentMethod;
import com.payflow.common.constant.PaymentStatus;
import com.payflow.common.event.PaymentEvent;
import com.payflow.common.exception.PayflowException;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.common.util.IdGenerator;
import com.payflow.payment.dto.*;
import com.payflow.payment.feign.RoutingServiceClient;
import com.payflow.payment.mapper.PaymentMapper;
import com.payflow.payment.model.Order;
import com.payflow.payment.model.Payment;
import com.payflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderService orderService;
    private final RoutingServiceClient routingServiceClient;
    // Feign client → calls routing-service HTTP endpoint
    private final EventPublisher eventPublisher;
    // Could be Kafka or SQS (depends on active profile)
    private final PaymentMapper paymentMapper;

    /**
     * AUTHORIZE a payment — the critical path.
     *
     * Flow:
     * 1. Validate order exists and is in CREATED state
     * 2. Create payment record (status = CREATED)
     * 3. Call routing-service (which calls bank via ISO 8583)
     * 4. Update payment status based on bank response
     * 5. Publish event to Kafka
     * 6. Return response
     */
    @Transactional
    public PaymentResponse authorize(AuthorizePaymentRequest request) {
        log.info("Authorizing payment for order: {}", request.getOrderId());

        // 1. Validate order
        Order order = orderService.getOrderEntity(request.getOrderId());

        if (order.getStatus() != OrderStatus.CREATED) {
            throw new PayflowException("INVALID_STATE",
                    "Order is not in CREATED state. Current: " + order.getStatus());
        }

        // Check if payment already exists for this order
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(order.getId());
        if (existingPayment.isPresent()) {
            throw new PayflowException("DUPLICATE_PAYMENT",
                    "Payment already exists for order: " + order.getId());
        }

        // 2. Create payment record
        Payment payment = Payment.builder()
                .id(IdGenerator.generatePaymentId())
                .orderId(order.getId())
                .merchantId(order.getMerchantId())
                .amount(order.getAmount())
                .currency(order.getCurrency())
                .status(PaymentStatus.CREATED)
                .paymentMethod(PaymentMethod.valueOf(request.getPaymentMethod()))
                .build();

        payment = paymentRepository.save(payment);

        // Update order status to ATTEMPTED
        orderService.updateOrderStatus(order.getId(), OrderStatus.ATTEMPTED);

        // 3. Call routing-service (bank communication)
        try {
            Map<String, Object> routingResponse = routingServiceClient.routePayment(Map.of(
                    "merchantId", order.getMerchantId(),
                    "amount", order.getAmount().toString(),
                    "currency", order.getCurrency(),
                    "paymentMethod", request.getPaymentMethod(),
                    "cardBin", request.getCardNumber() != null ?
                            request.getCardNumber().substring(0, 6) : "",
                    "paymentId", payment.getId()
            ));

            // 4. Update payment based on bank response
            String bankStatus = (String) routingResponse.get("status");

            if ("AUTHORIZED".equals(bankStatus)) {
                payment.setStatus(PaymentStatus.AUTHORIZED);
                payment.setAuthorizationCode((String) routingResponse.get("authorizationCode"));
                payment.setBankReferenceId((String) routingResponse.get("bankReferenceId"));

                // Publish success event
                publishEvent("payment.authorized", payment);
            } else {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason((String) routingResponse.getOrDefault(
                        "responseMessage", "Payment declined by bank"));

                // Publish failure event
                publishEvent("payment.failed", payment);
            }

        } catch (Exception e) {
            // Bank communication failed (timeout, circuit breaker open, etc.)
            log.error("Bank communication failed for payment {}: {}", payment.getId(), e.getMessage());
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Bank communication error: " + e.getMessage());
            publishEvent("payment.failed", payment);
        }

        // Save final state
        payment = paymentRepository.save(payment);

        return paymentMapper.toResponse(payment);
    }

    /**
     * CAPTURE — collect the authorized money.
     * Only valid transition: AUTHORIZED → CAPTURED
     */
    @Transactional
    public PaymentResponse capture(CapturePaymentRequest request) {
        Payment payment = getPaymentEntity(request.getPaymentId());

        // Validate state transition
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PayflowException("INVALID_STATE",
                    "Cannot capture payment in " + payment.getStatus() + " state. " +
                    "Only AUTHORIZED payments can be captured.");
        }

        // Update state
        payment.setStatus(PaymentStatus.CAPTURED);
        payment = paymentRepository.save(payment);

        // Update order to PAID
        orderService.updateOrderStatus(payment.getOrderId(), OrderStatus.PAID);

        // Publish event (webhook-service and settlement-service will consume this)
        publishEvent("payment.captured", payment);

        log.info("Payment captured: {} for order: {}", payment.getId(), payment.getOrderId());
        return paymentMapper.toResponse(payment);
    }

    /**
     * VOID — cancel an authorization before capture.
     * Only valid transition: AUTHORIZED → VOIDED
     */
    @Transactional
    public PaymentResponse voidPayment(String paymentId) {
        Payment payment = getPaymentEntity(paymentId);

        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PayflowException("INVALID_STATE",
                    "Cannot void payment in " + payment.getStatus() + " state.");
        }

        payment.setStatus(PaymentStatus.VOIDED);
        payment = paymentRepository.save(payment);

        log.info("Payment voided: {}", paymentId);
        return paymentMapper.toResponse(payment);
    }

    /**
     * Get payment details by ID.
     */
    public PaymentResponse getPayment(String paymentId) {
        return paymentMapper.toResponse(getPaymentEntity(paymentId));
    }

    private Payment getPaymentEntity(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }

    /**
     * Build and publish a PaymentEvent to Kafka.
     */
    private void publishEvent(String topic, Payment payment) {
        PaymentEvent event = PaymentEvent.builder()
                .eventId(IdGenerator.generateEventId())
                .eventType(topic)
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .merchantId(payment.getMerchantId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .paymentMethod(payment.getPaymentMethod().name())
                .timestamp(Instant.now())
                .build();

        eventPublisher.publishPaymentEvent(topic, event);
    }
}
```

---

<a name="refund-service"></a>
## Step 7: RefundService

**File:** `backend/payment-service/src/main/java/com/payflow/payment/service/RefundService.java`

```java
package com.payflow.payment.service;

import com.payflow.common.constant.PaymentStatus;
import com.payflow.common.exception.PayflowException;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.common.util.IdGenerator;
import com.payflow.payment.dto.RefundRequest;
import com.payflow.payment.dto.RefundResponse;
import com.payflow.payment.model.Payment;
import com.payflow.payment.model.Refund;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final EventPublisher eventPublisher;

    /**
     * Create a refund (full or partial).
     * Only CAPTURED payments can be refunded.
     * Refund amount must not exceed original payment amount minus previous refunds.
     */
    @Transactional
    public RefundResponse createRefund(RefundRequest request) {
        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", request.getPaymentId()));

        // Only captured payments can be refunded
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new PayflowException("INVALID_STATE",
                    "Cannot refund payment in " + payment.getStatus() + " state. " +
                    "Only CAPTURED payments can be refunded.");
        }

        // Calculate total already refunded
        BigDecimal totalRefunded = refundRepository.findByPaymentId(payment.getId())
                .stream()
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remainingRefundable = payment.getAmount().subtract(totalRefunded);

        if (request.getAmount().compareTo(remainingRefundable) > 0) {
            throw new PayflowException("REFUND_EXCEEDS_AMOUNT",
                    String.format("Refund amount (%.2f) exceeds refundable amount (%.2f)",
                            request.getAmount(), remainingRefundable));
        }

        // Create refund record
        Refund refund = Refund.builder()
                .id(IdGenerator.generateRefundId())
                .paymentId(payment.getId())
                .merchantId(payment.getMerchantId())
                .amount(request.getAmount())
                .reason(request.getReason())
                .status("PROCESSED")
                .build();

        refund = refundRepository.save(refund);

        // If full refund, mark payment as REFUNDED
        BigDecimal newTotalRefunded = totalRefunded.add(request.getAmount());
        if (newTotalRefunded.compareTo(payment.getAmount()) >= 0) {
            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);
        }

        log.info("Refund created: {} for payment: {}, amount: {}",
                refund.getId(), payment.getId(), refund.getAmount());

        return RefundResponse.builder()
                .id(refund.getId())
                .paymentId(refund.getPaymentId())
                .amount(refund.getAmount())
                .reason(refund.getReason())
                .status(refund.getStatus())
                .createdAt(refund.getCreatedAt())
                .build();
    }

    public List<RefundResponse> getRefundsByPayment(String paymentId) {
        return refundRepository.findByPaymentId(paymentId).stream()
                .map(r -> RefundResponse.builder()
                        .id(r.getId())
                        .paymentId(r.getPaymentId())
                        .amount(r.getAmount())
                        .reason(r.getReason())
                        .status(r.getStatus())
                        .createdAt(r.getCreatedAt())
                        .build())
                .toList();
    }
}
```

---

<a name="config"></a>
## Step 8: Configuration Classes

### RedisConfig

**File:** `backend/payment-service/src/main/java/com/payflow/payment/config/RedisConfig.java`

```java
package com.payflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        // StringRedisTemplate: convenience class for String key-value operations
        // Connection details (host, port) come from application.yml:
        //   spring.data.redis.host=localhost
        //   spring.data.redis.port=6379
        return new StringRedisTemplate(connectionFactory);
    }
}
```

### KafkaProducerConfig

**File:** `backend/payment-service/src/main/java/com/payflow/payment/config/KafkaProducerConfig.java`

```java
package com.payflow.payment.config;

import com.payflow.common.event.PaymentEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, PaymentEvent> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // JsonSerializer: converts PaymentEvent → JSON bytes automatically
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Kafka producer idempotence: prevents duplicate messages on retry
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // "all" = wait for ALL replicas to acknowledge (strongest durability)
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, PaymentEvent> kafkaTemplate(
            ProducerFactory<String, PaymentEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
```

---

<a name="verification"></a>
## Verification

### Prerequisites
```powershell
# Ensure infrastructure is running
cd infra/docker
docker compose up postgres redis kafka -d

# Verify
docker compose ps
# All three should show "healthy" or "running"
```

### Build
```powershell
cd backend
mvn clean compile -pl payment-service -am
# Expected: BUILD SUCCESS
```

### Run (after identity-service and merchant-service are ready)
```powershell
mvn spring-boot:run -pl payment-service
# Expected: Started PaymentServiceApplication in X seconds
# Check: http://localhost:8083/actuator/health → {"status":"UP"}
```

---

## What You Learned

| # | Concept | What You Practiced |
|---|---------|-------------------|
| 1 | State Machine | Defined valid transitions, reject invalid ones |
| 2 | Idempotency | Redis SET NX for distributed lock, cache responses |
| 3 | Strategy Pattern | EventPublisher interface with Kafka/SQS implementations |
| 4 | Kafka Producer | KafkaTemplate with JSON serializer, async send |
| 5 | @Transactional | Atomic database operations (rollback on failure) |
| 6 | Feign Client | HTTP call to another microservice (routing-service) |
| 7 | Refund validation | Prevent over-refunding with sum calculation |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `Connection refused: localhost:6379` | Redis not running | `docker compose up redis -d` |
| `Connection refused: localhost:9092` | Kafka not running | `docker compose up kafka -d` |
| `No qualifying bean: EventPublisher` | Profile mismatch | Ensure NOT running with `--spring.profiles.active=aws` |
| `SerializationException: PaymentEvent` | Jackson can't serialize | Add `@JsonInclude(NON_NULL)` or check field types |
| `INVALID_STATE: Cannot capture` | Payment not AUTHORIZED | Check payment status before calling capture |
| `IDEMPOTENCY_CONFLICT` | Same key already processing | Wait and retry, or use different key |

---

## Git Commit

```bash
git add .
git status
git commit -m "Phase 4 Part 8b: Add payment services, idempotency, and Kafka events

- Created OrderService (create, get, update status)
- Created IdempotencyService (Redis SET NX, cache responses)
- Created EventPublisher interface (Strategy pattern)
- Created KafkaEventPublisher (async send with merchant_id key)
- Created PaymentService (authorize → capture → void state machine)
- Created RefundService (full/partial with amount validation)
- Added RedisConfig and KafkaProducerConfig
- Verified: builds with mvn compile"

git push origin main
```

---

## Document Index

| Phase | Part | Document | Status |
|-------|------|----------|--------|
| 4 | 8a | [Payment Entities](./phase4-part08a-payment-entities.md) | ✅ |
| 4 | **8b** | **[Payment Services & Kafka](./phase4-part08b-payment-services-kafka.md)** | ← You are here |
| 4 | 8c | [Payment Controllers](./phase4-part08c-payment-controllers.md) | ⬜ Next |

---
*End of Phase 4 Part 8b — Payment Services, Kafka & Idempotency*
*Next: [Phase 4 Part 8c — Payment Controllers & Feign Clients](./phase4-part08c-payment-controllers.md)*
