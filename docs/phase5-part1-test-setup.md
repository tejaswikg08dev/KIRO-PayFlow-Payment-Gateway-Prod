# Phase 5 Part 1: Test Setup & Configuration

## Overview

Test infrastructure setup: profiles, H2 configuration, base test classes, and test utilities shared across all services.

## Test Profiles

```yaml
# src/test/resources/application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
    username: sa
    password:
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
  flyway:
    enabled: false  # H2 uses create-drop instead
  kafka:
    bootstrap-servers: ${spring.embedded.kafka.brokers:localhost:9092}

jwt:
  secret: dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3Rpbmctb25seQ==
  access-token-expiry: 900000
  refresh-token-expiry: 604800000

server:
  port: 0  # Random port for parallel test execution
```

## Base Test Classes

```java
// Base class for unit tests (no Spring context)
public abstract class BaseUnitTest {

    protected ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    protected UUID randomUUID() {
        return UUID.randomUUID();
    }

    protected LocalDateTime now() {
        return LocalDateTime.now();
    }
}

// Base class for controller tests (MockMvc)
@WebMvcTest
@ActiveProfiles("test")
@Import(SecurityConfig.class)
public abstract class BaseControllerTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String asJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    protected String validJwtToken() {
        return "Bearer test-valid-jwt-token";
    }
}

// Base class for integration tests (Testcontainers)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
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
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @LocalServerPort
    protected int port;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
```

## Test Data Factory

```java
public class TestDataFactory {

    public static User createUser() {
        return User.builder()
            .id(UUID.randomUUID())
            .email("test@example.com")
            .passwordHash("$2a$12$hashedpassword")
            .fullName("Test User")
            .role(UserRole.MERCHANT)
            .status(UserStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .build();
    }

    public static Merchant createMerchant(UUID userId) {
        return Merchant.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .businessName("Test Shop")
            .businessType("RETAIL")
            .mccCode("5411")
            .status(MerchantStatus.ACTIVE)
            .settlementAccountNumber("1234567890")
            .settlementIfsc("SBIN0001234")
            .createdAt(LocalDateTime.now())
            .build();
    }

    public static Order createOrder(UUID merchantId) {
        return Order.builder()
            .id(UUID.randomUUID())
            .merchantId(merchantId)
            .amount(50000L)  // ₹500
            .currency("INR")
            .status(OrderStatus.CREATED)
            .description("Test order")
            .customerEmail("buyer@example.com")
            .idempotencyKey("idem_" + UUID.randomUUID())
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusMinutes(30))
            .build();
    }

    public static CreateOrderRequest createOrderRequest() {
        return new CreateOrderRequest(
            50000L,
            "INR",
            "Test order",
            "RCT-001",
            "buyer@example.com",
            "+919876543210",
            "https://merchant.com/callback",
            "idem_" + UUID.randomUUID()
        );
    }

    public static PaymentRequest createCardPaymentRequest() {
        return new PaymentRequest(
            "card",
            new CardDetails(
                "4111111111111111",
                "12",
                "25",
                "123",
                "Test User"
            ),
            null,
            null
        );
    }

    public static RegisterRequest createRegisterRequest() {
        return new RegisterRequest(
            "newuser@example.com",
            "SecurePass123!",
            "New User"
        );
    }
}
```

## Custom Test Assertions

```java
public class PayFlowAssertions {

    public static OrderAssert assertThatOrder(Order order) {
        return new OrderAssert(order);
    }

    public static class OrderAssert {
        private final Order order;

        public OrderAssert(Order order) {
            this.order = order;
            assertThat(order).isNotNull();
        }

        public OrderAssert hasStatus(OrderStatus status) {
            assertThat(order.getStatus()).isEqualTo(status);
            return this;
        }

        public OrderAssert hasAmount(Long amount) {
            assertThat(order.getAmount()).isEqualTo(amount);
            return this;
        }

        public OrderAssert belongsToMerchant(UUID merchantId) {
            assertThat(order.getMerchantId()).isEqualTo(merchantId);
            return this;
        }

        public OrderAssert isNotExpired() {
            assertThat(order.getExpiresAt()).isAfter(LocalDateTime.now());
            return this;
        }
    }
}
```

## Test Configuration for Kafka

```java
@TestConfiguration
public class KafkaTestConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(
            EmbeddedKafkaBroker broker) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            broker.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(
            EmbeddedKafkaBroker broker) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }
}
```

## Running Tests

```bash
# Run all unit tests
mvn test

# Run specific service tests
mvn test -pl payment-service

# Run integration tests (requires Docker)
mvn verify -Pintegration

# Run with coverage report
mvn test jacoco:report
# Report at: target/site/jacoco/index.html

# Run specific test class
mvn test -Dtest=OrderServiceTest

# Run tests matching pattern
mvn test -Dtest="*PaymentService*"
```

## Maven Surefire Configuration

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*Test.java</include>
            <include>**/*Tests.java</include>
        </includes>
        <excludes>
            <exclude>**/*IntegrationTest.java</exclude>
            <exclude>**/*IT.java</exclude>
        </excludes>
    </configuration>
</plugin>

<!-- Failsafe for integration tests -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <configuration>
        <includes>
            <include>**/*IntegrationTest.java</include>
            <include>**/*IT.java</include>
        </includes>
    </configuration>
</plugin>
```
