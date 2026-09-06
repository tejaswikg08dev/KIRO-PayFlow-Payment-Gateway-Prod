# 🏗️ Phase 4 Part 8h: Payment Service — IdempotencyService + EventPublisher + Tests

> **"'SET NX EX' — three letters that prevent double-charging a customer."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8h — Idempotency + Event Publishing |
| **What You Build** | IdempotencyService.java, EventPublisher.java, KafkaEventPublisher.java, SqsEventPublisher.java, IdempotencyServiceTest.java |
| **Previous** | [Part 8g — OrderService + RefundService](./phase4-part08g-payment-order-refund-services.md) |
| **Next** | [Part 8i — PaymentService Core Engine](./phase4-part08i-payment-engine.md) |

---

## 📖 Table of Contents

1. [What This Part Covers — Two Critical Patterns](#1-what-this-part-covers--two-critical-patterns)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: EventPublisher.java (Interface)](#3-step-by-step-eventpublisherjava-interface)
4. [Step-by-Step: KafkaEventPublisher.java](#4-step-by-step-kafkaeventpublisherjava)
5. [Step-by-Step: SqsEventPublisher.java (Stub)](#5-step-by-step-sqseventpublisherjava-stub)
6. [Step-by-Step: IdempotencyService.java](#6-step-by-step-idempotencyservicejava)
7. [Step-by-Step: IdempotencyServiceTest.java](#7-step-by-step-idempotencyservicetestjava)
8. [What You Learned](#8-what-you-learned)

---

## 1. What This Part Covers — Two Critical Patterns

### Pattern 1: Event Publishing (Kafka)

```
WHAT: When a payment is authorized/captured/refunded, publish an event
WHY:  Webhook Service, Settlement Service, Notification Service need to know
HOW:  EventPublisher interface → KafkaEventPublisher implementation → Kafka broker

Interface allows swapping Kafka for SQS by changing Spring profile.
```

### Pattern 2: Idempotency (Redis)

```
WHAT: Prevent duplicate charges when customer clicks "Pay" twice
WHY:  Network timeout → retry → second charge without protection
HOW:  Redis SET NX EX (atomic lock with TTL) → check/lock/process/cache

Customer clicks "Pay" → idempotency key sent
First request:  key not in Redis → lock → process → cache result
Second request: key found in Redis → return cached result (NO second charge!)
```

---

## 2. Folder Structure After This Part

```
backend/payment-service/src/
├── main/java/com/payflow/payment/
│   ├── ... (from 8a-8g)
│   ├── service/
│   │   ├── OrderService.java          ← from 8g
│   │   ├── RefundService.java         ← from 8g
│   │   ├── EventPublisher.java        ← YOU CREATE THIS (interface)
│   │   └── IdempotencyService.java    ← YOU CREATE THIS
│   ├── kafka/                         ← YOU CREATE THIS FOLDER
│   │   └── KafkaEventPublisher.java   ← YOU CREATE THIS
│   └── sqs/                           ← YOU CREATE THIS FOLDER
│       └── SqsEventPublisher.java     ← YOU CREATE THIS
└── test/java/com/payflow/payment/
    └── service/
        ├── OrderServiceTest.java      ← from 8g
        └── IdempotencyServiceTest.java ← YOU CREATE THIS
```

---

## 3. Step-by-Step: EventPublisher.java (Interface)

**File:** `src/main/java/com/payflow/payment/service/EventPublisher.java`

```java
package com.payflow.payment.service;

import com.payflow.common.event.PaymentEvent;

/**
 * Interface for publishing payment lifecycle events.
 * Implementations can use Kafka, SQS, or any messaging system.
 */
public interface EventPublisher {

    /**
     * Publishes a payment event to the appropriate topic/queue.
     *
     * @param topic the topic name (e.g., payment.authorized, payment.captured)
     * @param event the payment event payload
     */
    void publishPaymentEvent(String topic, PaymentEvent event);
}
```

**WHY AN INTERFACE (NOT A CLASS)?**

```
WITHOUT interface:
  RefundService → KafkaEventPublisher (directly)
  If you switch to SQS → change every service that publishes events

WITH interface:
  RefundService → EventPublisher (interface)
                    ├── KafkaEventPublisher (@Profile("!aws"))  ← default
                    └── SqsEventPublisher (@Profile("aws"))     ← AWS
  Switch to SQS → change NOTHING. Just set SPRING_PROFILES_ACTIVE=aws
```

This is the **Strategy Pattern** — one interface, multiple implementations, runtime selection via `@Profile`.

**WHERE THIS INTERFACE IS USED:**
```java
// In RefundService (from Part 8g):
private final EventPublisher eventPublisher;  // ← Interface type, not KafkaEventPublisher!
eventPublisher.publishPaymentEvent("payment.refunded", event);

// In PaymentService (Part 8i):
private final EventPublisher eventPublisher;
eventPublisher.publishPaymentEvent("payment.authorized", event);
```

Spring injects the correct implementation based on the active profile. The services don't know (or care) whether Kafka or SQS is used.

---

## 4. Step-by-Step: KafkaEventPublisher.java

**File:** `src/main/java/com/payflow/payment/kafka/KafkaEventPublisher.java`

```java
package com.payflow.payment.kafka;

import com.payflow.common.event.PaymentEvent;
import com.payflow.payment.service.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
```

**KEY IMPORTS:**

| Import | What |
|---|---|
| `EventPublisher` | The interface this class implements |
| `KafkaTemplate` | The Kafka client (configured in KafkaProducerConfig, Part 8f) |
| `SendResult` | The result of a Kafka send operation (contains partition + offset) |
| `CompletableFuture` | Java's async result — the send is non-blocking |

```java
/**
 * Kafka-based implementation of EventPublisher.
 * Active by default (non-AWS environments).
 */
@Component
@Profile("!aws")
@RequiredArgsConstructor
@Slf4j
public class KafkaEventPublisher implements EventPublisher {
```

**`@Component`** (not `@Service`) — Convention: `@Service` for business logic, `@Component` for infrastructure/technical components. Both register as Spring beans.

**`@Profile("!aws")`** — Only active when NOT on AWS. In local dev, Docker, staging → this runs. On AWS → SqsEventPublisher runs instead.

**`implements EventPublisher`** — This class fulfills the EventPublisher contract.

```java
    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
```

Injected from `KafkaProducerConfig` (Part 8f). This is the typed Kafka client configured with `acks=all`, `idempotence=true`, `retries=3`.

```java
    @Override
    public void publishPaymentEvent(String topic, PaymentEvent event) {
        log.info("Publishing event to topic [{}]: eventId={}, paymentId={}", 
                topic, event.getEventId(), event.getPaymentId());
```

Logs WHAT is being published and WHERE.

```java
        CompletableFuture<SendResult<String, PaymentEvent>> future = 
                kafkaTemplate.send(topic, event.getPaymentId(), event);
```

**🆕 `kafkaTemplate.send(topic, key, value)` — THREE PARAMETERS:**

| Parameter | Value | Purpose |
|---|---|---|
| `topic` | `"payment.authorized"` | Which Kafka topic to publish to |
| `key` | `event.getPaymentId()` | Message key — determines which partition |
| `value` | `event` (PaymentEvent) | Message payload — serialized to JSON by `JsonSerializer` |

**WHY `event.getPaymentId()` AS KEY?**
Kafka guarantees ordering WITHIN a partition. Messages with the same key go to the same partition:
```
Key "pay_abc" → Partition 3
  Message 1: payment.authorized  (processed first)
  Message 2: payment.captured    (processed second)

Without a key → messages scattered across partitions → no ordering guarantee
→ consumer might process "captured" before "authorized" → wrong!
```

**🆕 `CompletableFuture` — ASYNC SEND:**

`kafkaTemplate.send()` returns immediately (non-blocking). The actual network send happens in a background thread. The `CompletableFuture` lets us handle the result when it completes:

```java
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event to topic [{}]: eventId={}", 
                        topic, event.getEventId(), ex);
            } else {
                log.debug("Event published to topic [{}] at partition={}, offset={}", 
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
```

**`whenComplete` — CALLBACK AFTER SEND:**

| Scenario | `result` | `ex` | What Happens |
|---|---|---|---|
| Success | SendResult (partition, offset) | null | Log success with partition+offset |
| Failure | null | Exception | Log error (Kafka unreachable, serialization failed, etc.) |

**NO RE-THROW ON FAILURE.** If Kafka is down:
- The error is LOGGED
- The method returns normally
- The calling service (PaymentService/RefundService) is NOT affected
- The payment/refund has already been saved to PostgreSQL

This is the **fire-and-forget** pattern. Events are best-effort — payment correctness depends on PostgreSQL, not Kafka.

**`partition` + `offset`** — Kafka storage coordinates:
```
Topic: payment.authorized
  Partition 0: [msg1, msg2, msg3]        ← offset 0, 1, 2
  Partition 1: [msg4, msg5]              ← offset 0, 1
  Partition 2: [msg6, msg7, msg8, msg9]  ← offset 0, 1, 2, 3

"Event published at partition=2, offset=3" → message is msg9
```

---

## 5. Step-by-Step: SqsEventPublisher.java (Stub)

**File:** `src/main/java/com/payflow/payment/sqs/SqsEventPublisher.java`

```java
package com.payflow.payment.sqs;

import com.payflow.common.event.PaymentEvent;
import com.payflow.payment.service.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * AWS SQS-based implementation of EventPublisher.
 * Active only when running with the 'aws' profile.
 * Stub implementation — to be completed with AWS SDK SQS integration.
 */
@Component
@Profile("aws")
@Slf4j
public class SqsEventPublisher implements EventPublisher {

    @Override
    public void publishPaymentEvent(String topic, PaymentEvent event) {
        log.info("SQS: Publishing event to queue [{}]: eventId={}, paymentId={}", 
                topic, event.getEventId(), event.getPaymentId());

        // TODO: Implement AWS SQS publishing
        // SqsClient sqsClient = ...
        // SendMessageRequest sendRequest = SendMessageRequest.builder()
        //         .queueUrl(resolveQueueUrl(topic))
        //         .messageBody(objectMapper.writeValueAsString(event))
        //         .messageGroupId(event.getMerchantId())
        //         .messageDeduplicationId(event.getEventId())
        //         .build();
        // sqsClient.sendMessage(sendRequest);

        log.warn("SQS EventPublisher is a stub — event not actually sent");
    }
}
```

**THIS IS A STUB** — it logs but doesn't actually send to SQS. The TODO comments show the intended implementation.

**WHY INCLUDE A STUB?**
1. The interface pattern works today (code compiles, profiles work)
2. When deploying to AWS, you implement the TODO and it works
3. No code changes in PaymentService/RefundService — just set `SPRING_PROFILES_ACTIVE=aws`

**`@Profile("aws")`** — only loads when running on AWS. Never conflicts with KafkaEventPublisher (`@Profile("!aws")`).

**HOW SPRING RESOLVES THE RIGHT BEAN:**
```
Profile: default (local dev)
  KafkaEventPublisher @Profile("!aws") → LOADS ✓
  SqsEventPublisher @Profile("aws")   → SKIPPED ✗
  Spring injects: KafkaEventPublisher

Profile: aws (production on AWS)
  KafkaEventPublisher @Profile("!aws") → SKIPPED ✗
  SqsEventPublisher @Profile("aws")   → LOADS ✓
  Spring injects: SqsEventPublisher
```

---

## 6. Step-by-Step: IdempotencyService.java

**File:** `src/main/java/com/payflow/payment/service/IdempotencyService.java`

This is the most conceptually new service in the entire project.

### Class Declaration

```java
package com.payflow.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.exception.IdempotencyConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
```

**KEY IMPORTS:**

| Import | What |
|---|---|
| `ObjectMapper` | Jackson's JSON serializer/deserializer |
| `IdempotencyConflictException` | From common-lib — thrown when request is currently being processed |
| `StringRedisTemplate` | Redis client (configured in RedisConfig, Part 8f) |
| `Duration` | Java time — for 24-hour TTL |

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idempotency:";
    private static final String LOCK_VALUE = "PROCESSING";
```

**THREE CONSTANTS:**

| Constant | Value | Purpose |
|---|---|---|
| `DEFAULT_TTL` | 24 hours | How long cached responses live in Redis |
| `KEY_PREFIX` | `"idempotency:"` | All keys start with this (namespacing in Redis) |
| `LOCK_VALUE` | `"PROCESSING"` | Sentinel value meaning "request is being processed right now" |

```java
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
```

| Dependency | Purpose |
|---|---|
| `StringRedisTemplate` | Talk to Redis (GET, SET, DELETE) |
| `ObjectMapper` | Convert Java objects → JSON strings (for Redis storage) and back |

---

### Method: getCachedResponse

```java
    public Optional<String> getCachedResponse(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        String value = redisTemplate.opsForValue().get(key);
```

**`opsForValue().get(key)`** — Redis GET command:
```
GET idempotency:unique-uuid-123
→ null (key doesn't exist)
→ "PROCESSING" (request is in progress)
→ '{"paymentId":"pay_abc","status":"AUTHORIZED"}' (cached response)
```

```java
        if (value == null) {
            return Optional.empty();
        }
```

**Key not found** → this idempotency key has never been used. Return empty = "go ahead and process."

```java
        if (LOCK_VALUE.equals(value)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
```

**🆕 PROCESSING CONFLICT:**

```
Scenario:
  Request A: POST /v1/payments/authorize (Idempotency-Key: abc-123)
    → getCachedResponse("abc-123") → null → acquireLock → set "PROCESSING"
    → Processing payment... (takes 2 seconds)

  Request B (concurrent): POST /v1/payments/authorize (Idempotency-Key: abc-123)
    → getCachedResponse("abc-123") → "PROCESSING" ← CONFLICT!
    → throw IdempotencyConflictException → 409 Conflict response

  Request A completes:
    → cacheResponse("abc-123", response) → overwrites "PROCESSING" with JSON

  Request C (later): POST /v1/payments/authorize (Idempotency-Key: abc-123)
    → getCachedResponse("abc-123") → '{"paymentId":"pay_abc",...}' ← CACHED!
    → return cached response → no second charge
```

```java
        log.debug("Idempotency cache hit for key: {}", idempotencyKey);
        return Optional.of(value);
    }
```

**Cached response found** → return the JSON string. Controller will deserialize and return to client.

---

### Method: acquireLock

```java
    public boolean acquireLock(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, LOCK_VALUE, DEFAULT_TTL);
```

**🆕 `setIfAbsent(key, value, ttl)` — THE CORE REDIS OPERATION**

This translates to the Redis command:
```
SET idempotency:abc-123 "PROCESSING" NX EX 86400
```

| Flag | Meaning |
|---|---|
| `NX` | **Only set if Not eXists** — if the key already exists, do NOTHING |
| `EX 86400` | **Expire after 86400 seconds** (24 hours) |

**WHY NX IS CRITICAL:**
```
WITHOUT NX:
  Request A: SET key "PROCESSING"         → OK
  Request B: SET key "PROCESSING"         → OK (overwrites A's lock!)
  Both requests process → DOUBLE CHARGE!

WITH NX:
  Request A: SET key "PROCESSING" NX      → OK (key created)
  Request B: SET key "PROCESSING" NX      → FAIL (key already exists)
  Only request A processes → SINGLE CHARGE ✓
```

**WHY EX (TTL)?**
If the application crashes AFTER acquiring the lock but BEFORE caching the response, the lock stays forever → key is "stuck." The 24-hour TTL ensures stuck locks auto-expire.

**WHY `setIfAbsent` NOT `set`?**
`setIfAbsent` = Redis `SET NX` = atomic "set only if not exists." `set` = always overwrites.

```java
        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Idempotency lock acquired for key: {}", idempotencyKey);
            return true;
        }

        return false;
    }
```

**`Boolean.TRUE.equals(acquired)`** — null-safe check. Redis might return `null` instead of `false` in edge cases. `Boolean.TRUE.equals(null)` → `false` (safe).

---

### Method: cacheResponse

```java
    public <T> void cacheResponse(String idempotencyKey, T response) {
        String key = KEY_PREFIX + idempotencyKey;
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, json, DEFAULT_TTL);
            log.debug("Idempotency response cached for key: {}", idempotencyKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency key: {}", idempotencyKey, e);
            redisTemplate.delete(key);
        }
    }
```

**AFTER SUCCESSFUL PROCESSING:**
```
Redis before: idempotency:abc-123 → "PROCESSING"
              ↓ cacheResponse()
Redis after:  idempotency:abc-123 → '{"paymentId":"pay_abc","status":"AUTHORIZED"}'
              TTL: 24 hours
```

**`<T>` GENERIC TYPE:** Accepts any response object (PaymentResponse, etc.). Jackson serializes it to JSON.

**ERROR HANDLING:** If serialization fails → DELETE the key. This releases the lock so the request can be retried. Without this, a serialization error would leave a permanent "PROCESSING" lock.

---

### Method: releaseLock

```java
    public void releaseLock(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;
        redisTemplate.delete(key);
        log.debug("Idempotency lock released for key: {}", idempotencyKey);
    }
```

**CALLED ON FAILURE:** If the payment processing fails (exception thrown), the controller calls `releaseLock` to remove the "PROCESSING" sentinel. This allows the client to retry with the same idempotency key.

```
Request 1: lock → process → EXCEPTION → releaseLock → key removed
Request 2 (retry): lock → process → SUCCESS → cacheResponse
```

Without `releaseLock`, a failed request would leave a "PROCESSING" lock for 24 hours → client can't retry.

---

### Method: deserialize

```java
    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached response", e);
            throw new RuntimeException("Failed to deserialize cached response", e);
        }
    }
```

Converts cached JSON string back to Java object. Used by the controller when a cache hit occurs:
```java
String cachedJson = idempotencyService.getCachedResponse(key).get();
PaymentResponse response = idempotencyService.deserialize(cachedJson, PaymentResponse.class);
return ResponseEntity.ok(ApiResponse.success(response));
```

---

### The Complete Idempotency Flow

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    IDEMPOTENCY FLOW (in PaymentController)                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  Client sends: POST /v1/payments/authorize                                     │
│                Idempotency-Key: uuid-abc-123                                   │
│                                                                                 │
│  STEP 1: Check cache                                                           │
│  ─────────────────                                                             │
│  getCachedResponse("uuid-abc-123")                                             │
│    → null         → proceed to step 2 (first time)                             │
│    → "PROCESSING" → 409 Conflict (concurrent request)                          │
│    → JSON string  → return cached response (duplicate request)                 │
│                                                                                 │
│  STEP 2: Acquire lock                                                          │
│  ────────────────────                                                          │
│  acquireLock("uuid-abc-123")                                                   │
│    → Redis: SET idempotency:uuid-abc-123 "PROCESSING" NX EX 86400            │
│    → true  → proceed to step 3                                                 │
│    → false → getCachedResponse again (race condition, another req locked it)   │
│                                                                                 │
│  STEP 3: Process payment                                                       │
│  ────────────────────────                                                      │
│  try {                                                                         │
│    PaymentResponse response = paymentService.authorizePayment(request);        │
│                                                                                 │
│    STEP 4: Cache result                                                        │
│    ────────────────────                                                        │
│    cacheResponse("uuid-abc-123", response)                                     │
│    → Redis: SET idempotency:uuid-abc-123 '{"paymentId":"pay_xyz",...}' EX 86400│
│                                                                                 │
│    return response;                                                            │
│  } catch (Exception e) {                                                       │
│                                                                                 │
│    STEP 4 (on failure): Release lock                                           │
│    ────────────────────────────────                                            │
│    releaseLock("uuid-abc-123")                                                 │
│    → Redis: DEL idempotency:uuid-abc-123                                      │
│    → Client can retry with same key                                            │
│                                                                                 │
│    throw e;                                                                    │
│  }                                                                             │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Step-by-Step: IdempotencyServiceTest.java

**File:** `src/test/java/com/payflow/payment/service/IdempotencyServiceTest.java`

### Setup

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Unit Tests")
class IdempotencyServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private IdempotencyService idempotencyService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        idempotencyService = new IdempotencyService(redisTemplate, objectMapper);
    }
```

**🆕 NEW: Mocking `ValueOperations`**

`StringRedisTemplate.opsForValue()` returns a `ValueOperations` object. We need to mock BOTH:
1. `redisTemplate` — to return our mocked `valueOperations`
2. `valueOperations` — to return test values for `get()`, `setIfAbsent()`

```java
when(redisTemplate.opsForValue()).thenReturn(valueOperations);
when(valueOperations.get("idempotency:key")).thenReturn("PROCESSING");
```

**🆕 NEW: Manual construction (not @InjectMocks)**

`IdempotencyService` takes `ObjectMapper` as a constructor parameter. We create a REAL `ObjectMapper` (not mocked) because we need actual JSON serialization in some tests. `@InjectMocks` would try to create a mock ObjectMapper which won't work properly.

### Test 1: New key → empty

```java
    @Test
    @DisplayName("getCachedResponse - should return empty for a new idempotency key")
    void getCachedResponse_NewKey_ReturnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:new-key-123")).thenReturn(null);

        Optional<String> result = idempotencyService.getCachedResponse("new-key-123");

        assertThat(result).isEmpty();
    }
```

Key not found → empty Optional → "process this request."

### Test 2: Existing key → cached JSON

```java
    @Test
    @DisplayName("getCachedResponse - should return cached JSON for existing key")
    void getCachedResponse_ExistingKey_ReturnsCachedResult() {
        String cachedJson = "{\"id\":\"pay-001\",\"status\":\"AUTHORIZED\"}";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:existing-key")).thenReturn(cachedJson);

        Optional<String> result = idempotencyService.getCachedResponse("existing-key");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(cachedJson);
    }
```

Key found with JSON → return it → "this is a duplicate, return cached response."

### Test 3: PROCESSING → conflict

```java
    @Test
    @DisplayName("getCachedResponse - should throw IdempotencyConflictException for PROCESSING key")
    void getCachedResponse_ProcessingKey_ThrowsConflict() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:processing-key")).thenReturn("PROCESSING");

        assertThatThrownBy(() -> idempotencyService.getCachedResponse("processing-key"))
                .isInstanceOf(IdempotencyConflictException.class);
    }
```

Key has "PROCESSING" value → concurrent request is handling this key → 409 Conflict.

### Test 4-5: acquireLock

```java
    @Test
    @DisplayName("acquireLock - should return true when key is successfully locked")
    void acquireLock_Success_ReturnsTrue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:lock-key"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(true);

        boolean acquired = idempotencyService.acquireLock("lock-key");
        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("acquireLock - should return false when key already exists")
    void acquireLock_AlreadyExists_ReturnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:lock-key"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(false);

        boolean acquired = idempotencyService.acquireLock("lock-key");
        assertThat(acquired).isFalse();
    }
```

**`eq()` and `any()` matchers:**
- `eq("idempotency:lock-key")` — must match this exact string
- `eq("PROCESSING")` — must match this exact value
- `any(Duration.class)` — accepts any Duration (we don't care about the exact TTL in this test)

### Test 6: releaseLock

```java
    @Test
    @DisplayName("releaseLock - should delete the key from Redis")
    void releaseLock_DeletesKey() {
        when(redisTemplate.delete("idempotency:release-key")).thenReturn(true);

        idempotencyService.releaseLock("release-key");

        verify(redisTemplate).delete("idempotency:release-key");
    }
```

Verifies that `releaseLock` calls `redisTemplate.delete()` with the correct key.

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Strategy Pattern** | Interface + multiple implementations + @Profile = runtime swapping |
| 2 | **EventPublisher interface** | Decouples services from messaging technology (Kafka vs SQS) |
| 3 | **@Profile("!aws") / @Profile("aws")** | Conditional bean loading based on environment |
| 4 | **@Component vs @Service** | @Component for infrastructure, @Service for business logic (convention) |
| 5 | **CompletableFuture** | Async Kafka send — non-blocking, result handled in callback |
| 6 | **whenComplete callback** | Handle success (log partition+offset) or failure (log error) |
| 7 | **Kafka message key** | paymentId ensures ordering within partition (authorize before capture) |
| 8 | **Fire-and-forget** | Kafka failure is logged, not re-thrown — payment still succeeds |
| 9 | **Redis SET NX EX** | Atomic lock: "set only if not exists" + "expire after 24h" |
| 10 | **setIfAbsent()** | Spring Data Redis equivalent of `SET key value NX EX ttl` |
| 11 | **PROCESSING sentinel** | Distinguishes "being processed" from "cached result" and "not found" |
| 12 | **Three states in Redis** | null (new), "PROCESSING" (in progress), JSON (cached result) |
| 13 | **releaseLock on failure** | Delete the PROCESSING sentinel so client can retry |
| 14 | **cacheResponse overwrites** | Replaces "PROCESSING" with actual JSON response |
| 15 | **ObjectMapper for JSON** | Serialize response→JSON for Redis storage, deserialize back for cache hits |
| 16 | **SQS stub** | Placeholder implementation — works when code runs, real SQS integration is TODO |
| 17 | **Mocking ValueOperations** | Must mock BOTH redisTemplate AND opsForValue() return value |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part08-payment-service-overview.md) | Payment Service Overview |
| [Part 8a](./phase4-part08a-payment-project-setup.md) | Project Setup |
| [Part 8b](./phase4-part08b-payment-entities.md) | Entities |
| [Part 8c](./phase4-part08c-payment-migrations.md) | Flyway Migrations |
| [Part 8d](./phase4-part08d-payment-repositories.md) | Repositories |
| [Part 8e](./phase4-part08e-payment-dtos-mappers.md) | DTOs + Mappers |
| [Part 8f](./phase4-part08f-payment-configs.md) | Config Classes |
| [Part 8g](./phase4-part08g-payment-order-refund-services.md) | OrderService + RefundService |
| **Part 8h** | **IdempotencyService + EventPublisher + Tests** (You are here) |
| [Part 8i](./phase4-part08i-payment-engine.md) | PaymentService Core Engine |
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + Docker |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | Connections & Flows |

---

*Next: [Part 8i — PaymentService Core Engine + Tests](./phase4-part08i-payment-engine.md) →*
