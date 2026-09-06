# 🏗️ Phase 4 Part 8i: Payment Service — PaymentService Core Engine + Tests

> **"This is the beating heart of PayFlow. Every rupee passes through this 295-line file."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 8i — PaymentService Core Engine |
| **What You Build** | PaymentService.java, PaymentServiceTest.java |
| **Previous** | [Part 8h — IdempotencyService + Events](./phase4-part08h-payment-idempotency-events.md) |
| **Next** | [Part 8j — Controllers + Docker](./phase4-part08j-payment-controllers-docker.md) |

---

## 📖 Table of Contents

1. [Why This Is the Most Complex Service](#1-why-this-is-the-most-complex-service)
2. [The 6 Dependencies — What Each Does](#2-the-6-dependencies--what-each-does)
3. [Step-by-Step: authorize() — The Main Flow](#3-step-by-step-authorize--the-main-flow)
4. [Step-by-Step: capture() — Collecting Money](#4-step-by-step-capture--collecting-money)
5. [Step-by-Step: voidPayment() — Cancelling Authorization](#5-step-by-step-voidpayment--cancelling-authorization)
6. [Step-by-Step: Private Helpers](#6-step-by-step-private-helpers)
7. [Step-by-Step: PaymentServiceTest.java](#7-step-by-step-paymentservicetestjava)
8. [What You Learned](#8-what-you-learned)

---

## 1. Why This Is the Most Complex Service

| Metric | MerchantService | OrderService | **PaymentService** |
|---|---|---|---|
| Lines | ~90 | ~100 | **~295** |
| Dependencies | 2 (repo, mapper) | 2 (repo, mapper) | **6** (2 repos, orderService, feign, events, mapper) |
| External calls | 0 | 0 | **1** (Feign → routing-service → bank) |
| State machines | None | 4 states | **6 states** (CREATED→AUTHORIZED→CAPTURED/VOIDED/FAILED→REFUNDED) |
| Error handling | Simple throws | Simple throws | **Multi-layer** (bank decline, Feign timeout, routing error) |
| Events published | 0 | 0 | **3** (authorized, captured, failed) |

PaymentService orchestrates: order validation + payment creation + payment method storage + bank routing via Feign + bank response processing + state transitions + event publishing. All in one `@Transactional` method.

---

## 2. The 6 Dependencies — What Each Does

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final OrderService orderService;
    private final RoutingServiceClient routingServiceClient;
    private final EventPublisher eventPublisher;
    private final PaymentMapper paymentMapper;
```

| Dependency | Type | Purpose |
|---|---|---|
| `PaymentRepository` | @Repository | Save/read payment records |
| `PaymentMethodRepository` | @Repository | Save card/UPI/bank details |
| `OrderService` | @Service | Validate order, update order status |
| `RoutingServiceClient` | @FeignClient | HTTP call to routing-service → bank |
| `EventPublisher` | Interface | Publish Kafka events (authorized/captured/failed) |
| `PaymentMapper` | @Mapper | Convert Payment entity → PaymentResponse DTO |

**THIS IS THE FIRST SERVICE THAT CALLS ANOTHER SERVICE VIA FEIGN.** All previous services only talked to their own database.

---

## 3. Step-by-Step: authorize() — The Main Flow

This is a 7-step flow — the longest method in the project.

```java
    @Transactional
    public PaymentResponse authorize(AuthorizePaymentRequest request) {
        log.info("Authorizing payment for order: {}", request.getOrderId());
```

### Step 1: Validate the order

```java
        // 1. Validate the order
        Order order = orderService.getOrderEntity(request.getOrderId());
        validateOrderForPayment(order);
```

`getOrderEntity()` returns the raw entity (not DTO) — see Part 8g. Then `validateOrderForPayment()` checks 3 conditions (see Private Helpers below).

### Step 2: Check for existing payment

```java
        // 2. Check for existing payment on this order
        paymentRepository.findByOrderId(order.getId()).ifPresent(existing -> {
            if (existing.getStatus() != PaymentStatus.FAILED) {
                throw new PayflowException("PAYMENT_EXISTS",
                        "Order already has an active payment: " + existing.getId());
            }
        });
```

**WHY THIS CHECK?**

```
Scenario: Customer clicks "Pay" → payment AUTHORIZED → clicks "Pay" again
WITHOUT check: Second payment created → TWO charges!
WITH check: "Order already has an active payment" → rejected ✓

Exception: FAILED payments are allowed to retry.
  Customer's first card was declined (FAILED) → tries a different card → new payment OK
```

**`ifPresent(existing -> { ... })`** — if a payment exists for this order, run the lambda. If no payment exists, do nothing (first attempt).

### Step 3: Create payment record

```java
        // 3. Create payment record
        PaymentMethod method = PaymentMethod.valueOf(request.getPaymentMethod().toUpperCase());
        Payment payment = Payment.builder()
                .id(IdGenerator.generatePaymentId())
                .orderId(order.getId())
                .merchantId(order.getMerchantId())
                .amount(order.getAmount())
                .currency(order.getCurrency())
                .status(PaymentStatus.CREATED)
                .paymentMethod(method)
                .build();

        payment = paymentRepository.save(payment);
```

**`PaymentMethod.valueOf("CARD")`** — converts string to enum. If invalid → `IllegalArgumentException`.

**`amount = order.getAmount()`** — the payment amount matches the order. Partial authorization isn't supported (partial CAPTURE is, but not partial authorization).

**Status starts as `CREATED`** — will change to AUTHORIZED or FAILED after bank response.

### Step 4: Save payment method details

```java
        // 4. Save payment method details
        savePaymentMethodDetails(payment.getId(), request);
```

Saves card last4/brand/expiry OR upiId OR bankCode/bankName (see Private Helpers below).

### Step 5: Update order status

```java
        // 5. Update order status to ATTEMPTED
        orderService.updateOrderStatus(order.getId(), OrderStatus.ATTEMPTED);
```

Order: CREATED → ATTEMPTED. "We tried to pay" — even if the bank declines, the order is no longer in CREATED state.

### Step 6: Route to bank (Feign call)

```java
        // 6. Route to bank via routing-service
        try {
            Map<String, Object> routeRequest = Map.of(
                    "paymentId", payment.getId(),
                    "merchantId", order.getMerchantId(),
                    "amount", order.getAmount(),
                    "currency", order.getCurrency(),
                    "paymentMethod", method.name()
            );

            Map<String, Object> routeResponse = routingServiceClient.routePayment(routeRequest);
```

**🆕 THIS IS THE FEIGN CALL — THE FIRST INTER-SERVICE HTTP CALL**

```
PaymentService                    RoutingServiceClient (Feign)               routing-service
     │                                    │                                       │
     │  routingServiceClient              │                                       │
     │    .routePayment(request) ────────►│  POST /internal/route  ─────────────►│
     │                                    │  {paymentId, amount, ...}             │
     │                                    │                                       │ → calls bank
     │                                    │  {status: "AUTHORIZED",               │ ← bank responds
     │                                    │   authorizationCode: "AUTH123"}       │
     │  ◄──────────── result ─────────────│◄──────────── response ───────────────│
     │                                    │                                       │
```

**`Map.of(...)`** — Java immutable map. Sends payment details to routing-service which forwards to the bank.

**`Map<String, Object>`** — loosely typed response. The routing-service returns dynamic fields depending on the bank's response.

### Step 7: Process bank response

```java
            // 7. Process bank response
            String bankStatus = (String) routeResponse.getOrDefault("status", "FAILED");
            
            if ("AUTHORIZED".equalsIgnoreCase(bankStatus)) {
                payment.setStatus(PaymentStatus.AUTHORIZED);
                payment.setAuthorizationCode((String) routeResponse.get("authorizationCode"));
                payment.setBankReferenceId((String) routeResponse.get("bankReferenceId"));
                log.info("Payment authorized: {}", payment.getId());

                // Publish authorization event
                publishEvent("payment.authorized", payment);
```

**BANK APPROVED:** Status → AUTHORIZED, store the bank's codes, publish event.

```java
            } else {
                String reason = (String) routeResponse.getOrDefault("reason", "Bank declined");
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(reason);
                log.warn("Payment declined: {} - {}", payment.getId(), reason);

                // Publish failure event
                publishEvent("payment.failed", payment);

                throw new PaymentDeclinedException(
                        (String) routeResponse.getOrDefault("declineCode", "DECLINED"),
                        reason
                );
            }
```

**BANK DECLINED:** Status → FAILED, store the reason, publish event, throw `PaymentDeclinedException` (→ HTTP 402).

### Error Handling (Two Catch Blocks)

```java
        } catch (PaymentDeclinedException e) {
            paymentRepository.save(payment);
            throw e;
        } catch (Exception e) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Routing service error: " + e.getMessage());
            paymentRepository.save(payment);
            publishEvent("payment.failed", payment);
            log.error("Payment routing failed for: {}", payment.getId(), e);
            throw new PayflowException("ROUTING_ERROR",
                    "Failed to process payment: " + e.getMessage());
        }
```

**TWO CATCH BLOCKS — WHY?**

| Exception | Source | Handling |
|---|---|---|
| `PaymentDeclinedException` | Bank said "no" | Save FAILED status, re-throw (controller returns 402) |
| `Exception` (any other) | Feign timeout, network error, serialization error | Save FAILED status, wrap in PayflowException (controller returns 422) |

**KEY INSIGHT:** In BOTH cases, the payment is saved as FAILED before throwing. The `@Transactional` would normally rollback on exception — but since we explicitly save before throwing, the FAILED payment is persisted. This is critical for audit trails.

```java
        payment = paymentRepository.save(payment);
        return paymentMapper.toResponse(payment);
    }
```

Final save (for the AUTHORIZED case) and return the DTO.

---

## 4. Step-by-Step: capture() — Collecting Money

```java
    @Transactional
    public PaymentResponse capture(CapturePaymentRequest request) {
        log.info("Capturing payment: {}", request.getPaymentId());

        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", request.getPaymentId()));

        // Validate state transition
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PayflowException("INVALID_STATE",
                    String.format("Cannot capture payment in status: %s. Must be AUTHORIZED.", 
                            payment.getStatus()));
        }
```

**STATE GATE:** Only AUTHORIZED payments can be captured. All other states rejected.

```java
        // Validate capture amount
        BigDecimal captureAmount = request.getAmount() != null 
                ? request.getAmount() 
                : payment.getAmount();
```

**🆕 PARTIAL CAPTURE LOGIC:**
```
request.amount = null     → captureAmount = payment.amount (FULL capture)
request.amount = 8500.00  → captureAmount = 8500.00 (PARTIAL capture)

Hotel example: Authorized ₹10,000 → Bill is ₹8,500 → Capture ₹8,500 (partial)
```

```java
        if (captureAmount.compareTo(payment.getAmount()) > 0) {
            throw new PayflowException("INVALID_AMOUNT",
                    "Capture amount cannot exceed authorized amount");
        }
```

**Can't capture MORE than authorized.** Authorized ₹10,000 → capture ₹15,000 → REJECTED.

```java
        // Update payment
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setAmount(captureAmount);
        payment = paymentRepository.save(payment);

        // Update order to PAID
        orderService.updateOrderStatus(payment.getOrderId(), OrderStatus.PAID);

        // Publish capture event
        publishEvent("payment.captured", payment);

        log.info("Payment captured: {} for amount: {}", payment.getId(), captureAmount);
        return paymentMapper.toResponse(payment);
    }
```

**THREE THINGS HAPPEN:**
1. Payment: AUTHORIZED → CAPTURED (amount might change if partial)
2. Order: ATTEMPTED → PAID
3. Event: `payment.captured` published to Kafka

---

## 5. Step-by-Step: voidPayment() — Cancelling Authorization

```java
    @Transactional
    public PaymentResponse voidPayment(String paymentId) {
        log.info("Voiding payment: {}", paymentId);

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PayflowException("INVALID_STATE",
                    String.format("Cannot void payment in status: %s. Must be AUTHORIZED.", 
                            payment.getStatus()));
        }

        payment.setStatus(PaymentStatus.VOIDED);
        payment = paymentRepository.save(payment);

        log.info("Payment voided: {}", payment.getId());
        return paymentMapper.toResponse(payment);
    }
```

**SIMPLEST MUTATION METHOD.** Find → validate AUTHORIZED → set VOIDED → save.

**WHY NO EVENT?** Void is a cancellation — no money moved. Webhook/Settlement services don't need to know about voids (no action required on their end).

**WHY ONLY AUTHORIZED → VOIDED?**
- CAPTURED → can't void (money already moved — use REFUND instead)
- FAILED → nothing to void (authorization never happened)
- CREATED → shouldn't happen (CREATED is brief, becomes AUTHORIZED or FAILED immediately)

---

## 6. Step-by-Step: Private Helpers

### validateOrderForPayment

```java
    private void validateOrderForPayment(Order order) {
        if (order.getStatus() == OrderStatus.EXPIRED) {
            throw new PayflowException("ORDER_EXPIRED",
                    "Order has expired: " + order.getId());
        }
        if (order.getStatus() == OrderStatus.PAID) {
            throw new PayflowException("ORDER_ALREADY_PAID",
                    "Order is already paid: " + order.getId());
        }
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(Instant.now())) {
            orderService.updateOrderStatus(order.getId(), OrderStatus.EXPIRED);
            throw new PayflowException("ORDER_EXPIRED",
                    "Order has expired: " + order.getId());
        }
    }
```

**THREE CHECKS:**

| Check | Why |
|---|---|
| Status = EXPIRED? | Order already expired (by cron job) |
| Status = PAID? | Order already paid (can't pay twice) |
| expiresAt < now? | Order expired RIGHT NOW (update status + reject) |

**The third check is a REAL-TIME expiry check.** Even if the cron job hasn't run yet, we check `expiresAt` at payment time. If expired → update to EXPIRED and reject.

### savePaymentMethodDetails

```java
    private void savePaymentMethodDetails(String paymentId, AuthorizePaymentRequest request) {
        PaymentMethodEntity.PaymentMethodEntityBuilder builder = PaymentMethodEntity.builder()
                .paymentId(paymentId)
                .type(request.getPaymentMethod().toUpperCase());

        switch (request.getPaymentMethod().toUpperCase()) {
            case "CARD" -> {
                if (request.getCardNumber() != null && request.getCardNumber().length() >= 4) {
                    builder.cardLast4(request.getCardNumber()
                            .substring(request.getCardNumber().length() - 4));
                }
                builder.cardBrand(detectCardBrand(request.getCardNumber()));
                builder.cardExpiryMonth(request.getCardExpiryMonth());
                builder.cardExpiryYear(request.getCardExpiryYear());
            }
            case "UPI" -> builder.upiId(request.getUpiId());
            case "NET_BANKING" -> {
                builder.bankCode(request.getBankCode());
                builder.bankName(request.getBankName());
            }
        }

        paymentMethodRepository.save(builder.build());
    }
```

**🆕 JAVA SWITCH EXPRESSION (Java 17+):**
```java
case "CARD" -> { ... }   // Arrow syntax — no break needed (Java 14+)
case "CARD":  { ... break; }  // Old syntax — needs break
```

**CARD LAST 4 EXTRACTION:**
```java
"4111111111111111".substring("4111111111111111".length() - 4)
// → "1111" (last 4 characters)
```

**🔒 THE FULL CARD NUMBER IS NEVER SAVED.** Only `last4` is stored. The full number was sent to the bank via Feign (in the routing request) and immediately discarded.

### detectCardBrand

```java
    private String detectCardBrand(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty()) return "UNKNOWN";
        if (cardNumber.startsWith("4")) return "VISA";
        if (cardNumber.startsWith("5")) return "MASTERCARD";
        if (cardNumber.startsWith("6")) return "RUPAY";
        if (cardNumber.startsWith("3")) return "AMEX";
        return "UNKNOWN";
    }
```

**SIMPLIFIED BIN (Bank Identification Number) DETECTION:**

| First Digit | Network | Real-World Range |
|---|---|---|
| 4 | Visa | 4xxx xxxx xxxx xxxx |
| 5 | Mastercard | 51xx-55xx, 2221-2720 |
| 6 | RuPay | 60xx, 65xx, 81xx |
| 3 | American Express | 34xx, 37xx |

This is a simplified version. Production systems use full BIN databases (first 6-8 digits) for accurate detection.

### publishEvent

```java
    private void publishEvent(String topic, Payment payment) {
        try {
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
        } catch (Exception e) {
            log.error("Failed to publish event for payment: {}", payment.getId(), e);
        }
    }
```

Same fire-and-forget pattern as RefundService. Kafka failure doesn't break the payment.

---

## 7. Step-by-Step: PaymentServiceTest.java

### 6 Dependencies Mocked

```java
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private OrderService orderService;
    @Mock private RoutingServiceClient routingServiceClient;
    @Mock private EventPublisher eventPublisher;
    @Mock private PaymentMapper paymentMapper;

    @InjectMocks private PaymentService paymentService;
```

**THE MOST MOCKED SERVICE SO FAR.** 6 mocks because PaymentService has 6 dependencies.

### Test: authorize success

```java
    @Test
    @DisplayName("authorize - should create payment and route to bank successfully")
    void authorize_Success() {
        when(orderService.getOrderEntity("order-001")).thenReturn(testOrder);
        when(paymentRepository.findByOrderId("order-001")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            if (p.getId() == null) p.setId("pay-001");
            return p;
        });
        when(routingServiceClient.routePayment(any(Map.class))).thenReturn(Map.of(
                "status", "AUTHORIZED",
                "authorizationCode", "AUTH123",
                "bankReferenceId", "BANK-REF-001"
        ));
        when(paymentMapper.toResponse(any(Payment.class))).thenReturn(paymentResponse);

        PaymentResponse result = paymentService.authorize(authorizeRequest);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("pay-001");
        assertThat(result.getStatus()).isEqualTo("AUTHORIZED");

        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        verify(orderService).updateOrderStatus("order-001", OrderStatus.ATTEMPTED);
    }
```

**KEY MOCK: `routingServiceClient.routePayment(...)`**

This mocks the FEIGN CALL. Instead of actually calling routing-service over HTTP, it returns a fake bank response:
```java
Map.of("status", "AUTHORIZED", "authorizationCode", "AUTH123", "bankReferenceId", "BANK-REF-001")
```

**`atLeastOnce()`** — payment is saved multiple times (once at creation, once after bank response). `atLeastOnce()` verifies it was saved at least once without caring about exact count.

### Test: capture success

```java
    @Test
    @DisplayName("capture - should change payment status from AUTHORIZED to CAPTURED")
    void capture_ChangesStatus() {
        CapturePaymentRequest captureRequest = new CapturePaymentRequest();
        captureRequest.setPaymentId("pay-001");
        captureRequest.setAmount(new BigDecimal("10000"));

        when(paymentRepository.findById("pay-001")).thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse capturedResponse = new PaymentResponse();
        capturedResponse.setId("pay-001");
        capturedResponse.setStatus("CAPTURED");
        when(paymentMapper.toResponse(any(Payment.class))).thenReturn(capturedResponse);

        PaymentResponse result = paymentService.capture(captureRequest);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("CAPTURED");
        verify(orderService).updateOrderStatus("order-001", OrderStatus.PAID);
    }
```

**VERIFIES:** After capture, `orderService.updateOrderStatus` is called with `OrderStatus.PAID` — confirming the order state also changes.

### Test: capture invalid state

```java
    @Test
    @DisplayName("capture - should throw PayflowException for invalid state transition")
    void capture_InvalidState_Throws() {
        Payment createdPayment = Payment.builder()
                .id("pay-002").orderId("order-002").merchantId("merchant-001")
                .amount(new BigDecimal("5000")).currency("INR")
                .status(PaymentStatus.CAPTURED)  // Already captured!
                .paymentMethod(PaymentMethod.CARD)
                .build();

        CapturePaymentRequest captureRequest = new CapturePaymentRequest();
        captureRequest.setPaymentId("pay-002");

        when(paymentRepository.findById("pay-002")).thenReturn(Optional.of(createdPayment));

        assertThatThrownBy(() -> paymentService.capture(captureRequest))
                .isInstanceOf(PayflowException.class)
                .hasMessageContaining("INVALID_STATE");
    }
```

**TESTS THE STATE GUARD:** Can't capture an already-captured payment. The test constructs a payment with `CAPTURED` status and verifies the method throws.

### Test: void success

```java
    @Test
    @DisplayName("voidPayment - should change status from AUTHORIZED to VOIDED")
    void voidPayment_Success() {
        when(paymentRepository.findById("pay-001")).thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse voidedResponse = new PaymentResponse();
        voidedResponse.setId("pay-001");
        voidedResponse.setStatus("VOIDED");
        when(paymentMapper.toResponse(any(Payment.class))).thenReturn(voidedResponse);

        PaymentResponse result = paymentService.voidPayment("pay-001");

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("VOIDED");
    }
```

`testPayment` has status AUTHORIZED (set in `@BeforeEach`), so void succeeds.

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **6 dependencies** | PaymentService orchestrates repos, orderService, Feign, events, mapper — the most complex service |
| 2 | **7-step authorize flow** | Validate → check existing → create → save method → update order → Feign → process response |
| 3 | **Feign inter-service call** | `routingServiceClient.routePayment(map)` — HTTP POST to routing-service, returns bank response |
| 4 | **Map<String, Object> for dynamic responses** | Bank responses have variable fields — loosely typed map handles this |
| 5 | **Two catch blocks** | PaymentDeclinedException (bank said no) vs Exception (Feign/network error) — different handling |
| 6 | **Save before throw** | FAILED payment is persisted BEFORE throwing exception (audit trail requirement) |
| 7 | **Partial capture** | `request.amount != null ? request.amount : payment.amount` — null=full, value=partial |
| 8 | **State gate pattern** | `if (status != AUTHORIZED) throw` — only specific states can transition |
| 9 | **Existing payment check** | `ifPresent(existing -> { if (status != FAILED) throw })` — prevent double payment, allow retry |
| 10 | **Real-time expiry check** | Even if cron hasn't run, `expiresAt.isBefore(now)` catches expired orders |
| 11 | **Java switch arrows** | `case "CARD" -> { ... }` — Java 14+ arrow syntax, no break needed |
| 12 | **Card last4 extraction** | `substring(length - 4)` — PCI compliant, never store full number |
| 13 | **Card brand detection** | Simple prefix check: 4=VISA, 5=MC, 6=RuPay, 3=AMEX |
| 14 | **Order status cascade** | Authorize → order ATTEMPTED. Capture → order PAID. |
| 15 | **Mocking Feign client** | `when(routingServiceClient.routePayment(...)).thenReturn(Map.of(...))` — fake bank response |
| 16 | **atLeastOnce()** | Verify method called 1+ times (when exact count varies) |

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
| [Part 8h](./phase4-part08h-payment-idempotency-events.md) | IdempotencyService + Events |
| **Part 8i** | **PaymentService Core Engine** (You are here) |
| [Part 8j](./phase4-part08j-payment-controllers-docker.md) | Controllers + Docker |
| [Part 8k](./phase4-part08k-payment-connections-flows.md) | Connections & Flows |

---

*Next: [Part 8j — Controllers + ExceptionHandler + FeignClients + Dockerfile + curl](./phase4-part08j-payment-controllers-docker.md) →*
