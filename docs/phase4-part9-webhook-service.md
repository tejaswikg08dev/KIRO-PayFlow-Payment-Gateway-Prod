# Phase 4 Part 9: Webhook Service Implementation

## Overview

The Webhook Service delivers real-time event notifications to merchant-configured URLs. It consumes Kafka events, signs payloads with HMAC-SHA256, implements exponential backoff retries, and tracks delivery status in DynamoDB.

## Architecture

```
┌─────────────┐     ┌──────────────────────────────────┐     ┌────────────────┐
│   KAFKA     │     │       WEBHOOK SERVICE             │     │   MERCHANT     │
│             │     │                                    │     │   SERVER       │
│ payment.*   │────▶│ Consumer → Builder → Signer →     │────▶│                │
│ refund.*    │     │         → HTTP POST               │     │ POST /webhook  │
│ settlement.*│     │         → Retry (if failed)       │     │                │
└─────────────┘     └──────────────┬───────────────────┘     └────────────────┘
                                   │
                            ┌──────▼──────┐
                            │  DynamoDB   │
                            │  Delivery   │
                            │  Tracking   │
                            └─────────────┘
```

## Kafka Consumer

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventConsumer {

    private final WebhookDeliveryService deliveryService;

    @KafkaListener(
        topics = {
            "payment.created",
            "payment.authorized",
            "payment.captured",
            "payment.failed",
            "refund.initiated",
            "refund.completed",
            "settlement.completed"
        },
        groupId = "webhook-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleEvent(
            @Payload PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {

        log.info("Received event: topic={}, orderId={}, merchantId={}",
            topic, event.orderId(), event.merchantId());

        try {
            deliveryService.processEvent(topic, event);
        } catch (Exception e) {
            log.error("Failed to process webhook event: topic={}, " +
                "orderId={}", topic, event.orderId(), e);
            // DLQ handling - don't throw to avoid reprocessing
        }
    }
}
```

## HMAC Signature Generation

```java
@Component
@Slf4j
public class WebhookSigner {

    /**
     * Webhook signature format:
     * Header: X-PayFlow-Signature
     * Value: t=<timestamp>,v1=<hmac_sha256>
     *
     * Signing string: <timestamp>.<payload_json>
     *
     * Merchants verify by:
     * 1. Extract timestamp and signature from header
     * 2. Reconstruct signing string: timestamp + "." + raw body
     * 3. Compute HMAC-SHA256 with their webhook secret
     * 4. Compare signatures (timing-safe)
     * 5. Check timestamp is within tolerance (5 minutes)
     */
    public String generateSignature(String payload, String secret) {
        long timestamp = Instant.now().getEpochSecond();
        String signingString = timestamp + "." + payload;

        String hmac = computeHmacSha256(signingString, secret);

        return "t=" + timestamp + ",v1=" + hmac;
    }

    private String computeHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(
                data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    /**
     * Verification helper (for documentation/SDK)
     */
    public boolean verifySignature(String payload, String header,
                                    String secret, long toleranceSeconds) {
        String[] parts = header.split(",");
        long timestamp = Long.parseLong(parts[0].replace("t=", ""));
        String receivedSig = parts[1].replace("v1=", "");

        // Check timestamp tolerance
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > toleranceSeconds) {
            return false;
        }

        // Recompute and compare
        String signingString = timestamp + "." + payload;
        String expectedSig = computeHmacSha256(signingString, secret);

        return MessageDigest.isEqual(
            expectedSig.getBytes(), receivedSig.getBytes());
    }
}
```

## Exponential Backoff Retry

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryService {

    private final WebhookConfigClient webhookConfigClient;
    private final WebhookSigner signer;
    private final WebhookDeliveryRepository deliveryRepo;
    private final WebClient webClient;

    private static final int MAX_ATTEMPTS = 6;
    private static final int[] RETRY_DELAYS_SECONDS = {
        30,     // Attempt 2: 30 seconds
        120,    // Attempt 3: 2 minutes
        600,    // Attempt 4: 10 minutes
        3600,   // Attempt 5: 1 hour
        14400   // Attempt 6: 4 hours
    };

    public void processEvent(String topic, PaymentEvent event) {
        // Get merchant's webhook configs
        List<WebhookConfig> configs = webhookConfigClient
            .getActiveWebhooks(UUID.fromString(event.merchantId()));

        for (WebhookConfig config : configs) {
            // Check if this webhook is subscribed to this event type
            String eventType = topic;  // e.g., "payment.captured"
            if (!config.getEvents().contains(eventType)) {
                continue;
            }

            // Build webhook payload
            WebhookPayload payload = buildPayload(eventType, event);
            String payloadJson = objectMapper.writeValueAsString(payload);

            // Sign the payload
            String signature = signer.generateSignature(
                payloadJson, config.getSecret());

            // Attempt delivery
            deliverWebhook(config, payloadJson, signature, 1);
        }
    }

    private void deliverWebhook(WebhookConfig config, String payload,
                                 String signature, int attempt) {
        WebhookDelivery delivery = WebhookDelivery.builder()
            .webhookConfigId(config.getId().toString())
            .merchantId(config.getMerchantId().toString())
            .url(config.getUrl())
            .payload(payload)
            .attempt(attempt)
            .status("PENDING")
            .createdAt(Instant.now())
            .build();

        try {
            // HTTP POST to merchant's URL
            WebClient.ResponseSpec response = webClient.post()
                .uri(config.getUrl())
                .header("Content-Type", "application/json")
                .header("X-PayFlow-Signature", signature)
                .header("X-PayFlow-Event", extractEventType(payload))
                .header("User-Agent", "PayFlow-Webhook/1.0")
                .bodyValue(payload)
                .retrieve();

            HttpStatusCode statusCode = response.toBodilessEntity()
                .block(Duration.ofSeconds(10))
                .getStatusCode();

            if (statusCode.is2xxSuccessful()) {
                delivery.setStatus("DELIVERED");
                delivery.setResponseCode(statusCode.value());
                delivery.setDeliveredAt(Instant.now());
                log.info("Webhook delivered: url={}, attempt={}",
                    config.getUrl(), attempt);
            } else {
                handleFailure(config, payload, signature,
                    attempt, delivery, statusCode.value(), null);
            }

        } catch (Exception e) {
            handleFailure(config, payload, signature,
                attempt, delivery, 0, e.getMessage());
        }

        deliveryRepo.save(delivery);
    }

    private void handleFailure(WebhookConfig config, String payload,
                                String signature, int attempt,
                                WebhookDelivery delivery,
                                int responseCode, String error) {
        delivery.setStatus("FAILED");
        delivery.setResponseCode(responseCode);
        delivery.setError(error);

        if (attempt < MAX_ATTEMPTS) {
            int delaySeconds = RETRY_DELAYS_SECONDS[attempt - 1];
            log.warn("Webhook delivery failed: url={}, attempt={}, " +
                "next retry in {}s", config.getUrl(), attempt, delaySeconds);

            // Schedule retry
            scheduleRetry(config, payload, signature,
                attempt + 1, delaySeconds);
        } else {
            log.error("Webhook delivery exhausted all retries: url={}, " +
                "merchantId={}", config.getUrl(), config.getMerchantId());
            delivery.setStatus("EXHAUSTED");
            // Could notify merchant via email about failed webhooks
        }
    }

    @Async
    private void scheduleRetry(WebhookConfig config, String payload,
                                String signature, int attempt,
                                int delaySeconds) {
        try {
            Thread.sleep(delaySeconds * 1000L);
            deliverWebhook(config, payload, signature, attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

## DynamoDB Delivery Tracking

```java
@DynamoDbBean
public class WebhookDelivery {

    private String id;
    private String webhookConfigId;
    private String merchantId;
    private String url;
    private String payload;
    private int attempt;
    private String status;       // PENDING, DELIVERED, FAILED, EXHAUSTED
    private int responseCode;
    private String error;
    private Instant createdAt;
    private Instant deliveredAt;
    private long ttl;            // Auto-delete after 30 days

    @DynamoDbPartitionKey
    public String getId() { return id; }

    @DynamoDbSortKey
    public Instant getCreatedAt() { return createdAt; }

    @DynamoDbSecondaryPartitionKey(indexNames = "merchant-index")
    public String getMerchantId() { return merchantId; }
}
```

### DynamoDB Table Design

| Attribute | Type | Key |
|-----------|------|-----|
| id | String | Partition Key |
| createdAt | Number (epoch) | Sort Key |
| merchantId | String | GSI Partition Key |
| webhookConfigId | String | - |
| status | String | - |
| attempt | Number | - |
| payload | String | - |
| responseCode | Number | - |
| ttl | Number | TTL attribute (30 days) |

## Webhook Payload Format

```json
{
  "id": "evt_abc123def456",
  "event": "payment.captured",
  "created_at": "2024-01-15T10:30:00Z",
  "data": {
    "order_id": "ord_xyz789",
    "transaction_id": "txn_abc456",
    "amount": 50000,
    "currency": "INR",
    "status": "CAPTURED",
    "payment_method": "card",
    "card_last4": "1111",
    "card_network": "visa",
    "rrn": "240115123456",
    "customer_email": "buyer@example.com"
  }
}
```

## Retry Schedule Visualization

```
Attempt 1: Immediately
    │
    │ ← 30 seconds
    ▼
Attempt 2: T + 30s
    │
    │ ← 2 minutes
    ▼
Attempt 3: T + 2m 30s
    │
    │ ← 10 minutes
    ▼
Attempt 4: T + 12m 30s
    │
    │ ← 1 hour
    ▼
Attempt 5: T + 1h 12m 30s
    │
    │ ← 4 hours
    ▼
Attempt 6: T + 5h 12m 30s  (FINAL)
    │
    ▼
EXHAUSTED → Alert merchant
```

## Configuration

```yaml
spring:
  application:
    name: webhook-service
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    consumer:
      group-id: webhook-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.payflow.*"

aws:
  dynamodb:
    endpoint: ${DYNAMODB_ENDPOINT:http://localhost:8000}
    region: ${AWS_REGION:ap-south-1}
    table-name: webhook-deliveries

webhook:
  max-attempts: 6
  timeout-seconds: 10
  retry-delays: 30,120,600,3600,14400

server:
  port: 8087
```
