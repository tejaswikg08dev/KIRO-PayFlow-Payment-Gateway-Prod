# Phase 5 — Testing Strategy & Implementation

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 5 — Testing |
| **Part** | Complete Testing Guide |
| **Previous** | [Phase 4 Part 16 — Hosted Checkout](phase4-part16-hosted-checkout.md) |
| **Next** | [Phase 6 Part 1 — Docker Concepts](phase6-part1-docker-concepts.md) |
| **Time** | ~4 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | Java, Spring Boot, JUnit basics |

---

## Table of Contents

1. [Testing Strategy & Test Pyramid](#1-testing-strategy--test-pyramid)
2. [JUnit 5 Fundamentals](#2-junit-5-fundamentals)
3. [Mockito Patterns](#3-mockito-patterns)
4. [Controller Tests with @WebMvcTest](#4-controller-tests-with-webmvctest)
5. [Integration Tests with Testcontainers](#5-integration-tests-with-testcontainers)
6. [Code Coverage with JaCoCo](#6-code-coverage-with-jacoco)
7. [Running Tests](#7-running-tests)
8. [What You Learned](#what-you-learned)
9. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Testing Strategy & Test Pyramid

```
                    /\
                   /  \          E2E Tests (few)
                  /    \         - Full system, real HTTP
                 /------\
                /        \      Integration Tests (some)
               /          \     - Real DB, real Redis
              /------------\
             /              \   Unit Tests (many)
            /                \  - Fast, isolated, mocked
           /------------------\
```

| Layer | What | Tools | Speed | Count |
|-------|------|-------|-------|-------|
| Unit | Single class/method | JUnit 5 + Mockito | ~ms | 100s |
| Integration | Multiple components + infra | Testcontainers | ~seconds | 10s |
| E2E | Full API flow | REST Assured + Docker Compose | ~10s | Few |

**PayFlow Testing Goals:**
- Unit test coverage: **80%+** on service layer
- Integration tests: Every repository + critical flows
- E2E tests: Happy path for payment lifecycle

---

## 2. JUnit 5 Fundamentals

### Key Annotations

```java
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class PaymentServiceTest {

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        // Runs before EACH test method
        paymentService = new PaymentService();
    }

    @AfterEach
    void tearDown() {
        // Cleanup after each test
    }

    @Test
    @DisplayName("Should create payment order with valid request")
    void shouldCreatePaymentOrder() {
        // Given
        CreateOrderRequest request = CreateOrderRequest.builder()
            .amount(BigDecimal.valueOf(1000))
            .currency("INR")
            .merchantId("merchant-123")
            .build();

        // When
        PaymentOrder order = paymentService.createOrder(request);

        // Then
        assertThat(order).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    @DisplayName("Should throw exception for negative amount")
    void shouldRejectNegativeAmount() {
        CreateOrderRequest request = CreateOrderRequest.builder()
            .amount(BigDecimal.valueOf(-100))
            .build();

        assertThatThrownBy(() -> paymentService.createOrder(request))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("Amount must be positive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("Should reject blank merchant ID")
    void shouldRejectBlankMerchantId(String merchantId) {
        CreateOrderRequest request = CreateOrderRequest.builder()
            .merchantId(merchantId)
            .amount(BigDecimal.valueOf(100))
            .build();

        assertThatThrownBy(() -> paymentService.createOrder(request))
            .isInstanceOf(InvalidRequestException.class);
    }

    @Nested
    @DisplayName("Refund Tests")
    class RefundTests {

        @Test
        @DisplayName("Should process full refund")
        void shouldProcessFullRefund() {
            // test implementation
        }

        @Test
        @DisplayName("Should process partial refund")
        void shouldProcessPartialRefund() {
            // test implementation
        }
    }
}
```

---

## 3. Mockito Patterns

### Basic Mocking

```java
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentOrderRepository orderRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @Test
    @DisplayName("Should save order and publish event")
    void shouldSaveOrderAndPublishEvent() {
        // Given - set up mock behavior
        CreateOrderRequest request = buildValidRequest();
        when(idempotencyService.checkDuplicate(anyString())).thenReturn(false);
        when(orderRepository.save(any(PaymentOrder.class)))
            .thenAnswer(inv -> {
                PaymentOrder order = inv.getArgument(0);
                order.setId("order-001");
                return order;
            });

        // When
        PaymentOrder result = paymentService.createOrder(request);

        // Then - verify interactions
        verify(orderRepository, times(1)).save(any(PaymentOrder.class));
        verify(eventPublisher).publish(argThat(event ->
            event.getType().equals("ORDER_CREATED") &&
            event.getOrderId().equals("order-001")
        ));
        verify(idempotencyService).checkDuplicate(request.getIdempotencyKey());
    }

    @Test
    @DisplayName("Should return cached result for duplicate request")
    void shouldReturnCachedForDuplicate() {
        // Given
        when(idempotencyService.checkDuplicate("key-123")).thenReturn(true);
        when(idempotencyService.getCachedResponse("key-123"))
            .thenReturn(existingOrder());

        CreateOrderRequest request = buildValidRequest();
        request.setIdempotencyKey("key-123");

        // When
        PaymentOrder result = paymentService.createOrder(request);

        // Then - repository should NOT be called
        verify(orderRepository, never()).save(any());
        assertThat(result.getId()).isEqualTo("existing-order-id");
    }
}
```

### Mockito Cheat Sheet

| Pattern | Usage |
|---------|-------|
| `when(mock.method()).thenReturn(value)` | Stub return value |
| `when(mock.method()).thenThrow(exception)` | Stub exception |
| `when(mock.method()).thenAnswer(inv -> ...)` | Dynamic return |
| `verify(mock).method()` | Verify called once |
| `verify(mock, times(2)).method()` | Verify call count |
| `verify(mock, never()).method()` | Verify NOT called |
| `argThat(arg -> condition)` | Custom argument matcher |
| `any()`, `anyString()`, `anyLong()` | Wildcard matchers |
| `@Captor ArgumentCaptor<T>` | Capture argument for assertion |

---

## 4. Controller Tests with @WebMvcTest

`@WebMvcTest` loads only the web layer — no service beans, no database.

```java
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private JwtTokenProvider tokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/v1/payments/orders - should create order")
    void shouldCreateOrder() throws Exception {
        // Given
        CreateOrderRequest request = new CreateOrderRequest(
            BigDecimal.valueOf(1500), "INR", "merchant-001", "idem-key-1"
        );
        PaymentOrder mockOrder = PaymentOrder.builder()
            .id("order-123")
            .status(OrderStatus.CREATED)
            .amount(BigDecimal.valueOf(1500))
            .build();

        when(paymentService.createOrder(any())).thenReturn(mockOrder);

        // When & Then
        mockMvc.perform(post("/api/v1/payments/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer valid-token")
                .header("X-Idempotency-Key", "idem-key-1")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("order-123"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.amount").value(1500));
    }

    @Test
    @DisplayName("POST /api/v1/payments/orders - should return 400 for invalid request")
    void shouldReturn400ForInvalidRequest() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
            BigDecimal.valueOf(-100), null, null, null  // all invalid
        );

        mockMvc.perform(post("/api/v1/payments/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer valid-token")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/payments/orders/{id} - should return 404 for unknown order")
    void shouldReturn404ForUnknownOrder() throws Exception {
        when(paymentService.getOrder("unknown")).thenThrow(new OrderNotFoundException("unknown"));

        mockMvc.perform(get("/api/v1/payments/orders/unknown")
                .header("Authorization", "Bearer valid-token"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("ORDER_NOT_FOUND"));
    }

    @Test
    @DisplayName("Should return 401 without auth token")
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/payments/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
```

---

## 5. Integration Tests with Testcontainers

Testcontainers spins up real Docker containers for infrastructure dependencies.

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PaymentRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("payflow_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private PaymentOrderRepository repository;

    @Test
    @DisplayName("Should save and retrieve payment order from real database")
    void shouldSaveAndRetrieve() {
        // Given
        PaymentOrder order = PaymentOrder.builder()
            .orderId("ORD_" + UUID.randomUUID())
            .merchantId("merchant-001")
            .amount(BigDecimal.valueOf(2500))
            .currency("INR")
            .status(OrderStatus.CREATED)
            .build();

        // When
        PaymentOrder saved = repository.save(order);
        Optional<PaymentOrder> found = repository.findByOrderId(saved.getOrderId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getAmount()).isEqualByComparingTo(BigDecimal.valueOf(2500));
        assertThat(found.get().getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    @DisplayName("Should find orders by merchant ID with pagination")
    void shouldFindByMerchantWithPagination() {
        // Given - insert 25 orders
        for (int i = 0; i < 25; i++) {
            repository.save(buildOrder("merchant-002", BigDecimal.valueOf(100 + i)));
        }

        // When
        Page<PaymentOrder> page = repository.findByMerchantId(
            "merchant-002", PageRequest.of(0, 10, Sort.by("createdAt").descending())
        );

        // Then
        assertThat(page.getContent()).hasSize(10);
        assertThat(page.getTotalElements()).isEqualTo(25);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }
}
```

### Full Payment Flow Integration Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PaymentFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Full payment lifecycle: create → authorize → capture")
    void fullPaymentLifecycle() {
        // 1. Create Order
        var createResp = restTemplate.postForEntity("/api/v1/payments/orders",
            new CreateOrderRequest(BigDecimal.valueOf(1000), "INR", "m-001", "key-1"),
            PaymentOrderResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = createResp.getBody().getId();

        // 2. Authorize Payment
        var authResp = restTemplate.postForEntity("/api/v1/payments/orders/" + orderId + "/authorize",
            new AuthorizeRequest("CARD", cardDetails()),
            PaymentOrderResponse.class);
        assertThat(authResp.getBody().getStatus()).isEqualTo("AUTHORIZED");

        // 3. Capture Payment
        var captureResp = restTemplate.postForEntity("/api/v1/payments/orders/" + orderId + "/capture",
            new CaptureRequest(BigDecimal.valueOf(1000)),
            PaymentOrderResponse.class);
        assertThat(captureResp.getBody().getStatus()).isEqualTo("CAPTURED");
    }
}
```

---

## 6. Code Coverage with JaCoCo

### Maven Configuration

```xml
<!-- In parent pom.xml -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

After running `mvn verify`, find the report at:
```
target/site/jacoco/index.html
```

---

## 7. Running Tests

```bash
# Run all unit tests
mvn test

# Run all tests including integration
mvn verify

# Run specific test class
mvn test -Dtest=PaymentServiceTest

# Run specific test method
mvn test -Dtest=PaymentServiceTest#shouldCreatePaymentOrder

# Run with coverage report
mvn verify -Pjacoco

# Run tests for a specific module
mvn test -pl payment-service

# Skip tests during build (not recommended!)
mvn package -DskipTests
```

### IDE Test Runner (IntelliJ IDEA)

1. Right-click test class → "Run Tests"
2. Green gutter icon next to `@Test` → run individual test
3. Right-click package → "Run Tests in package"
4. View results in Run tool window (green = pass, red = fail)

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Test pyramid | Many unit tests, fewer integration, fewest E2E |
| 2 | JUnit 5 | `@Test`, `@DisplayName`, `@BeforeEach`, `@Nested` for organization |
| 3 | Mockito | Mock dependencies, verify interactions, isolate unit under test |
| 4 | @WebMvcTest | Test controllers without starting full app context |
| 5 | Testcontainers | Real infrastructure in tests (PostgreSQL, Redis) via Docker |
| 6 | JaCoCo | Enforce minimum 80% line coverage at build time |
| 7 | Test commands | `mvn test` for unit, `mvn verify` for integration |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `NullPointerException` in test | `@Mock` but forgot `@ExtendWith(MockitoExtension.class)` | Add extension annotation |
| `Unnecessary stubbings detected` | Stubbed method but never called | Remove unused `when()` or use `lenient()` |
| Testcontainers: `Docker not available` | Docker Desktop not running | Start Docker Desktop before running tests |
| `@WebMvcTest` loads full context | Wrong annotation — used `@SpringBootTest` | Switch to `@WebMvcTest(ControllerName.class)` |
| JaCoCo: `Coverage below minimum` | Not enough tests | Write more tests or exclude generated code |
| Flyway migration fails in test | Test DB has stale schema | Use `spring.flyway.clean-disabled=false` in test profile |
| Port conflict in integration test | Hardcoded ports | Use `RANDOM_PORT` or Testcontainers mapped ports |

---

<div align="center">

**[← Phase 4 Part 16: Hosted Checkout](phase4-part16-hosted-checkout.md)** | **[Documentation Index](../README.md)** | **[Phase 6 Part 1: Docker Concepts →](phase6-part1-docker-concepts.md)**

</div>
