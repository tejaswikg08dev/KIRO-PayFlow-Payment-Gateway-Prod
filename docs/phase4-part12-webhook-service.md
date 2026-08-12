# Phase 4 · Part 12 — Webhook Service

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Event-Driven Services |
| **Part** | 12 — Webhook Delivery Service |
| **Previous** | [Part 11 — Settlement Service](./phase4-part11-settlement-service.md) |
| **Next** | [Part 13 — Notification Service](./phase4-part13-notification-service.md) |
| **Time** | ~2.5 hours |
| **Difficulty** | ★★★☆☆ (Intermediate) |
| **Prerequisites** | Kafka (Part 6), HMAC basics, HTTP webhooks concept |
| **What You'll Build** | A service that delivers payment events to merchant webhook URLs with signatures and retries |
| **Git Commit** | `feat(webhook): add webhook delivery with HMAC signatures and retry` |

---

## Table of Contents

1. [What Are Webhooks?](#1-what-are-webhooks)
2. [KafkaWebhookConsumer](#2-kafkawebhookconsumer)
3. [WebhookDeliveryService](#3-webhookdeliveryservice)
4. [HmacSignatureService](#4-hmacsignatureservice)
5. [RetryPolicyService](#5-retrypolicyservice)
6. [DynamoDbDeliveryRepository](#6-dynamodbdeliveryrepository)
7. [Dead Letter Topic](#7-dead-letter-topic)
8. [Webhook Payload Example](#8-webhook-payload-example)
9. [Merchant Signature Verification](#9-merchant-signature-verification)
10. [What You Learned](#10-what-you-learned)
11. [Common Errors & Fixes](#11-common-errors--fixes)
12. [Git Commit](#12-git-commit)

---

## What You'll Learn

- How webhooks push notifications to merchants (vs polling)
- How to consume Kafka events and deliver them as HTTP POST requests
- How HMAC-SHA256 signatures prove a webhook came from PayFlow (not an attacker)
- How exponential backoff prevents overwhelming a failing merchant server
- How Dead Letter Topics handle permanently failed deliveries
- How merchants verify webhook authenticity in their code

---

## 1. What Are Webhooks?

Webhooks are **HTTP callbacks** — when something happens in PayFlow (payment success, refund, etc.), we push a notification to the merchant's server.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                 POLLING vs WEBHOOKS                                       │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  POLLING (merchant asks us repeatedly):                                  │
│  ┌──────────┐                            ┌──────────┐                    │
│  │ Merchant │ ──→ "Any updates?" ──────→ │ PayFlow  │                    │
│  │ Server   │ ←── "Nope" ──────────────  │          │                    │
│  │          │ ──→ "Any updates?" ──────→ │          │  (1 minute later)  │
│  │          │ ←── "Nope" ──────────────  │          │                    │
│  │          │ ──→ "Any updates?" ──────→ │          │  (1 minute later)  │
│  │          │ ←── "Yes! Payment OK!" ──  │          │                    │
│  └──────────┘                            └──────────┘                    │
│  Problems: Wastes API calls, delayed detection (up to 1 min)             │
│                                                                           │
│  WEBHOOKS (we tell them immediately):                                    │
│  ┌──────────┐                            ┌──────────┐                    │
│  │ Merchant │ ←── "Payment succeeded!" ─ │ PayFlow  │  (instant!)       │
│  │ Server   │ ──→ "200 OK, got it" ───→  │          │                    │
│  └──────────┘                            └──────────┘                    │
│  Benefits: Instant notification, no wasted calls                         │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
```

### Webhook Delivery Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                    WEBHOOK SERVICE FLOW                                 │
├────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Kafka Topics              Webhook Service              Merchant        │
│  ─────────────             ───────────────              ─────────       │
│                                                                         │
│  payment.authorized ──┐                                                │
│  payment.captured  ───┤──→ KafkaWebhookConsumer                        │
│  payment.failed    ───┤         │                                      │
│  refund.completed  ───┘         ▼                                      │
│                         WebhookDeliveryService                          │
│                              │         │                                │
│                              ▼         ▼                                │
│                    HmacSignature    Build HTTP Request                  │
│                    Service              │                               │
│                              │         │                                │
│                              ▼         ▼                                │
│                         POST https://merchant.com/webhooks              │
│                         Headers:                                        │
│                           X-PayFlow-Signature: sha256=abc123...         │
│                           X-PayFlow-Event: payment.authorized           │
│                           X-PayFlow-Delivery-Id: uuid                   │
│                                    │                                    │
│                                    ▼                                    │
│                         ┌─── Response ───┐                             │
│                         │                │                              │
│                     200 OK          4xx/5xx/Timeout                     │
│                         │                │                              │
│                         ▼                ▼                              │
│                    Mark DELIVERED    Schedule RETRY                     │
│                                     (exponential backoff)              │
│                                          │                             │
│                                          ▼                             │
│                                   After 4 failures                     │
│                                          │                             │
│                                          ▼                             │
│                                   Dead Letter Topic                    │
│                                                                         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. KafkaWebhookConsumer

```java
package com.payflow.webhook.consumer;

import com.payflow.webhook.model.WebhookEvent;
import com.payflow.webhook.service.WebhookDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes payment events from Kafka and triggers webhook delivery.
 *
 * WHY @KafkaListener with topicPattern:
 * We listen to ALL payment-related topics (payment.*, refund.*)
 * using a regex pattern. New event types are automatically picked up.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KafkaWebhookConsumer {

    private final WebhookDeliveryService deliveryService;

    // WHY topicPattern: Matches payment.authorized, payment.captured, payment.failed,
    // refund.completed, etc. — any new topic matching the pattern is auto-subscribed.
    @KafkaListener(
            topicPattern = "payment\\..*|refund\\..*",
            groupId = "webhook-delivery-group",
            containerFactory = "webhookKafkaListenerContainerFactory"
    )
    public void onPaymentEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("📥 Received event: topic={}, key={}, offset={}", topic, key, offset);

        try {
            // WHY: Parse the Kafka message into our domain model
            WebhookEvent event = WebhookEvent.builder()
                    .eventType(topic)           // e.g., "payment.authorized"
                    .merchantId(extractMerchantId(payload))
                    .payload(payload)           // Raw JSON payload
                    .kafkaOffset(offset)
                    .build();

            // WHY: Delegate to delivery service (separation of concerns)
            // Consumer handles Kafka; delivery service handles HTTP
            deliveryService.deliver(event);

        } catch (Exception e) {
            // WHY: Log but don't rethrow — we don't want to block the consumer
            // Failed deliveries are handled by retry mechanism
            log.error("❌ Failed to process webhook event: topic={}, offset={}",
                    topic, offset, e);
        }
    }

    private String extractMerchantId(String payload) {
        // WHY: Simple JSON parsing (in production, use Jackson ObjectMapper)
        // The merchantId determines which webhook URL to use
        int idx = payload.indexOf("\"merchantId\":\"");
        if (idx == -1) return "UNKNOWN";
        int start = idx + 14;
        int end = payload.indexOf("\"", start);
        return payload.substring(start, end);
    }
}
```

---

## 3. WebhookDeliveryService

```java
package com.payflow.webhook.service;

import com.payflow.webhook.model.*;
import com.payflow.webhook.repository.DynamoDbDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Delivers webhook events to merchant URLs.
 * Handles signature generation, HTTP delivery, and result tracking.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebhookDeliveryService {

    private final HmacSignatureService signatureService;
    private final RetryPolicyService retryPolicy;
    private final DynamoDbDeliveryRepository deliveryRepo;
    private final MerchantWebhookConfigService configService;
    private final RestTemplate restTemplate;

    public void deliver(WebhookEvent event) {
        // Step 1: Look up merchant's webhook configuration
        WebhookConfig config = configService.getConfig(event.getMerchantId());

        if (config == null || !config.isEnabled()) {
            log.debug("Webhook not configured for merchant: {}", event.getMerchantId());
            return;  // WHY: Merchant hasn't set up webhooks — skip silently
        }

        // Step 2: Create delivery record (for tracking)
        String deliveryId = UUID.randomUUID().toString();
        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .deliveryId(deliveryId)
                .merchantId(event.getMerchantId())
                .eventType(event.getEventType())
                .webhookUrl(config.getUrl())
                .payload(event.getPayload())
                .attemptNumber(1)
                .status(DeliveryStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        // Step 3: Generate HMAC signature
        // WHY: Proves to merchant that this request came from PayFlow (not an attacker)
        String signature = signatureService.sign(event.getPayload(), config.getSecret());

        // Step 4: Build HTTP request with PayFlow headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-PayFlow-Signature", "sha256=" + signature);
        headers.set("X-PayFlow-Event", event.getEventType());
        headers.set("X-PayFlow-Delivery-Id", deliveryId);
        headers.set("X-PayFlow-Timestamp", String.valueOf(Instant.now().getEpochSecond()));

        HttpEntity<String> request = new HttpEntity<>(event.getPayload(), headers);

        // Step 5: Send HTTP POST to merchant's webhook URL
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    config.getUrl(),
                    HttpMethod.POST,
                    request,
                    String.class
            );

            // WHY: Any 2xx = success. Merchant acknowledged receipt.
            if (response.getStatusCode().is2xxSuccessful()) {
                attempt.setStatus(DeliveryStatus.DELIVERED);
                attempt.setResponseCode(response.getStatusCode().value());
                log.info("✅ Webhook delivered: deliveryId={}, merchant={}, event={}",
                        deliveryId, event.getMerchantId(), event.getEventType());
            } else {
                // WHY: 3xx, 4xx = merchant-side issue. Schedule retry.
                handleFailure(attempt, response.getStatusCode().value(), null);
            }

        } catch (Exception e) {
            // WHY: Timeout, connection refused, DNS failure, etc.
            handleFailure(attempt, 0, e.getMessage());
        }

        // Step 6: Save delivery record (success or failure)
        deliveryRepo.save(attempt);
    }

    private void handleFailure(DeliveryAttempt attempt, int statusCode, String error) {
        attempt.setStatus(DeliveryStatus.FAILED);
        attempt.setResponseCode(statusCode);
        attempt.setErrorMessage(error);

        log.warn("⚠️ Webhook delivery failed: deliveryId={}, attempt={}, status={}, error={}",
                attempt.getDeliveryId(), attempt.getAttemptNumber(), statusCode, error);

        // Schedule retry with exponential backoff
        retryPolicy.scheduleRetry(attempt);
    }
}
```

---

## 4. HmacSignatureService

```java
package com.payflow.webhook.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Generates HMAC-SHA256 signatures for webhook payloads.
 *
 * WHY HMAC-SHA256:
 * 1. Proves payload wasn't tampered with (integrity)
 * 2. Proves it came from someone who knows the secret (authenticity)
 * 3. Industry standard — Stripe, GitHub, Shopify all use it
 *
 * How it works:
 * signature = HMAC-SHA256(payload, merchant_secret)
 * Merchant computes same signature on their end.
 * If signatures match → legitimate webhook from PayFlow.
 */
@Service
@Slf4j
public class HmacSignatureService {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Sign a payload with the merchant's webhook secret.
     *
     * @param payload The JSON body being sent to merchant
     * @param secret  The merchant's unique webhook secret (set during onboarding)
     * @return Hex-encoded HMAC-SHA256 signature
     */
    public String sign(String payload, String secret) {
        try {
            // WHY SecretKeySpec: Wraps the raw secret string into a cryptographic key object
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    ALGORITHM
            );

            // WHY Mac.getInstance: Gets the HMAC-SHA256 algorithm implementation
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);

            // WHY: Compute the HMAC over the entire payload (any change = different signature)
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

            // WHY hex encoding: Binary bytes → human-readable hex string for HTTP header
            return HexFormat.of().formatHex(hmacBytes);

        } catch (Exception e) {
            // WHY: This should never happen with SHA256, but handle gracefully
            log.error("❌ HMAC signature generation failed", e);
            throw new RuntimeException("Failed to generate webhook signature", e);
        }
    }

    /**
     * Verify a signature (used in tests and by our own webhook receiver if needed).
     */
    public boolean verify(String payload, String secret, String expectedSignature) {
        String computed = sign(payload, secret);
        // WHY constantTimeEquals: Prevents timing attacks
        // (attacker can't measure response time to guess signature byte-by-byte)
        return constantTimeEquals(computed, expectedSignature);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            // WHY XOR: If any byte differs, result becomes non-zero
            // WHY |= (not !=): Ensures we always check ALL bytes (no early return)
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
```

---

## 5. RetryPolicyService

```java
package com.payflow.webhook.service;

import com.payflow.webhook.model.DeliveryAttempt;
import com.payflow.webhook.model.DeliveryStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Exponential backoff retry policy for failed webhook deliveries.
 *
 * Retry Schedule:
 * ┌──────────┬────────────┬─────────────────────────────────────┐
 * │ Attempt  │ Wait Time  │ Rationale                           │
 * ├──────────┼────────────┼─────────────────────────────────────┤
 * │ 1 → 2   │ 5 minutes  │ Quick retry (network blip?)         │
 * │ 2 → 3   │ 30 minutes │ Maybe server restart                │
 * │ 3 → 4   │ 2 hours    │ Maybe deployment issue              │
 * │ 4 → DLT │ 24 hours   │ Last chance before giving up        │
 * └──────────┴────────────┴─────────────────────────────────────┘
 *
 * WHY exponential: Don't hammer a failing server.
 * If merchant's server is down for deployment (30 min), the 5-min retry
 * would fail, but the 30-min retry catches it when it's back.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RetryPolicyService {

    private final TaskScheduler taskScheduler;
    private final WebhookDeliveryService deliveryService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final int MAX_ATTEMPTS = 4;

    // WHY: Durations as array for easy lookup by attempt number
    private static final Duration[] RETRY_DELAYS = {
            Duration.ofMinutes(5),   // After 1st failure: wait 5 min
            Duration.ofMinutes(30),  // After 2nd failure: wait 30 min
            Duration.ofHours(2),     // After 3rd failure: wait 2 hours
            Duration.ofHours(24)     // After 4th failure: wait 24 hours (last try)
    };

    public void scheduleRetry(DeliveryAttempt failedAttempt) {
        int currentAttempt = failedAttempt.getAttemptNumber();

        if (currentAttempt >= MAX_ATTEMPTS) {
            // WHY: After 4 failures, send to Dead Letter Topic
            // Humans need to investigate (merchant URL wrong? server permanently dead?)
            sendToDeadLetter(failedAttempt);
            return;
        }

        // Calculate next retry time
        Duration delay = RETRY_DELAYS[currentAttempt - 1];
        Instant nextAttemptTime = Instant.now().plus(delay);

        log.info("⏰ Scheduling retry #{} for deliveryId={} at {} (delay={})",
                currentAttempt + 1,
                failedAttempt.getDeliveryId(),
                nextAttemptTime,
                delay);

        // WHY TaskScheduler: Spring's built-in scheduler for delayed execution
        // In production with multiple instances, use a persistent scheduler (Quartz/DB)
        taskScheduler.schedule(
                () -> retryDelivery(failedAttempt),
                nextAttemptTime
        );
    }

    private void retryDelivery(DeliveryAttempt previousAttempt) {
        log.info("🔄 Retrying webhook delivery: deliveryId={}, attempt=#{}",
                previousAttempt.getDeliveryId(),
                previousAttempt.getAttemptNumber() + 1);

        // WHY: Reconstruct the event and re-deliver
        // The delivery service handles the full flow (sign, send, record)
        WebhookEvent event = WebhookEvent.builder()
                .eventType(previousAttempt.getEventType())
                .merchantId(previousAttempt.getMerchantId())
                .payload(previousAttempt.getPayload())
                .build();

        // Increment attempt number
        previousAttempt.setAttemptNumber(previousAttempt.getAttemptNumber() + 1);

        deliveryService.deliver(event);
    }

    private void sendToDeadLetter(DeliveryAttempt attempt) {
        log.error("💀 Moving to Dead Letter Topic after {} attempts: deliveryId={}, merchant={}",
                attempt.getAttemptNumber(),
                attempt.getDeliveryId(),
                attempt.getMerchantId());

        // WHY: DLT preserves the event for manual inspection
        // Operations team can investigate and manually re-trigger
        attempt.setStatus(DeliveryStatus.DEAD_LETTER);

        kafkaTemplate.send(
                "webhook.dead-letter",
                attempt.getMerchantId(),
                attempt.getPayload()
        );
    }
}
```

---

## 6. DynamoDbDeliveryRepository

```java
package com.payflow.webhook.repository;

import com.payflow.webhook.model.DeliveryAttempt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores webhook delivery attempts in DynamoDB.
 *
 * WHY DynamoDB (not PostgreSQL):
 * 1. High write throughput — millions of webhook attempts per day
 * 2. Auto-scaling — no capacity planning needed
 * 3. TTL — auto-delete old records after 30 days (saves storage)
 * 4. No relations needed — each delivery is independent
 *
 * Table Design:
 * ┌───────────────┬──────────────────┬─────────────────────────────────┐
 * │ Partition Key  │ Sort Key         │ Attributes                      │
 * ├───────────────┼──────────────────┼─────────────────────────────────┤
 * │ merchantId     │ deliveryId       │ eventType, status, payload,     │
 * │               │                  │ attemptNumber, createdAt, etc.  │
 * └───────────────┴──────────────────┴─────────────────────────────────┘
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class DynamoDbDeliveryRepository {

    private final DynamoDbClient dynamoDb;
    private static final String TABLE_NAME = "webhook_deliveries";

    public void save(DeliveryAttempt attempt) {
        Map<String, AttributeValue> item = new HashMap<>();

        // WHY merchantId as partition key: Query all deliveries for a merchant efficiently
        item.put("merchantId", AttributeValue.builder().s(attempt.getMerchantId()).build());
        // WHY deliveryId as sort key: Unique identifier within a merchant
        item.put("deliveryId", AttributeValue.builder().s(attempt.getDeliveryId()).build());

        item.put("eventType", AttributeValue.builder().s(attempt.getEventType()).build());
        item.put("webhookUrl", AttributeValue.builder().s(attempt.getWebhookUrl()).build());
        item.put("payload", AttributeValue.builder().s(attempt.getPayload()).build());
        item.put("status", AttributeValue.builder().s(attempt.getStatus().name()).build());
        item.put("attemptNumber",
                AttributeValue.builder().n(String.valueOf(attempt.getAttemptNumber())).build());
        item.put("createdAt",
                AttributeValue.builder().n(String.valueOf(attempt.getCreatedAt().getEpochSecond())).build());

        // WHY TTL: DynamoDB auto-deletes items after this timestamp
        // 30 days from now — we don't need delivery history forever
        long ttl = attempt.getCreatedAt().plusSeconds(30 * 24 * 3600).getEpochSecond();
        item.put("ttl", AttributeValue.builder().n(String.valueOf(ttl)).build());

        if (attempt.getResponseCode() > 0) {
            item.put("responseCode",
                    AttributeValue.builder().n(String.valueOf(attempt.getResponseCode())).build());
        }
        if (attempt.getErrorMessage() != null) {
            item.put("errorMessage",
                    AttributeValue.builder().s(attempt.getErrorMessage()).build());
        }

        PutItemRequest request = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build();

        dynamoDb.putItem(request);
        log.debug("Saved delivery attempt: {}", attempt.getDeliveryId());
    }
}
```

---

## 7. Dead Letter Topic

```
┌────────────────────────────────────────────────────────────────────────┐
│                     DEAD LETTER TOPIC FLOW                             │
├────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Normal Flow:                                                           │
│  payment.authorized → WebhookConsumer → POST merchant.com → 200 OK ✅ │
│                                                                         │
│  Retry Flow:                                                            │
│  Attempt 1 (t=0)    → POST → 500 ❌                                   │
│  Attempt 2 (t+5min) → POST → 500 ❌                                   │
│  Attempt 3 (t+35min)→ POST → Timeout ❌                               │
│  Attempt 4 (t+2.5hr)→ POST → Connection Refused ❌                    │
│       │                                                                 │
│       ▼                                                                 │
│  webhook.dead-letter topic                                              │
│       │                                                                 │
│       ▼                                                                 │
│  Operations Dashboard:                                                  │
│  "Merchant XYZ has 5 undelivered webhooks"                             │
│  Actions:                                                               │
│    [Retry All] [View Payloads] [Disable Webhook] [Contact Merchant]   │
│                                                                         │
│  WHY Dead Letter (not just discard):                                   │
│  - Merchant might fix their URL later                                  │
│  - Legal requirement to deliver payment notifications                  │
│  - Operations visibility into systemic problems                        │
│  - Can replay events after merchant fixes their server                 │
│                                                                         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Webhook Payload Example

```json
{
    "id": "evt_a1b2c3d4e5f6",
    "type": "payment.authorized",
    "created_at": "2024-01-15T14:30:00Z",
    "data": {
        "payment_id": "pay_xK9mN2pQ3rS4",
        "merchant_id": "mer_abc123",
        "amount": 5000,
        "currency": "INR",
        "status": "AUTHORIZED",
        "card": {
            "last4": "4242",
            "brand": "visa",
            "exp_month": 12,
            "exp_year": 2025
        },
        "customer": {
            "email": "customer@example.com",
            "name": "Raj Patel"
        },
        "metadata": {
            "order_id": "ORD-2024-001",
            "invoice": "INV-5678"
        }
    }
}
```

### HTTP Request to Merchant

```http
POST /webhooks/payflow HTTP/1.1
Host: api.merchant-store.com
Content-Type: application/json
X-PayFlow-Signature: sha256=7d38cdd689735b008b3c702edd92eea23791c5f6c437d1c8f3456789abcdef01
X-PayFlow-Event: payment.authorized
X-PayFlow-Delivery-Id: del_abc123def456
X-PayFlow-Timestamp: 1705324200

{"id":"evt_a1b2c3d4e5f6","type":"payment.authorized","created_at":"2024-01-15T14:30:00Z",...}
```

---

## 9. Merchant Signature Verification

How merchants verify the webhook is legitimate (example in multiple languages).

### Java (Merchant's Code)

```java
/**
 * Example: How a merchant verifies PayFlow webhook signatures.
 * This code runs on the MERCHANT'S server, not PayFlow's.
 */
public class PayFlowWebhookVerifier {

    // WHY: This secret is given to the merchant during onboarding
    // It's stored securely (env variable, not hardcoded!)
    private static final String WEBHOOK_SECRET = System.getenv("PAYFLOW_WEBHOOK_SECRET");

    public boolean verifyWebhook(HttpServletRequest request, String body) {
        // Step 1: Extract signature from header
        String signatureHeader = request.getHeader("X-PayFlow-Signature");
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;  // Missing or malformed signature
        }
        String receivedSignature = signatureHeader.substring(7); // Remove "sha256=" prefix

        // Step 2: Compute expected signature
        String expectedSignature = computeHmac(body, WEBHOOK_SECRET);

        // Step 3: Compare (constant-time to prevent timing attacks)
        return MessageDigest.isEqual(
                receivedSignature.getBytes(),
                expectedSignature.getBytes()
        );
    }

    private String computeHmac(String payload, String secret) {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
```

### Node.js (Merchant's Code)

```javascript
// How a merchant verifies in Node.js (Express)
const crypto = require('crypto');

app.post('/webhooks/payflow', (req, res) => {
    const secret = process.env.PAYFLOW_WEBHOOK_SECRET;
    const signature = req.headers['x-payflow-signature'];
    const body = JSON.stringify(req.body);

    // Compute expected signature
    const expected = 'sha256=' + crypto
        .createHmac('sha256', secret)
        .update(body)
        .digest('hex');

    // Constant-time comparison
    if (crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expected))) {
        console.log('✅ Webhook verified!');
        // Process the event...
        res.status(200).send('OK');
    } else {
        console.log('❌ Invalid signature!');
        res.status(401).send('Unauthorized');
    }
});
```

---

## 10. What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Webhooks concept | Push notifications to merchants (vs polling) |
| 2 | KafkaListener | Pattern-based topic subscription (payment.\*, refund.\*) |
| 3 | HMAC-SHA256 | Cryptographic proof of authenticity (sign with shared secret) |
| 4 | Constant-time compare | Prevents timing attacks on signature verification |
| 5 | Exponential backoff | 5min → 30min → 2hr → 24hr retry schedule |
| 6 | Dead Letter Topic | After 4 failures, preserve for manual intervention |
| 7 | DynamoDB TTL | Auto-delete old delivery records after 30 days |
| 8 | X-PayFlow-Signature | Header format: `sha256=<hex-encoded HMAC>` |
| 9 | Merchant verification | Merchants recompute HMAC to verify webhook authenticity |
| 10 | Delivery tracking | Every attempt recorded (audit trail + debugging) |

---

## 11. Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `InvalidSignatureException` | Secret mismatch between PayFlow and merchant | Verify merchant has correct webhook secret |
| `ConnectTimeoutException` | Merchant server unreachable | Retry will handle it; check URL if persistent |
| Duplicate webhook deliveries | Consumer reprocessing after restart | Add idempotency key (deliveryId) on merchant side |
| `TopicAuthorizationException` | Kafka ACL missing for webhook consumer | Grant read permission on payment.* topics |
| DynamoDB `ProvisionedThroughputExceededException` | Too many writes | Enable auto-scaling or use on-demand mode |
| Signature valid but payload tampered | Signature computed on different body | Ensure raw body (not parsed/re-serialized) is used for HMAC |
| Webhook never arrives | Merchant firewall blocks PayFlow IPs | Merchant needs to whitelist PayFlow's IP range |
| Retry storms on merchant recovery | All retries fire at once when server comes back | Add jitter to retry delays |

---

## 12. Git Commit

```bash
# Stage webhook service files
git add backend/webhook-service/

# Commit
git commit -m "feat(webhook): add webhook delivery with HMAC signatures and retry

- KafkaWebhookConsumer: listens on payment.* and refund.* topics
- WebhookDeliveryService: HTTP POST with X-PayFlow-Signature header
- HmacSignatureService: HMAC-SHA256 with constant-time verification
- RetryPolicyService: exponential backoff (5min, 30min, 2hr, 24hr)
- DynamoDbDeliveryRepository: delivery tracking with 30-day TTL
- Dead Letter Topic: webhook.dead-letter after 4 failures
- Merchant verification examples: Java and Node.js"

# Push
git push origin feature/phase4-webhook-service
```

---

## Document Index

| # | Document | Status |
|---|----------|--------|
| 01 | Project Overview & Architecture | ✅ |
| 02 | Development Environment Setup | ✅ |
| 03 | Merchant Service (CRUD + Auth) | ✅ |
| 04 | Payment Service (Core Processing) | ✅ |
| 05 | API Gateway (Routing + Security) | ✅ |
| 06 | Kafka Event Streaming | ✅ |
| 07 | Redis Caching & Idempotency | ✅ |
| 08 | Ledger Service (Double-Entry) | ✅ |
| 09a | ISO 8583 Message Parsing | ✅ |
| 09b | Netty TCP Client | ✅ |
| 09c | Fraud Detection & Smart Routing | ✅ |
| 10 | Bank Simulator | ✅ |
| 11 | Settlement Service | ✅ |
| **12** | **Webhook Service** | **📍 Current** |
| 13 | Notification Service | 🔜 Next |
| 14 | Docker & Containerization | ⬜ |
| 15a | Frontend Setup | ⬜ |
| 15b | Frontend Features | ⬜ |

---

## Next Steps

In **Part 13**, we'll build the **Notification Service** — email and SMS notifications:
- Kafka consumer for notification events
- AWS SES for email delivery
- AWS SNS for SMS delivery
- Thymeleaf HTML templates for beautiful emails

---

[← Previous: Part 11 — Settlement Service](./phase4-part11-settlement-service.md) | [Next: Part 13 — Notification Service →](./phase4-part13-notification-service.md)
