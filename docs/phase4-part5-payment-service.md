# Phase 4 Part 5: Payment Service Implementation

## Overview

The Payment Service is the core orchestrator of PayFlow. It manages order creation, payment state machine transitions, idempotency enforcement, and publishes events to Kafka for downstream consumers.

## Payment State Machine

```
                    ┌─────────┐
                    │ CREATED │
                    └────┬────┘
                         │ submit payment
                    ┌────▼────┐
                    │PROCESSING│
                    └────┬────┘
                    ┌────┴────┐
               success│        │failure
              ┌───────▼──┐  ┌──▼──────┐
              │AUTHORIZED │  │ FAILED  │
              └─────┬─────┘  └─────────┘
                    │ capture
              ┌─────▼─────┐
              │ CAPTURED   │
              └─────┬─────┘
              ┌─────┴─────┐
         full │           │ partial
        ┌─────▼────┐  ┌───▼──────────────┐
        │ REFUNDED │  │PARTIALLY_REFUNDED│
        └──────────┘  └──────────────────┘

  From AUTHORIZED: can also → VOIDED (void before capture)
  From CAPTURED: → SETTLED (after settlement batch)
```

## Order Service (Create + Get)

```java
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final IdempotencyService idempotencyService;
    private final MerchantServiceClient merchantClient;

    public OrderResponse createOrder(UUID merchantId,
                                     CreateOrderRequest request) {
        // Idempotency check
        if (request.idempotencyKey() != null) {
            Optional<OrderResponse> cached = idempotencyService
                .getCachedResponse(request.idempotencyKey());
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        // Validate merchant exists and is active
        merchantClient.validateMerchant(merchantId);

        // Validate amount
        if (request.amount() <= 0) {
            throw new InvalidAmountException(request.amount());
        }
        if (request.amount() > 10_000_00) {  // ₹10,000 max per txn
            throw new AmountExceedsLimitException(request.amount());
        }

        Order order = Order.builder()
            .merchantId(merchantId)
            .amount(request.amount())
            .currency(request.currency() != null ? request.currency() : "INR")
            .status(OrderStatus.CREATED)
            .description(request.description())
            .receiptNumber(request.receiptNumber())
            .customerEmail(request.customerEmail())
            .customerPhone(request.customerPhone())
            .callbackUrl(request.callbackUrl())
            .idempotencyKey(request.idempotencyKey())
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusMinutes(30))
            .build();

        order = orderRepository.save(order);

        OrderResponse response = OrderResponse.from(order);

        // Cache for idempotency
        if (request.idempotencyKey() != null) {
            idempotencyService.cacheResponse(
                request.idempotencyKey(), response);
        }

        return response;
    }

    public OrderResponse getOrder(UUID merchantId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!order.getMerchantId().equals(merchantId)) {
            throw new UnauthorizedAccessException();
        }

        return OrderResponse.from(order);
    }

    public PageResponse<OrderResponse> listOrders(UUID merchantId,
                                                   Pageable pageable) {
        Page<Order> page = orderRepository
            .findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);

        return PageResponse.from(page.map(OrderResponse::from));
    }
}
```

## Idempotency Service (Redis-Backed)

```java
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * Idempotency Strategy:
     * 1. Client sends X-Idempotency-Key header
     * 2. Before processing, check Redis for existing response
     * 3. If found → return cached response (no duplicate processing)
     * 4. If not found → process request, cache response
     * 5. Key expires after 24 hours
     */
    public <T> Optional<T> getCachedResponse(String idempotencyKey,
                                              Class<T> type) {
        String key = KEY_PREFIX + idempotencyKey;
        String cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            return Optional.of(objectMapper.readValue(cached, type));
        }
        return Optional.empty();
    }

    public <T> void cacheResponse(String idempotencyKey, T response) {
        String key = KEY_PREFIX + idempotencyKey;
        String json = objectMapper.writeValueAsString(response);
        redisTemplate.opsForValue().set(key, json, TTL);
    }

    /**
     * Distributed lock to prevent race conditions.
     * Two identical requests arriving simultaneously:
     * - First request acquires lock → processes
     * - Second request sees lock → waits → gets cached response
     */
    public boolean acquireLock(String idempotencyKey) {
        String lockKey = KEY_PREFIX + "lock:" + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "PROCESSING", Duration.ofSeconds(30));
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseLock(String idempotencyKey) {
        String lockKey = KEY_PREFIX + "lock:" + idempotencyKey;
        redisTemplate.delete(lockKey);
    }
}
```

## Payment Processing Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final OrderRepository orderRepository;
    private final TransactionRepository transactionRepository;
    private final RoutingServiceClient routingClient;
    private final KafkaEventPublisher eventPublisher;
    private final IdempotencyService idempotencyService;

    @Transactional
    public PaymentResponse processPayment(UUID orderId,
                                          PaymentRequest request) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        // Validate state transition
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new InvalidStateTransitionException(
                order.getStatus(), "PROCESSING");
        }

        // Check if order expired
        if (order.getExpiresAt().isBefore(LocalDateTime.now())) {
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            throw new OrderExpiredException(orderId);
        }

        // Update to PROCESSING
        order.setStatus(OrderStatus.PROCESSING);
        orderRepository.save(order);

        try {
            // Call Routing Service (fraud check + bank communication)
            RoutingRequest routingRequest = RoutingRequest.builder()
                .orderId(orderId.toString())
                .merchantId(order.getMerchantId().toString())
                .amount(order.getAmount())
                .currency(order.getCurrency())
                .paymentMethod(request.paymentMethod())
                .card(request.card())
                .build();

            RoutingResponse bankResponse = routingClient
                .processPayment(routingRequest);

            // Create transaction record
            Transaction transaction = Transaction.builder()
                .order(order)
                .type(TransactionType.AUTHORIZATION)
                .amount(order.getAmount())
                .status(bankResponse.approved() ?
                    TransactionStatus.SUCCESS : TransactionStatus.FAILED)
                .rrn(bankResponse.rrn())
                .authCode(bankResponse.authCode())
                .responseCode(bankResponse.responseCode())
                .paymentMethod(PaymentMethod.valueOf(
                    request.paymentMethod().toUpperCase()))
                .cardLast4(request.card() != null ?
                    request.card().number().substring(
                        request.card().number().length() - 4) : null)
                .createdAt(LocalDateTime.now())
                .build();

            transactionRepository.save(transaction);

            // Update order status
            if (bankResponse.approved()) {
                order.setStatus(OrderStatus.AUTHORIZED);
                eventPublisher.publish("payment.authorized",
                    PaymentEvent.from(order, transaction));
            } else {
                order.setStatus(OrderStatus.FAILED);
                eventPublisher.publish("payment.failed",
                    PaymentEvent.from(order, transaction));
            }
            orderRepository.save(order);

            return PaymentResponse.from(order, transaction);

        } catch (Exception e) {
            log.error("Payment processing failed for order: {}", orderId, e);
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            eventPublisher.publish("payment.failed",
                PaymentEvent.failed(order, e.getMessage()));
            throw new PaymentProcessingException(orderId, e);
        }
    }

    @Transactional
    public PaymentResponse capturePayment(UUID orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.AUTHORIZED) {
            throw new InvalidStateTransitionException(
                order.getStatus(), "CAPTURED");
        }

        order.setStatus(OrderStatus.CAPTURED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        // Create capture transaction
        Transaction capture = Transaction.builder()
            .order(order)
            .type(TransactionType.CAPTURE)
            .amount(order.getAmount())
            .status(TransactionStatus.SUCCESS)
            .createdAt(LocalDateTime.now())
            .build();
        transactionRepository.save(capture);

        eventPublisher.publish("payment.captured",
            PaymentEvent.from(order, capture));

        return PaymentResponse.from(order, capture);
    }
}
```

## Kafka Event Publishing

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaEventPublisher {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    public void publish(String topic, PaymentEvent event) {
        try {
            kafkaTemplate.send(topic, event.orderId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish event to topic: {} " +
                            "for order: {}", topic, event.orderId(), ex);
                        // Fallback: store in outbox table for retry
                        storeInOutbox(topic, event);
                    } else {
                        log.info("Published event to topic: {} partition: {} " +
                            "offset: {}", topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                    }
                });
        } catch (Exception e) {
            log.error("Kafka send failed for topic: {}", topic, e);
            storeInOutbox(topic, event);
        }
    }

    private void storeInOutbox(String topic, PaymentEvent event) {
        // Transactional outbox pattern fallback
        // Store event in DB, separate scheduler retries publishing
    }
}
```

## Feign Clients

```java
@FeignClient(name = "routing-service", url = "${services.routing.url}")
public interface RoutingServiceClient {

    @PostMapping("/internal/process")
    RoutingResponse processPayment(@RequestBody RoutingRequest request);

    @PostMapping("/internal/refund")
    RoutingResponse processRefund(@RequestBody RefundRoutingRequest request);
}

@FeignClient(name = "merchant-service", url = "${services.merchant.url}")
public interface MerchantServiceClient {

    @GetMapping("/internal/merchants/{id}")
    MerchantResponse validateMerchant(@PathVariable("id") UUID merchantId);

    @GetMapping("/internal/merchants/{id}/fees")
    List<FeeConfigResponse> getMerchantFees(
        @PathVariable("id") UUID merchantId,
        @RequestParam("paymentMethod") String paymentMethod);
}
```

## Event Model

```java
public record PaymentEvent(
    String orderId,
    String merchantId,
    Long amount,
    String currency,
    String status,
    String paymentMethod,
    String rrn,
    String transactionId,
    String customerEmail,
    String customerPhone,
    String timestamp
) {
    public static PaymentEvent from(Order order, Transaction txn) {
        return new PaymentEvent(
            order.getId().toString(),
            order.getMerchantId().toString(),
            order.getAmount(),
            order.getCurrency(),
            order.getStatus().name(),
            txn.getPaymentMethod() != null ? txn.getPaymentMethod().name() : null,
            txn.getRrn(),
            txn.getId().toString(),
            order.getCustomerEmail(),
            order.getCustomerPhone(),
            LocalDateTime.now().toString()
        );
    }
}
```

## Configuration

```yaml
spring:
  application:
    name: payment-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/payflow_payment
    username: ${DB_USERNAME:payflow}
    password: ${DB_PASSWORD:payflow123}
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
  redis:
    host: ${REDIS_HOST:localhost}
    port: 6379

services:
  routing:
    url: http://${ROUTING_HOST:localhost}:8084
  merchant:
    url: http://${MERCHANT_HOST:localhost}:8083

server:
  port: 8082
```
