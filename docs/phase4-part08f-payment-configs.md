# 🏗️ Phase 4 Part 8f: Payment Service — Config Classes (Redis, Kafka, Feign)

> **"Three new technologies, three config classes. Each one configures HOW the payment service talks to external systems."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8f — Config Classes |
| **What You Build** | RedisConfig.java, KafkaProducerConfig.java, FeignConfig.java |
| **Previous** | [Part 8e — DTOs + Mappers](./phase4-part08e-payment-dtos-mappers.md) |
| **Next** | [Part 8g — OrderService + RefundService](./phase4-part08g-payment-order-refund-services.md) |

---

## 📖 Table of Contents

1. [Why We Need Config Classes](#1-why-we-need-config-classes)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: RedisConfig.java](#3-step-by-step-redisconfigjava)
4. [Step-by-Step: KafkaProducerConfig.java](#4-step-by-step-kafkaproducerconfigjava)
5. [Step-by-Step: FeignConfig.java](#5-step-by-step-feignconfigjava)
6. [How Each Config Connects to Its Service](#6-how-each-config-connects-to-its-service)
7. [What You Learned](#7-what-you-learned)

---

## 1. Why We Need Config Classes

Spring Boot auto-configures Redis, Kafka, and Feign from `application.yml`. But auto-config gives you **defaults**. Config classes let you **customize** behavior:

| Technology | Auto-Config Gives You | Config Class Customizes |
|---|---|---|
| **Redis** | `RedisTemplate<Object, Object>` | `StringRedisTemplate` with String serializers (human-readable keys) |
| **Kafka** | Basic producer factory | `acks=all`, `idempotence=true`, `retries=3`, no type headers |
| **Feign** | Default timeouts (10s/60s) | 5s connect / 10s read, retry 3 times, BASIC logging |

**WITHOUT config classes:** defaults work but aren't production-ready.
**WITH config classes:** tuned for a payment system's reliability requirements.

---

## 2. Folder Structure After This Part

```
backend/payment-service/src/main/java/com/payflow/payment/
├── PaymentServiceApplication.java    ← from 8a
├── config/
│   ├── SecurityConfig.java           ← from 8a
│   ├── RedisConfig.java              ← YOU CREATE THIS
│   ├── KafkaProducerConfig.java      ← YOU CREATE THIS
│   └── FeignConfig.java              ← YOU CREATE THIS
├── model/                            ← from 8b
├── repository/                       ← from 8d
├── dto/                              ← from 8e
└── mapper/                           ← from 8e
```

---

## 3. Step-by-Step: RedisConfig.java

**File:** `src/main/java/com/payflow/payment/config/RedisConfig.java`

### What It Does
Configures a `StringRedisTemplate` — a Redis client that stores keys and values as **human-readable strings** (not binary Java objects).

### Why We Need It

```
WITHOUT RedisConfig (default auto-config):
  Redis stores: \xac\xed\x00\x05t\x00\x12payment:idempotency:key123
  You run: redis-cli GET "payment:idempotency:key123"
  You see: (nil) ← Can't find it! The key is binary, not a readable string.

WITH RedisConfig (String serializers):
  Redis stores: payment:idempotency:key123 → {"paymentId":"pay_abc","status":"AUTHORIZED"}
  You run: redis-cli GET "payment:idempotency:key123"
  You see: {"paymentId":"pay_abc","status":"AUTHORIZED"} ← Human-readable!
```

### Line-by-Line

```java
package com.payflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
```

| Import | What |
|---|---|
| `RedisConnectionFactory` | Spring auto-creates this from application.yml (host, port, timeout) |
| `StringRedisTemplate` | Redis client that works with String keys + String values |
| `StringRedisSerializer` | Converts Java Strings → Redis byte arrays as UTF-8 text (not Java serialization) |

```java
/**
 * Redis configuration for idempotency and caching.
 * Uses StringRedisTemplate for simple key-value operations.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
```

**`RedisConnectionFactory connectionFactory`** — Spring auto-creates this bean from application.yml:
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
```

We receive it as a parameter and use it to configure our template.

```java
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
```

Create a new template and connect it to Redis.

```java
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
```

**FOUR SERIALIZERS — WHY ALL FOUR?**

Redis has two data structures we might use:
1. **Key-Value** (most common): `SET key value` / `GET key`
2. **Hash** (maps): `HSET hash field value` / `HGET hash field`

| Serializer | What It Controls | Without It |
|---|---|---|
| `keySerializer` | How keys are written | Binary Java serialized keys (unreadable) |
| `valueSerializer` | How values are written | Binary Java serialized values (unreadable) |
| `hashKeySerializer` | How hash field names are written | Binary |
| `hashValueSerializer` | How hash field values are written | Binary |

**ALL set to `StringRedisSerializer`** = everything in Redis is human-readable UTF-8 text.

```java
        template.afterPropertiesSet();
        return template;
    }
}
```

**`afterPropertiesSet()`** — Initializes the template (validates connection factory, finalizes serializer setup). Must be called after all properties are set. Think of it as "boot up" for the template.

### How IdempotencyService Uses This

```java
// In IdempotencyService (Part 8h):
@Autowired
private StringRedisTemplate redisTemplate;

// Store: key="payment:idempotency:uuid-123", value="{json response}", TTL=24hrs
redisTemplate.opsForValue().set(key, jsonValue, 24, TimeUnit.HOURS);

// Read: returns the JSON string (or null if not found)
String cached = redisTemplate.opsForValue().get(key);
```

---

## 4. Step-by-Step: KafkaProducerConfig.java

**File:** `src/main/java/com/payflow/payment/config/KafkaProducerConfig.java`

### What It Does
Configures a Kafka producer that publishes `PaymentEvent` messages with exactly-once delivery guarantees.

### Why We Need It (Not Just application.yml)

The `application.yml` Kafka config sets basic properties. But the config class:
1. Creates a typed `KafkaTemplate<String, PaymentEvent>` (not generic)
2. Disables Java type headers in JSON (consumers don't need Java class info)
3. Uses `@Profile("!aws")` to disable when running on AWS (SQS used instead)

### Line-by-Line

```java
package com.payflow.payment.config;

import com.payflow.common.event.PaymentEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;
```

| Import | What |
|---|---|
| `PaymentEvent` | From common-lib — the event object published to Kafka |
| `ProducerConfig` | Kafka's configuration constants (key names for settings) |
| `StringSerializer` | Serializes Kafka message keys as strings |
| `JsonSerializer` | Serializes Kafka message values (PaymentEvent) as JSON |
| `@Profile` | Conditional bean activation — only loads for specific profiles |
| `KafkaTemplate` | The high-level Kafka client for sending messages |
| `ProducerFactory` | Creates Kafka producer instances (manages connections to Kafka brokers) |

```java
/**
 * Kafka producer configuration for publishing payment events.
 * Active in non-AWS profiles (Kafka is the default messaging system).
 */
@Configuration
@Profile("!aws")
public class KafkaProducerConfig {
```

**🆕 `@Profile("!aws")` — CONDITIONAL CONFIGURATION**

```
@Profile("!aws") means:
  "Create these beans ONLY when the 'aws' profile is NOT active"

When running locally:     SPRING_PROFILES_ACTIVE=default  → Kafka config LOADS ✓
When running in Docker:   SPRING_PROFILES_ACTIVE=docker   → Kafka config LOADS ✓
When running on AWS:      SPRING_PROFILES_ACTIVE=aws      → Kafka config SKIPPED ✗
                          (SQS is used instead — see SqsEventPublisher)
```

**WHY?** On AWS, you might use Amazon SQS instead of Kafka. The `@Profile` annotation lets you swap implementations without changing code — just change the active profile.

```java
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;
```

**`@Value` WITH DEFAULT:** Reads `spring.kafka.bootstrap-servers` from application.yml. If not found, uses `localhost:9092` as fallback.

```java
    @Bean
    public ProducerFactory<String, PaymentEvent> producerFactory() {
        Map<String, Object> config = new HashMap<>();
```

**ProducerFactory** creates Kafka producer instances. We configure it with a Map of settings.

**`<String, PaymentEvent>`** — key type is String (paymentId), value type is PaymentEvent (the event object).

```java
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
```

**Where to find Kafka.** `"localhost:9092"` — the Kafka broker address.

In a cluster, this could be multiple brokers: `"kafka1:9092,kafka2:9092,kafka3:9092"`.

```java
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
```

**How to serialize the message KEY.** Keys are Strings (paymentId like `"pay_abc123"`). StringSerializer converts them to bytes.

**WHY USE paymentId AS KEY?** Kafka guarantees ordering within a partition. Messages with the same key go to the same partition. So all events for ONE payment are ordered:
```
Key: "pay_abc" → Partition 3
  Event 1: payment.authorized (always processed first)
  Event 2: payment.captured   (always processed second)
  Event 3: payment.refunded   (always processed third)
```

Without a key, events might be processed out of order (refund before capture!).

```java
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
```

**How to serialize the message VALUE.** PaymentEvent objects are serialized as JSON:
```json
{
  "eventId": "evt_abc123",
  "eventType": "payment.authorized",
  "paymentId": "pay_xyz789",
  "orderId": "order_def456",
  "amount": 1000.00,
  "currency": "INR",
  "status": "AUTHORIZED",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

```java
        config.put(ProducerConfig.ACKS_CONFIG, "all");
```

**🆕 `acks=all` — DURABILITY GUARANTEE**

| acks | What | Risk | Speed |
|---|---|---|---|
| `0` | Don't wait for ANY confirmation | Message can be LOST | Fastest |
| `1` | Wait for leader broker only | Lost if leader crashes before replicating | Fast |
| **`all`** | **Wait for ALL replicas** | **Message is NEVER lost** | **Slowest** |

**FOR A PAYMENT SYSTEM: `acks=all` IS MANDATORY.** A lost event means:
- Merchant doesn't get notified (webhook never delivered)
- Settlement never includes this payment (merchant doesn't get paid)
- Customer never gets a receipt email

```java
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
```

**Retry up to 3 times** if a send fails (network glitch, broker temporarily unavailable). After 3 failures → exception thrown (caught by EventPublisher, logged, payment NOT rolled back).

```java
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
```

**🆕 EXACTLY-ONCE DELIVERY**

```
WITHOUT idempotence:
  Send message → network timeout → retry → Kafka might store TWO copies
  Result: Merchant gets TWO webhook notifications for one payment

WITH idempotence (enable.idempotence=true):
  Send message → network timeout → retry → Kafka deduplicates (stores only ONE copy)
  Result: Merchant gets exactly ONE notification
```

Kafka assigns a unique producer ID and sequence number. If the same message is received twice, Kafka discards the duplicate.

```java
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
```

**🆕 DISABLE TYPE HEADERS**

By default, Spring's `JsonSerializer` adds a `__TypeId__` header to every Kafka message:
```
Header: __TypeId__ = com.payflow.common.event.PaymentEvent
```

This tells the Java consumer which class to deserialize into. But:
- Non-Java consumers (Python, Go) don't understand Java class names
- The consumer already knows the type (it's reading from a specific topic)
- Extra headers waste space

`ADD_TYPE_INFO_HEADERS = false` removes this overhead.

```java
        return new DefaultKafkaProducerFactory<>(config);
    }
```

Create the factory with all our settings.

```java
    @Bean
    public KafkaTemplate<String, PaymentEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

**`KafkaTemplate`** — the high-level client services use to send messages:

```java
// In KafkaEventPublisher:
kafkaTemplate.send("payment.events", paymentId, paymentEvent);
//                   topic            key        value
```

---

## 5. Step-by-Step: FeignConfig.java

**File:** `src/main/java/com/payflow/payment/config/FeignConfig.java`

### What It Does
Configures how Feign HTTP clients behave: timeouts, retries, and logging.

### Why We Need It

Default Feign settings:
- Connect timeout: 10 seconds (too long — if routing-service is unreachable, waiting 10s blocks the payment)
- Read timeout: 60 seconds (way too long — a bank response shouldn't take a minute)
- Retries: 0 (no retry — one network glitch and the payment fails)
- Logging: NONE (can't debug inter-service issues)

### Line-by-Line

```java
package com.payflow.payment.config;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;
```

**ALL imports are from `feign.*`** (not Spring — these are OpenFeign core classes).

| Import | What |
|---|---|
| `feign.Logger` | Logging level control for Feign HTTP calls |
| `feign.Request` | Timeout configuration |
| `feign.Retryer` | Retry strategy configuration |

```java
/**
 * Feign client configuration for inter-service communication.
 * Configures timeouts, retries, and logging level.
 */
@Configuration
public class FeignConfig {
```

No `@Profile` — applies in ALL environments. Feign is always needed (routing-service must always be reachable).

```java
    @Bean
    public Logger.Level feignLogLevel() {
        return Logger.Level.BASIC;
    }
```

**FEIGN LOGGING LEVELS:**

| Level | What It Logs | When to Use |
|---|---|---|
| `NONE` | Nothing | Production (minimal overhead) |
| **`BASIC`** | **Method, URL, response status, timing** | **Our choice — enough for debugging** |
| `HEADERS` | BASIC + request/response headers | When debugging auth issues |
| `FULL` | HEADERS + request/response body | When debugging data issues (CAREFUL: logs sensitive data!) |

**Example BASIC log output:**
```
[RoutingServiceClient#routePayment] ---> POST http://routing-service/internal/route HTTP/1.1
[RoutingServiceClient#routePayment] <--- HTTP/1.1 200 OK (45ms)
```

This tells you: which service was called, the URL, and how long it took. Enough to diagnose 90% of issues.

**WHY NOT FULL?** It logs the entire request/response body — which could contain card numbers, amounts, and other sensitive payment data. BASIC gives timing info without sensitive data.

```java
    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(
                5, TimeUnit.SECONDS,   // connect timeout
                10, TimeUnit.SECONDS,  // read timeout
                true                    // follow redirects
        );
    }
```

**THREE SETTINGS:**

| Setting | Value | What It Means | Why This Value |
|---|---|---|---|
| **Connect timeout** | 5 seconds | "If I can't establish a TCP connection in 5s, give up" | If routing-service is unreachable, don't wait forever |
| **Read timeout** | 10 seconds | "If I don't get a response in 10s after connecting, give up" | Bank authorization typically takes 1-3 seconds. 10s gives generous buffer. |
| **Follow redirects** | true | "If routing-service returns 301/302, follow it" | Standard HTTP behavior |

**REAL-WORLD SCENARIO:**
```
Payment Service → POST to routing-service → routing-service → POST to bank
                   ↑ 5s connect limit     ↑ 10s read limit (includes bank call time)
                   
If bank takes 15s → read timeout hits → FeignException thrown
PaymentService catches it → marks payment as FAILED → "Bank timeout"
```

```java
    @Bean
    public Retryer feignRetryer() {
        // Retry up to 3 times with 100ms initial interval and 1s max interval
        return new Retryer.Default(100, 1000, 3);
    }
```

**RETRY STRATEGY:**

| Parameter | Value | Meaning |
|---|---|---|
| `100` | 100ms | Initial wait before first retry |
| `1000` | 1000ms (1 second) | Maximum wait between retries |
| `3` | 3 attempts | Maximum number of retries |

**EXPONENTIAL BACKOFF:**
```
Attempt 1: Call routing-service → fails
Wait 100ms
Attempt 2: Call routing-service → fails
Wait ~200ms (exponential increase, capped at 1000ms)
Attempt 3: Call routing-service → fails
→ Give up, throw FeignException
```

**WHY RETRY?** Network glitches are temporary. If routing-service is briefly unreachable (pod restarting, network blip), a retry 100ms later usually succeeds.

**WHY MAX 3?** If the service is truly down (not just a glitch), retrying 10 times wastes 10 seconds. 3 retries with backoff = ~1.3 seconds total. Fast enough to not block the customer.

**WHY NOT RETRY FOR EVER?** The customer is waiting. A payment that takes 30 seconds to fail is worse than one that fails in 2 seconds.

```java
}
```

---

## 6. How Each Config Connects to Its Service

```
┌───────────────────────────────────────────────────────────────────────────┐
│                    CONFIG → SERVICE → INFRASTRUCTURE                      │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  RedisConfig                                                             │
│  ├── Creates: StringRedisTemplate bean                                   │
│  └── Used by: IdempotencyService (Part 8h)                              │
│       → SET payment:idempotency:key123 "{json}" EX 86400               │
│       → GET payment:idempotency:key123                                  │
│       → DEL payment:idempotency:key123                                  │
│                       ↓                                                  │
│                 Redis (:6379)                                            │
│                                                                           │
│  KafkaProducerConfig                                                     │
│  ├── Creates: KafkaTemplate<String, PaymentEvent> bean                  │
│  └── Used by: KafkaEventPublisher (Part 8h)                             │
│       → send("payment.events", "pay_abc", paymentEvent)                │
│                       ↓                                                  │
│                 Kafka (:9092) → consumers (webhook, settlement, etc.)   │
│                                                                           │
│  FeignConfig                                                             │
│  ├── Creates: Logger.Level, Request.Options, Retryer beans              │
│  └── Used by: RoutingServiceClient, MerchantServiceClient (Part 8j)    │
│       → POST http://routing-service/internal/route                      │
│                       ↓                                                  │
│                 routing-service (:8084) → bank simulator (:9090)        │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 7. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **StringRedisTemplate** | Uses String serializers → human-readable keys/values in Redis CLI |
| 2 | **StringRedisSerializer** | Set on key, value, hashKey, hashValue → ALL data stored as UTF-8 text |
| 3 | **afterPropertiesSet()** | Initializes the template after all properties are configured |
| 4 | **@Profile("!aws")** | Bean only loads when the "aws" profile is NOT active — for env switching |
| 5 | **ProducerFactory** | Creates Kafka producer instances with custom configuration |
| 6 | **KafkaTemplate** | High-level Kafka client: `kafkaTemplate.send(topic, key, value)` |
| 7 | **acks=all** | Wait for ALL Kafka replicas → message is never lost (mandatory for payments) |
| 8 | **enable.idempotence=true** | Kafka deduplicates retried messages → exactly-once delivery |
| 9 | **ADD_TYPE_INFO_HEADERS=false** | Don't add Java class names to Kafka messages (cleaner, cross-language) |
| 10 | **Kafka key = paymentId** | Ensures all events for one payment go to same partition → ordered processing |
| 11 | **Logger.Level.BASIC** | Logs URL + status + timing without sensitive data (safe for payments) |
| 12 | **5s connect / 10s read** | Aggressive timeouts — don't make the customer wait for a dead service |
| 13 | **Retryer.Default(100, 1000, 3)** | Retry 3x with exponential backoff (100ms → ~200ms → ~400ms) |
| 14 | **Why retry max 3** | Balance: catch transient glitches without blocking the customer |
| 15 | **Config class vs application.yml** | yml sets basic properties; config class creates customized Spring beans |

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
| **Part 8f** | **Config Classes** (You are here) |
| [Part 8g](./phase4-part08g-payment-order-refund-services.md) | OrderService + RefundService |
| [Part 8h](./phase4-part08h-payment-idempotency-events.md) | IdempotencyService + Events |
| [Part 8i](./phase4-part08i-payment-engine.md) | PaymentService Core Engine |
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + Docker |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | Connections & Flows |

---

*Next: [Part 8g — OrderService + RefundService + Tests](./phase4-part08g-payment-order-refund-services.md) →*
