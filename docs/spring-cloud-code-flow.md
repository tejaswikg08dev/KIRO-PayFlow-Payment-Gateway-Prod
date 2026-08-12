# Spring Cloud Code Flow

## Overview

Traces a complete request from client through Spring Cloud Gateway filter chain, downstream service processing, and back to the client response.

## Complete Request Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         REQUEST LIFECYCLE                                  │
└──────────────────────────────────────────────────────────────────────────┘

Client (HTTPS POST /api/v1/orders)
    │
    ▼
┌─── API GATEWAY (Spring Cloud Gateway) ──────────────────────────────────┐
│                                                                          │
│  1. NettyRoutingFilter (accepts TCP connection)                          │
│  2. RequestLoggingFilter (log method, path, generate correlation ID)     │
│  3. RateLimitFilter (check Redis counter, reject if over limit)          │
│  4. ApiKeyValidationFilter:                                              │
│     a. Extract X-Api-Key header                                          │
│     b. SHA-256 hash the key                                              │
│     c. Check Redis cache for merchant ID                                 │
│     d. If miss → call Merchant Service /internal/validate-key            │
│     e. Add X-Merchant-Id header to request                               │
│  5. RoutePredicateHandlerMapping (match path → route config)             │
│  6. LoadBalancerClientFilter (select service instance)                    │
│  7. NettyRoutingFilter (forward request to downstream)                   │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
    │
    │ HTTP (internal network)
    ▼
┌─── PAYMENT SERVICE ─────────────────────────────────────────────────────┐
│                                                                          │
│  8. DispatcherServlet (Spring MVC)                                       │
│  9. CorrelationIdFilter (extract/generate correlation ID, set MDC)       │
│  10. SecurityFilterChain (validate request, no auth needed - gateway did)│
│  11. HandlerMapping → OrderController.createOrder()                      │
│                                                                          │
│  ┌── OrderController ──────────────────────────────────────────────┐    │
│  │  12. @Valid validation on request body                           │    │
│  │  13. Extract X-Merchant-Id from header                           │    │
│  │  14. Call orderService.createOrder(merchantId, request)          │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  ┌── OrderService ─────────────────────────────────────────────────┐    │
│  │  15. Check idempotency key in Redis                              │    │
│  │  16. If cached → return immediately                              │    │
│  │  17. Validate merchant via Feign → Merchant Service              │    │
│  │  18. Build Order entity                                          │    │
│  │  19. @Transactional → save to PostgreSQL                         │    │
│  │  20. Cache response in Redis (idempotency, 24h TTL)              │    │
│  │  21. Return OrderResponse                                        │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  ┌── Response Pipeline ────────────────────────────────────────────┐    │
│  │  22. OrderController wraps in ApiResponse                        │    │
│  │  23. Jackson serializes to JSON                                  │    │
│  │  24. Set HTTP 201 Created                                        │    │
│  │  25. Response flows back through filter chain                    │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
    │
    │ HTTP Response
    ▼
┌─── API GATEWAY (Response Path) ─────────────────────────────────────────┐
│                                                                          │
│  26. NettyWriteResponseFilter (write response to client)                 │
│  27. ResponseLoggingFilter (log status code, latency)                    │
│  28. Add X-Correlation-Id header to response                             │
│  29. Add X-RateLimit-Remaining header                                    │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
    │
    ▼
Client receives HTTP 201 + JSON body
```

## Payment Processing Flow (Detailed)

```
Client POST /api/v1/orders/{id}/pay
    │
    ▼ (same gateway flow as above)
    │
┌─── PaymentController.processPayment() ──────────────────────────────────┐
│                                                                          │
│  1. Validate request body (card details)                                 │
│  2. paymentService.processPayment(orderId, request)                      │
│     │                                                                    │
│     ├─ 3. Load order from DB                                            │
│     ├─ 4. Validate state == CREATED                                     │
│     ├─ 5. Check not expired                                             │
│     ├─ 6. Update status → PROCESSING (save)                            │
│     │                                                                    │
│     ├─ 7. Build RoutingRequest                                          │
│     ├─ 8. Feign call → Routing Service                                  │
│     │     │                                                              │
│     │     ├─ 9. FraudDetectionService.checkFraud()                      │
│     │     │     ├─ VelocityCheckRule (Redis counter)                    │
│     │     │     └─ AmountLimitRule                                      │
│     │     │                                                              │
│     │     ├─ 10. SmartRouter.selectAcquirer()                           │
│     │     │                                                              │
│     │     ├─ 11. ISO8583MessageBuilder.buildAuthRequest()               │
│     │     │                                                              │
│     │     ├─ 12. BankTcpClient.sendMessage() [Netty]                    │
│     │     │     ├─ Connect TCP to bank:9090                             │
│     │     │     ├─ Encode ISO 8583 → bytes                              │
│     │     │     ├─ Send with length header                              │
│     │     │     ├─ Await response (CompletableFuture)                   │
│     │     │     └─ Decode response bytes → ISO8583Message               │
│     │     │                                                              │
│     │     └─ 13. Return RoutingResponse                                 │
│     │                                                                    │
│     ├─ 14. Create Transaction entity (save)                             │
│     ├─ 15. Update order status (AUTHORIZED or FAILED)                   │
│     ├─ 16. KafkaEventPublisher.publish("payment.authorized", event)     │
│     │     └─ Async: kafkaTemplate.send(topic, key, event)               │
│     │                                                                    │
│     └─ 17. Return PaymentResponse                                       │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

## Error Handling Flow

```
Exception thrown at any layer
    │
    ▼
┌─── GlobalExceptionHandler (@ControllerAdvice) ──┐
│                                                   │
│  @ExceptionHandler(OrderNotFoundException.class)  │
│  → HTTP 404 + ApiResponse{success:false}         │
│                                                   │
│  @ExceptionHandler(InvalidStateTransition.class)  │
│  → HTTP 409 + ApiResponse{success:false}         │
│                                                   │
│  @ExceptionHandler(MethodArgumentNotValid.class)  │
│  → HTTP 400 + field-level errors                 │
│                                                   │
│  @ExceptionHandler(Exception.class)               │
│  → HTTP 500 + generic message (log full error)   │
└───────────────────────────────────────────────────┘
```

## Key Spring Boot Auto-Configuration

| Component | Auto-Config | What It Sets Up |
|-----------|-------------|-----------------|
| DataSource | `DataSourceAutoConfiguration` | HikariCP connection pool |
| JPA | `HibernateJpaAutoConfiguration` | EntityManagerFactory |
| Kafka | `KafkaAutoConfiguration` | KafkaTemplate, ConsumerFactory |
| Redis | `RedisAutoConfiguration` | RedisTemplate, connection |
| Jackson | `JacksonAutoConfiguration` | ObjectMapper with Java 8 time |
| Actuator | `ActuatorAutoConfiguration` | Health, metrics endpoints |

## Feign Client Request Flow

```
PaymentService → FeignClient Interface
    │
    ├── RequestInterceptor (add correlation ID header)
    ├── Encoder (Jackson → JSON body)
    ├── Target URL resolution
    ├── Resilience4j CircuitBreaker check
    │     ├── CLOSED: proceed normally
    │     ├── OPEN: throw CallNotPermittedException
    │     └── HALF_OPEN: allow limited calls
    ├── HTTP request via OkHttp/Apache client
    ├── Retry (if configured, on 5xx/timeout)
    ├── Decoder (JSON → Java object)
    └── Return response or throw FeignException
```
