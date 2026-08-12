# Phase 5 Part 4: Integration Tests

## Overview

Integration tests using Testcontainers for real PostgreSQL and Kafka, testing full flows from HTTP request through service layer to database persistence and event publishing.

## Full Payment Flow Integration Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@Testcontainers
class PaymentFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("payflow_payment_test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @LocalServerPort
    private int port;

    @Test
    void fullPaymentFlow_createOrder_processPayment_capture() {
        // Step 1: Create order
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Id", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        String orderBody = """
            {
                "amount": 50000,
                "currency": "INR",
                "description": "Integration test order",
                "customerEmail": "test@example.com",
                "idempotencyKey": "idem_test_123"
            }
            """;

        ResponseEntity<ApiResponse> createResponse = restTemplate.exchange(
            "/api/v1/orders", HttpMethod.POST,
            new HttpEntity<>(orderBody, headers), ApiResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = extractOrderId(createResponse);

        // Verify order persisted
        Order savedOrder = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(savedOrder.getAmount()).isEqualTo(50000L);

        // Step 2: Process payment
        String paymentBody = """
            {
                "paymentMethod": "card",
                "card": {
                    "number": "4111111111111111",
                    "expiryMonth": "12",
                    "expiryYear": "25",
                    "cvv": "123",
                    "holderName": "Test User"
                }
            }
            """;

        ResponseEntity<ApiResponse> payResponse = restTemplate.exchange(
            "/api/v1/orders/" + orderId + "/pay", HttpMethod.POST,
            new HttpEntity<>(paymentBody, headers), ApiResponse.class);

        assertThat(payResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify state transition
        Order updatedOrder = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        assertThat(updatedOrder.getStatus()).isIn(
            OrderStatus.AUTHORIZED, OrderStatus.FAILED);

        if (updatedOrder.getStatus() == OrderStatus.AUTHORIZED) {
            // Step 3: Capture
            ResponseEntity<ApiResponse> captureResponse = restTemplate.exchange(
                "/api/v1/orders/" + orderId + "/capture", HttpMethod.POST,
                new HttpEntity<>(headers), ApiResponse.class);

            assertThat(captureResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

            Order capturedOrder = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
            assertThat(capturedOrder.getStatus()).isEqualTo(OrderStatus.CAPTURED);

            // Verify transaction record created
            List<Transaction> txns = transactionRepository.findByOrderId(UUID.fromString(orderId));
            assertThat(txns).hasSizeGreaterThanOrEqualTo(2); // auth + capture
        }
    }

    @Test
    void idempotency_duplicateRequest_shouldReturnSameResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Id", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
            {
                "amount": 30000,
                "currency": "INR",
                "idempotencyKey": "unique_key_456"
            }
            """;

        // First request
        ResponseEntity<ApiResponse> first = restTemplate.exchange(
            "/api/v1/orders", HttpMethod.POST,
            new HttpEntity<>(body, headers), ApiResponse.class);

        // Second request (same idempotency key)
        ResponseEntity<ApiResponse> second = restTemplate.exchange(
            "/api/v1/orders", HttpMethod.POST,
            new HttpEntity<>(body, headers), ApiResponse.class);

        // Should return same order ID
        assertThat(extractOrderId(first)).isEqualTo(extractOrderId(second));

        // Should not create duplicate in DB
        long count = orderRepository.countByIdempotencyKey("unique_key_456");
        assertThat(count).isEqualTo(1);
    }
}
```

## Kafka Integration Test

```java
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"payment.captured", "payment.failed"},
    brokerProperties = {"listeners=PLAINTEXT://localhost:9093"}
)
@ActiveProfiles("integration")
class KafkaEventPublisherIntegrationTest {

    @Autowired
    private KafkaEventPublisher publisher;

    @Autowired
    private KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
            "test-group", "true", embeddedKafka);
        consumer = new DefaultKafkaConsumerFactory<String, String>(props)
            .createConsumer();
        embeddedKafka.consumeFromAllEmbeddedTopics(consumer);
    }

    @Test
    void publish_paymentCapturedEvent_shouldBeConsumed() {
        PaymentEvent event = new PaymentEvent(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            50000L, "INR", "CAPTURED", "CARD",
            "240115123456", UUID.randomUUID().toString(),
            "test@example.com", "+919876543210",
            LocalDateTime.now().toString()
        );

        publisher.publish("payment.captured", event);

        // Verify event was published
        ConsumerRecords<String, String> records = KafkaTestUtils
            .getRecords(consumer, Duration.ofSeconds(10));

        assertThat(records.count()).isGreaterThan(0);
        ConsumerRecord<String, String> record = records.iterator().next();
        assertThat(record.topic()).isEqualTo("payment.captured");
        assertThat(record.value()).contains("CAPTURED");
    }
}
```

## Repository Integration Test

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("payflow_test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void findByMerchantId_shouldReturnPaginatedResults() {
        UUID merchantId = UUID.randomUUID();

        // Create 25 orders
        for (int i = 0; i < 25; i++) {
            Order order = TestDataFactory.createOrder(merchantId);
            order.setId(null);
            orderRepository.save(order);
        }

        // Query first page
        Page<Order> page = orderRepository.findByMerchantIdOrderByCreatedAtDesc(
            merchantId, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(10);
        assertThat(page.getTotalElements()).isEqualTo(25);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    void findByIdempotencyKey_shouldReturnExactMatch() {
        UUID merchantId = UUID.randomUUID();
        Order order = TestDataFactory.createOrder(merchantId);
        order.setId(null);
        order.setIdempotencyKey("unique_key_789");
        orderRepository.save(order);

        Optional<Order> found = orderRepository.findByIdempotencyKey("unique_key_789");

        assertThat(found).isPresent();
        assertThat(found.get().getMerchantId()).isEqualTo(merchantId);
    }
}
```

## REST Assured Tests

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class PaymentApiRestAssuredTest {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
    }

    @Test
    void createOrder_shouldReturnCorrectStructure() {
        given()
            .header("X-Merchant-Id", UUID.randomUUID().toString())
            .contentType(ContentType.JSON)
            .body("""
                {"amount": 50000, "currency": "INR", "description": "test"}
                """)
        .when()
            .post("/orders")
        .then()
            .statusCode(201)
            .body("success", equalTo(true))
            .body("data.id", notNullValue())
            .body("data.amount", equalTo(50000))
            .body("data.status", equalTo("CREATED"))
            .body("data.currency", equalTo("INR"));
    }
}
```

## Running Integration Tests

```bash
# Requires Docker to be running
mvn verify -Pintegration -pl payment-service

# With verbose output
mvn verify -Pintegration -Dsurefire.useFile=false
```
