# Phase 3: Low-Level Design

## Overview

This document provides the detailed technical design for all services — entity definitions, repository interfaces, service signatures, controller endpoints, DTOs, and configuration details.

## Maven Multi-Module Structure

```xml
<!-- Root POM (parent) -->
<groupId>com.payflow</groupId>
<artifactId>payflow-parent</artifactId>
<packaging>pom</packaging>

<modules>
    <module>api-gateway</module>
    <module>identity-service</module>
    <module>merchant-service</module>
    <module>payment-service</module>
    <module>routing-service</module>
    <module>bank-simulator</module>
    <module>settlement-service</module>
    <module>webhook-service</module>
    <module>notification-service</module>
</modules>
```

### Common Dependencies per Service

| Dependency | Version | Used In |
|-----------|---------|---------|
| Spring Boot Starter Web | 3.2.x | All services |
| Spring Data JPA | 3.2.x | Identity, Merchant, Payment, Settlement |
| PostgreSQL Driver | 42.7.x | Identity, Merchant, Payment, Settlement |
| Spring Kafka | 3.1.x | Payment, Settlement, Webhook, Notification |
| Spring Data Redis | 3.2.x | Payment, Gateway |
| Flyway | 10.x | All with PostgreSQL |
| Lombok | 1.18.x | All services |
| MapStruct | 1.5.x | All services |
| SpringDoc OpenAPI | 2.3.x | All services |
| Resilience4j | 2.2.x | Payment, Routing |

## JPA Entities

### Identity Service Entities

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    private UserRole role;  // MERCHANT, ADMIN

    @Enumerated(EnumType.STRING)
    private UserStatus status;  // ACTIVE, INACTIVE, SUSPENDED

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(unique = true, nullable = false)
    private String token;

    private LocalDateTime expiresAt;
    private boolean revoked;
}
```

### Merchant Service Entities

```java
@Entity
@Table(name = "merchants")
public class Merchant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String businessName;

    private String businessType;
    private String mccCode;  // Merchant Category Code
    private String gstin;
    private String panNumber;

    @Enumerated(EnumType.STRING)
    private MerchantStatus status;  // PENDING, ACTIVE, SUSPENDED

    private String settlementAccountNumber;
    private String settlementIfsc;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

@Entity
@Table(name = "api_keys")
public class ApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id")
    private Merchant merchant;

    @Column(nullable = false)
    private String keyPrefix;  // "pk_live_abc12345"

    @Column(nullable = false)
    private String keyHash;  // SHA-256 of full key

    @Enumerated(EnumType.STRING)
    private KeyStatus status;  // ACTIVE, REVOKED

    @Enumerated(EnumType.STRING)
    private KeyType type;  // LIVE, TEST

    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
}

@Entity
@Table(name = "webhook_configs")
public class WebhookConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id")
    private Merchant merchant;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String secret;  // For HMAC signing

    private String events;  // Comma-separated: "payment.captured,refund.completed"

    @Enumerated(EnumType.STRING)
    private WebhookStatus status;  // ACTIVE, INACTIVE

    private LocalDateTime createdAt;
}

@Entity
@Table(name = "fee_configs")
public class FeeConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id")
    private Merchant merchant;

    private BigDecimal mdrPercent;  // e.g., 2.00
    private BigDecimal fixedFee;    // e.g., 0.00
    private BigDecimal gstPercent;  // 18.00 (on MDR)

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;  // CARD, UPI, NET_BANKING

    private LocalDateTime effectiveFrom;
}
```

### Payment Service Entities

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private Long amount;  // In smallest currency unit (paise)

    @Column(nullable = false, length = 3)
    private String currency;  // "INR"

    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    // CREATED, PROCESSING, AUTHORIZED, CAPTURED, FAILED, VOIDED, REFUNDED

    private String description;
    private String receiptNumber;
    private String customerEmail;
    private String customerPhone;

    @Column(unique = true)
    private String idempotencyKey;

    private String callbackUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
}

@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    private TransactionType type;  // AUTHORIZATION, CAPTURE, VOID

    private Long amount;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;  // SUCCESS, FAILED, TIMEOUT

    private String rrn;       // Retrieval Reference Number
    private String authCode;  // Bank authorization code
    private String responseCode;  // Bank response code

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;  // CARD, UPI, NET_BANKING

    private String cardLast4;
    private String cardNetwork;  // VISA, MASTERCARD

    private LocalDateTime createdAt;
}

@Entity
@Table(name = "refunds")
public class Refund {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    private Long amount;
    private String reason;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;  // INITIATED, PROCESSING, COMPLETED, FAILED

    private String rrn;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}
```

### Settlement Service Entities

```java
@Entity
@Table(name = "settlement_batches")
public class SettlementBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private LocalDate batchDate;

    @Enumerated(EnumType.STRING)
    private BatchStatus status;  // PENDING, PROCESSING, COMPLETED, FAILED

    private Long totalGrossAmount;
    private Long totalFees;
    private Long totalNetAmount;
    private Integer transactionCount;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}

@Entity
@Table(name = "settlement_records")
public class SettlementRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private SettlementBatch batch;

    private UUID merchantId;
    private UUID transactionId;

    private Long grossAmount;
    private Long mdrAmount;
    private Long gstAmount;
    private Long netAmount;

    private LocalDateTime createdAt;
}

@Entity
@Table(name = "payouts")
public class Payout {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID merchantId;
    private UUID batchId;
    private Long amount;

    @Enumerated(EnumType.STRING)
    private PayoutStatus status;  // INITIATED, PROCESSING, COMPLETED, FAILED

    private String utr;  // Unique Transaction Reference
    private String accountNumber;
    private String ifscCode;

    private LocalDateTime initiatedAt;
    private LocalDateTime completedAt;
}
```

## Repository Interfaces

```java
// Identity Service
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByToken(String token);
    void deleteByUser(User user);
}

// Merchant Service
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    Optional<Merchant> findByUserId(UUID userId);
    List<Merchant> findByStatus(MerchantStatus status);
}

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyPrefixAndStatus(String prefix, KeyStatus status);
    List<ApiKey> findByMerchantId(UUID merchantId);
    Optional<ApiKey> findByKeyHash(String keyHash);
}

public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {
    List<WebhookConfig> findByMerchantIdAndStatus(UUID merchantId, WebhookStatus status);
}

// Payment Service
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
    Page<Order> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime before);
}

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByOrderId(UUID orderId);
    Optional<Transaction> findByRrn(String rrn);
    Page<Transaction> findByOrder_MerchantId(UUID merchantId, Pageable pageable);
}

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByTransactionId(UUID transactionId);
    Long sumAmountByTransactionIdAndStatusNot(UUID transactionId, RefundStatus status);
}

// Settlement Service
public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {
    Optional<SettlementBatch> findByBatchDate(LocalDate date);
}

public interface PayoutRepository extends JpaRepository<Payout, UUID> {
    List<Payout> findByMerchantIdOrderByInitiatedAtDesc(UUID merchantId);
}
```

## Service Class Signatures

```java
// Identity Service
public class AuthService {
    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    AuthResponse refreshToken(RefreshTokenRequest request);
    void logout(UUID userId);
    UserDetails validateToken(String token);
}

// Merchant Service
public class MerchantService {
    MerchantResponse createMerchant(UUID userId, CreateMerchantRequest request);
    MerchantResponse getMerchant(UUID merchantId);
    MerchantResponse updateMerchant(UUID merchantId, UpdateMerchantRequest request);
    ApiKeyResponse generateApiKey(UUID merchantId, KeyType type);
    void revokeApiKey(UUID keyId);
    MerchantResponse validateApiKey(String apiKey);
}

// Payment Service
public class OrderService {
    OrderResponse createOrder(UUID merchantId, CreateOrderRequest request);
    OrderResponse getOrder(UUID orderId);
    PageResponse<OrderResponse> listOrders(UUID merchantId, Pageable pageable);
    void expireStaleOrders();  // Scheduled task
}

public class PaymentService {
    PaymentResponse processPayment(UUID orderId, PaymentRequest request);
    PaymentResponse capturePayment(UUID orderId, CaptureRequest request);
    void handleBankResponse(BankResponseDTO response);
}

public class RefundService {
    RefundResponse initiateRefund(UUID transactionId, RefundRequest request);
    RefundResponse getRefund(UUID refundId);
}

// Settlement Service
public class SettlementService {
    void runDailySettlement(LocalDate date);
    SettlementBatchResponse getBatch(UUID batchId);
    List<PayoutResponse> getMerchantPayouts(UUID merchantId);
}
```

## Controller Endpoints

### Identity Service (`/api/v1/auth`)

| Method | Endpoint | Request Body | Response |
|--------|----------|-------------|----------|
| POST | `/register` | RegisterRequest | AuthResponse (tokens) |
| POST | `/login` | LoginRequest | AuthResponse (tokens) |
| POST | `/refresh` | RefreshTokenRequest | AuthResponse (new tokens) |
| POST | `/logout` | - | 204 No Content |
| GET | `/me` | - | UserResponse |

### Merchant Service (`/api/v1/merchants`)

| Method | Endpoint | Request Body | Response |
|--------|----------|-------------|----------|
| POST | `/` | CreateMerchantRequest | MerchantResponse |
| GET | `/{id}` | - | MerchantResponse |
| PUT | `/{id}` | UpdateMerchantRequest | MerchantResponse |
| POST | `/{id}/api-keys` | GenerateKeyRequest | ApiKeyResponse |
| DELETE | `/api-keys/{keyId}` | - | 204 No Content |
| POST | `/{id}/webhooks` | WebhookConfigRequest | WebhookConfigResponse |
| PUT | `/{id}/fees` | FeeConfigRequest | FeeConfigResponse |

### Payment Service (`/api/v1`)

| Method | Endpoint | Request Body | Response |
|--------|----------|-------------|----------|
| POST | `/orders` | CreateOrderRequest | OrderResponse |
| GET | `/orders/{id}` | - | OrderResponse |
| GET | `/orders` | - (query params) | Page<OrderResponse> |
| POST | `/orders/{id}/pay` | PaymentRequest | PaymentResponse |
| POST | `/orders/{id}/capture` | CaptureRequest | PaymentResponse |
| POST | `/refunds` | RefundRequest | RefundResponse |
| GET | `/refunds/{id}` | - | RefundResponse |
| GET | `/transactions` | - (query params) | Page<TransactionResponse> |

## Key DTOs

```java
// Request DTOs
public record CreateOrderRequest(
    Long amount,
    String currency,
    String description,
    String receiptNumber,
    String customerEmail,
    String customerPhone,
    String callbackUrl,
    String idempotencyKey
) {}

public record PaymentRequest(
    String paymentMethod,  // "card", "upi", "net_banking"
    CardDetails card,      // nullable
    UpiDetails upi,        // nullable
    NetBankingDetails netBanking  // nullable
) {}

public record CardDetails(
    String number,
    String expiryMonth,
    String expiryYear,
    String cvv,
    String holderName
) {}

// Response DTOs
public record OrderResponse(
    String id,
    Long amount,
    String currency,
    String status,
    String description,
    String createdAt
) {}

public record ApiResponse<T>(
    boolean success,
    T data,
    String message,
    String timestamp
) {}
```

## Spring Cloud Configuration

```yaml
# application.yml (Payment Service)
spring:
  application:
    name: payment-service
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_payment
    username: ${DB_USERNAME:payflow}
    password: ${DB_PASSWORD:payflow123}
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
  redis:
    host: localhost
    port: 6379

server:
  port: 8082

resilience4j:
  circuitbreaker:
    instances:
      routingService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
  retry:
    instances:
      routingService:
        maxAttempts: 3
        waitDuration: 1s
        exponentialBackoffMultiplier: 2
```

## Flyway Migrations Summary

| Service | Migration | Description |
|---------|-----------|-------------|
| Identity | V1__create_users.sql | Users table + indexes |
| Identity | V2__create_refresh_tokens.sql | Refresh tokens |
| Merchant | V1__create_merchants.sql | Merchants table |
| Merchant | V2__create_api_keys.sql | API keys table |
| Merchant | V3__create_webhook_configs.sql | Webhook configs |
| Merchant | V4__create_fee_configs.sql | Fee configurations |
| Payment | V1__create_orders.sql | Orders table + indexes |
| Payment | V2__create_transactions.sql | Transactions table |
| Payment | V3__create_refunds.sql | Refunds table |
| Settlement | V1__create_settlement_tables.sql | Batches + records + payouts |

## ISO 8583 Message Structure

```
┌────────────────────────────────────────────────────────────┐
│                    ISO 8583 MESSAGE                          │
├──────────┬──────────┬──────────────────────────────────────┤
│   MTI    │  Bitmap  │           Data Fields                 │
│ (4 bytes)│(16 bytes)│         (variable)                    │
└──────────┴──────────┴──────────────────────────────────────┘

MTI Examples:
- 0100: Authorization Request
- 0110: Authorization Response
- 0200: Financial Transaction Request
- 0210: Financial Transaction Response
- 0400: Reversal Request
- 0410: Reversal Response
```

### Key Fields Used

| Field | Name | Length | Format | Example |
|-------|------|--------|--------|---------|
| 2 | PAN (Card Number) | 19 | N | 4111111111111111 |
| 3 | Processing Code | 6 | N | 000000 (purchase) |
| 4 | Amount | 12 | N | 000000050000 (₹500) |
| 7 | Transmission DateTime | 10 | N | MMDDHHmmss |
| 11 | STAN | 6 | N | 123456 |
| 12 | Local Time | 6 | N | HHmmss |
| 14 | Expiry Date | 4 | N | YYMM |
| 37 | RRN | 12 | AN | 412312345678 |
| 38 | Auth Code | 6 | AN | A12345 |
| 39 | Response Code | 2 | AN | 00 (approved) |
| 41 | Terminal ID | 8 | AN | TERM0001 |
| 42 | Merchant ID | 15 | AN | MERCH000000001 |
| 49 | Currency Code | 3 | N | 356 (INR) |

## Netty Architecture (Routing Service)

```java
// TCP Client for bank communication
public class BankTcpClient {
    private final Bootstrap bootstrap;
    private final EventLoopGroup workerGroup;

    // Pipeline:
    // LengthFieldBasedFrameDecoder → ISO8583Decoder → ResponseHandler
    // LengthFieldPrepender → ISO8583Encoder

    public CompletableFuture<BankResponse> sendMessage(ISO8583Message message);
}
```

## Resilience4j Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      bankConnection:
        slidingWindowSize: 20
        failureRateThreshold: 50
        slowCallRateThreshold: 80
        slowCallDurationThreshold: 5s
        waitDurationInOpenState: 60s
        permittedNumberOfCallsInHalfOpenState: 5
        minimumNumberOfCalls: 10
  timelimiter:
    instances:
      bankConnection:
        timeoutDuration: 30s
  retry:
    instances:
      bankConnection:
        maxAttempts: 2
        waitDuration: 500ms
```

## Spring Batch Configuration (Settlement)

```java
@Configuration
@EnableBatchProcessing
public class SettlementBatchConfig {

    @Bean
    public Job settlementJob(Step fetchTransactionsStep,
                             Step calculateFeesStep,
                             Step initiatePayoutsStep) {
        return new JobBuilder("dailySettlement", jobRepository)
            .start(fetchTransactionsStep)
            .next(calculateFeesStep)
            .next(initiatePayoutsStep)
            .build();
    }

    @Bean
    public Step fetchTransactionsStep() {
        return new StepBuilder("fetchTransactions", jobRepository)
            .<Transaction, SettlementRecord>chunk(100, transactionManager)
            .reader(transactionReader())
            .processor(feeCalculationProcessor())
            .writer(settlementRecordWriter())
            .build();
    }
}
```
