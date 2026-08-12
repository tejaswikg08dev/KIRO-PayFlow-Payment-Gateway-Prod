# Phase 5 Part 2: Unit Tests

## Overview

Service layer unit tests using Mockito. These tests are fast (no Spring context), test business logic in isolation, and form the base of the testing pyramid.

## Payment Service Unit Tests

```java
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private RoutingServiceClient routingClient;
    @Mock private KafkaEventPublisher eventPublisher;
    @Mock private IdempotencyService idempotencyService;

    @InjectMocks private PaymentService paymentService;

    private Order testOrder;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        testOrder = TestDataFactory.createOrder(UUID.randomUUID());
        testOrder.setId(orderId);
    }

    @Test
    void processPayment_whenOrderCreated_shouldAuthorize() {
        // Given
        testOrder.setStatus(OrderStatus.CREATED);
        PaymentRequest request = TestDataFactory.createCardPaymentRequest();
        RoutingResponse bankResponse = new RoutingResponse(
            true, "240115123456", "A12345", "00", "APPROVED");

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(routingClient.processPayment(any())).thenReturn(bankResponse);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        PaymentResponse response = paymentService.processPayment(orderId, request);

        // Then
        assertThat(response.status()).isEqualTo("AUTHORIZED");
        assertThat(response.rrn()).isEqualTo("240115123456");
        verify(orderRepository, times(2)).save(any()); // PROCESSING then AUTHORIZED
        verify(eventPublisher).publish(eq("payment.authorized"), any());
    }

    @Test
    void processPayment_whenBankDeclines_shouldSetFailed() {
        // Given
        testOrder.setStatus(OrderStatus.CREATED);
        PaymentRequest request = TestDataFactory.createCardPaymentRequest();
        RoutingResponse bankResponse = new RoutingResponse(
            false, "240115123456", null, "51", "Insufficient funds");

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(routingClient.processPayment(any())).thenReturn(bankResponse);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        PaymentResponse response = paymentService.processPayment(orderId, request);

        // Then
        assertThat(response.status()).isEqualTo("FAILED");
        verify(eventPublisher).publish(eq("payment.failed"), any());
    }

    @Test
    void processPayment_whenOrderNotCreated_shouldThrowException() {
        testOrder.setStatus(OrderStatus.AUTHORIZED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

        assertThatThrownBy(() ->
            paymentService.processPayment(orderId, TestDataFactory.createCardPaymentRequest()))
            .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void processPayment_whenOrderExpired_shouldThrowException() {
        testOrder.setStatus(OrderStatus.CREATED);
        testOrder.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() ->
            paymentService.processPayment(orderId, TestDataFactory.createCardPaymentRequest()))
            .isInstanceOf(OrderExpiredException.class);
    }

    @Test
    void capturePayment_whenAuthorized_shouldCapture() {
        testOrder.setStatus(OrderStatus.AUTHORIZED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.capturePayment(orderId);

        assertThat(response.status()).isEqualTo("CAPTURED");
        verify(eventPublisher).publish(eq("payment.captured"), any());
    }

    @Test
    void capturePayment_whenNotAuthorized_shouldThrow() {
        testOrder.setStatus(OrderStatus.CREATED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

        assertThatThrownBy(() -> paymentService.capturePayment(orderId))
            .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void processPayment_whenOrderNotFound_shouldThrow() {
        when(orderRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            paymentService.processPayment(orderId, TestDataFactory.createCardPaymentRequest()))
            .isInstanceOf(OrderNotFoundException.class);
    }
}
```

## Order Service Unit Tests

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private IdempotencyService idempotencyService;
    @Mock private MerchantServiceClient merchantClient;

    @InjectMocks private OrderService orderService;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
    }

    @Test
    void createOrder_withValidRequest_shouldReturnOrder() {
        CreateOrderRequest request = TestDataFactory.createOrderRequest();
        when(idempotencyService.getCachedResponse(any(), any())).thenReturn(Optional.empty());
        when(merchantClient.validateMerchant(merchantId)).thenReturn(new MerchantResponse(/*...*/));
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            order.setId(UUID.randomUUID());
            return order;
        });

        OrderResponse response = orderService.createOrder(merchantId, request);

        assertThat(response).isNotNull();
        assertThat(response.amount()).isEqualTo(50000L);
        assertThat(response.status()).isEqualTo("CREATED");
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    void createOrder_withDuplicateIdempotencyKey_shouldReturnCached() {
        CreateOrderRequest request = TestDataFactory.createOrderRequest();
        OrderResponse cached = new OrderResponse("id", 50000L, "INR", "CREATED", null, null);
        when(idempotencyService.getCachedResponse(any(), any())).thenReturn(Optional.of(cached));

        OrderResponse response = orderService.createOrder(merchantId, request);

        assertThat(response).isEqualTo(cached);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrder_withNegativeAmount_shouldThrow() {
        CreateOrderRequest request = new CreateOrderRequest(
            -100L, "INR", "test", null, null, null, null, null);
        when(idempotencyService.getCachedResponse(any(), any())).thenReturn(Optional.empty());
        when(merchantClient.validateMerchant(any())).thenReturn(new MerchantResponse(/*...*/));

        assertThatThrownBy(() -> orderService.createOrder(merchantId, request))
            .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void createOrder_withAmountExceedingLimit_shouldThrow() {
        CreateOrderRequest request = new CreateOrderRequest(
            99_999_99L, "INR", "test", null, null, null, null, null);
        when(idempotencyService.getCachedResponse(any(), any())).thenReturn(Optional.empty());
        when(merchantClient.validateMerchant(any())).thenReturn(new MerchantResponse(/*...*/));

        assertThatThrownBy(() -> orderService.createOrder(merchantId, request))
            .isInstanceOf(AmountExceedsLimitException.class);
    }
}
```

## Fraud Detection Unit Tests

```java
@ExtendWith(MockitoExtension.class)
class VelocityCheckRuleTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private VelocityCheckRule velocityRule;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        velocityRule = new VelocityCheckRule(redisTemplate);
    }

    @Test
    void evaluate_whenUnderLimit_shouldPass() {
        when(valueOps.increment(anyString())).thenReturn(3L);

        RoutingRequest request = createRoutingRequest("4111111111111111");
        FraudCheckResult result = velocityRule.evaluate(request);

        assertThat(result.isFlagged()).isFalse();
    }

    @Test
    void evaluate_whenOverLimit_shouldFlag() {
        when(valueOps.increment(anyString())).thenReturn(6L);

        RoutingRequest request = createRoutingRequest("4111111111111111");
        FraudCheckResult result = velocityRule.evaluate(request);

        assertThat(result.isFlagged()).isTrue();
        assertThat(result.reason()).contains("too many transactions");
    }
}
```

## Settlement Fee Calculation Tests

```java
@ExtendWith(MockitoExtension.class)
class FeeCalculationProcessorTest {

    @Mock private MerchantServiceClient merchantClient;

    @InjectMocks private FeeCalculationProcessor processor;

    @Test
    void process_cardPayment_shouldCalculateCorrectFees() {
        // Given: ₹1000 card payment, MDR 2%, GST 18%
        CapturedTransaction txn = new CapturedTransaction(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            100000L,  // ₹1000 in paise
            "CARD"
        );

        FeeConfigResponse feeConfig = new FeeConfigResponse(
            new BigDecimal("2.00"),   // MDR
            BigDecimal.ZERO,          // Fixed fee
            new BigDecimal("18.00")   // GST
        );
        when(merchantClient.getMerchantFees(any(), any()))
            .thenReturn(List.of(feeConfig));

        // When
        SettlementRecord record = processor.process(txn);

        // Then
        assertThat(record.getGrossAmount()).isEqualTo(100000L);
        assertThat(record.getMdrAmount()).isEqualTo(2000L);    // ₹20
        assertThat(record.getGstAmount()).isEqualTo(360L);     // ₹3.60
        assertThat(record.getNetAmount()).isEqualTo(97640L);   // ₹976.40
    }

    @Test
    void process_upiPayment_shouldApplyLowerMdr() {
        CapturedTransaction txn = new CapturedTransaction(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            100000L,  // ₹1000
            "UPI"
        );

        FeeConfigResponse feeConfig = new FeeConfigResponse(
            new BigDecimal("0.50"),   // UPI MDR only 0.5%
            BigDecimal.ZERO,
            new BigDecimal("18.00")
        );
        when(merchantClient.getMerchantFees(any(), any()))
            .thenReturn(List.of(feeConfig));

        SettlementRecord record = processor.process(txn);

        assertThat(record.getMdrAmount()).isEqualTo(500L);     // ₹5
        assertThat(record.getGstAmount()).isEqualTo(90L);      // ₹0.90
        assertThat(record.getNetAmount()).isEqualTo(99410L);   // ₹994.10
    }
}
```

## Best Practices Applied

| Practice | Example |
|----------|---------|
| One assertion per test | Each test verifies one behavior |
| Arrange-Act-Assert | Clear Given-When-Then structure |
| Test naming | Descriptive method names explain scenarios |
| Mocking boundaries | Mock external dependencies, not internal logic |
| Edge cases | Test null inputs, boundaries, error conditions |
| Independent tests | No shared mutable state between tests |
| Fast execution | All unit tests run in < 10 seconds total |
