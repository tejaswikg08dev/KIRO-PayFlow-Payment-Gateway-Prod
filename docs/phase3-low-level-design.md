# 🔧 Phase 3: Low-Level Design (LLD)

> **"The devil is in the details — and in a payment gateway, the details handle money."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 3 — Low-Level Design |
| **Previous** | [phase2-high-level-design.md](./phase2-high-level-design.md) |
| **Next** | [phase4-part01-parent-pom-and-maven-setup.md](./phase4-part01-parent-pom-and-maven-setup.md) |
| **Author** | Tejaswi |
| **Created** | 2024 |
| **Status** | Living Document |

---

## 📖 Table of Contents

1. [Maven Multi-Module Structure](#maven-multi-module-structure)
2. [JPA Entity Class Signatures](#jpa-entity-class-signatures)
3. [Repository Interfaces](#repository-interfaces)
4. [Service Method Signatures](#service-method-signatures)
5. [Controller Endpoints](#controller-endpoints)
6. [Spring Cloud Configurations](#spring-cloud-configurations)
7. [Flyway Migration Summary](#flyway-migration-summary)
8. [ISO 8583 Class Design](#iso-8583-class-design)
9. [What You Learned](#what-you-learned)
10. [Document Index](#document-index)

---

## 📦 Maven Multi-Module Structure

### Parent POM Overview

```
payflow-payment-gateway/backend/
├── pom.xml                    ← Parent POM (packaging: pom)
├── common-lib/                ← Shared DTOs, events, exceptions
│   └── pom.xml
├── service-registry/          ← Eureka Server
│   └── pom.xml
├── config-server/             ← Spring Cloud Config
│   └── pom.xml
├── api-gateway/               ← Spring Cloud Gateway
│   └── pom.xml
├── identity-service/          ← Auth + User management
│   └── pom.xml
├── merchant-service/          ← Merchant CRUD + API keys
│   └── pom.xml
├── payment-service/           ← Orders + Payments + Refunds
│   └── pom.xml
├── routing-service/           ← ISO 8583, Netty, Fraud, Smart Routing
│   └── pom.xml
├── settlement-service/        ← Spring Batch settlement
│   └── pom.xml
├── webhook-service/           ← Webhook delivery
│   └── pom.xml
├── notification-service/      ← Email/SMS notifications
│   └── pom.xml
└── bank-simulator/            ← Netty TCP bank simulator
    └── pom.xml
```

### Module Dependency Graph

```
                          ┌───────────────────┐
                          │   Parent POM      │
                          │  (Spring Boot 3.2.5)
                          └─────────┬─────────┘
                                    │
              ┌─────────────────────┼──────────────────────┐
              │                     │                       │
              ▼                     ▼                       ▼
     ┌────────────────┐   ┌────────────────┐    ┌──────────────────┐
     │  common-lib    │   │service-registry│    │  config-server   │
     │ (jar, no Boot) │   │  (standalone)  │    │  (standalone)    │
     └───────┬────────┘   └────────────────┘    └──────────────────┘
             │
    ┌────────┼────────────────────┬──────────────────┐
    │        │                    │                   │
    ▼        ▼                    ▼                   ▼
┌────────┐ ┌────────┐    ┌──────────┐      ┌──────────────┐
│identity│ │merchant│    │ payment  │      │   routing    │
│service │ │service │    │ service  │      │   service    │
└────────┘ └────────┘    └──────────┘      └──────────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                 │
              ▼                ▼                 ▼
     ┌────────────┐   ┌──────────┐    ┌──────────────┐
     │ settlement │   │ webhook  │    │ notification │
     │  service   │   │ service  │    │   service    │
     └────────────┘   └──────────┘    └──────────────┘
```

### POM Dependency Summary

| Module | Spring Boot Starters | Additional Dependencies |
|--------|---------------------|------------------------|
| common-lib | — | Lombok, Jackson, Jakarta Validation |
| service-registry | spring-cloud-starter-netflix-eureka-server | — |
| config-server | spring-cloud-config-server | — |
| api-gateway | spring-cloud-starter-gateway, webflux | Redis, Eureka Client, JJWT |
| identity-service | web, data-jpa, security, validation | JJWT, MapStruct, Eureka, Kafka |
| merchant-service | web, data-jpa, validation | MapStruct, Eureka, Kafka |
| payment-service | web, data-jpa, validation | Kafka, Feign, Redis, MapStruct |
| routing-service | web, data-jpa | Netty, Resilience4j, Eureka |
| settlement-service | web, batch, data-jpa | Kafka, MapStruct |
| webhook-service | web, kafka | AWS DynamoDB SDK, Eureka |
| notification-service | web, kafka, mail | Thymeleaf, Eureka |
| bank-simulator | web | Netty |

---

## 🗄️ JPA Entity Class Signatures

### Identity Service Entities

```java
// File: backend/identity-service/src/main/java/com/payflow/identity/model/User.java
@Entity
@Table(name = "users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;                    // USER, MERCHANT, ADMIN

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

```java
// File: backend/identity-service/src/main/java/com/payflow/identity/model/Role.java
public enum Role {
    USER, MERCHANT, ADMIN
}
```

```java
// File: backend/identity-service/src/main/java/com/payflow/identity/model/RefreshToken.java
@Entity
@Table(name = "refresh_tokens")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
```

### Merchant Service Entities

```java
// File: backend/merchant-service/src/main/java/com/payflow/merchant/model/Merchant.java
@Entity
@Table(name = "merchants")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Merchant {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "business_type")
    private String businessType;

    @Column(name = "pan_number")
    private String panNumber;

    @Column(name = "gst_number")
    private String gstNumber;

    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    @Column(name = "bank_ifsc")
    private String bankIfsc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MerchantStatus status = MerchantStatus.PENDING;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

```java
// File: backend/merchant-service/src/main/java/com/payflow/merchant/model/ApiKey.java
@Entity
@Table(name = "api_keys")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiKey {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;              // SHA-256 hash of actual key

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix;            // First 8 chars for identification

    @Column(nullable = false)
    private String name;                 // "Production Key", "Test Key"

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
```

```java
// File: backend/merchant-service/src/main/java/com/payflow/merchant/model/WebhookConfig.java
@Entity
@Table(name = "webhook_configs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookConfig {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String secret;               // HMAC signing secret

    @ElementCollection
    @CollectionTable(name = "webhook_events")
    private List<String> events;         // ["payment.authorized", "payment.captured"]

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
```

```java
// File: backend/merchant-service/src/main/java/com/payflow/merchant/model/FeeConfig.java
@Entity
@Table(name = "fee_configs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FeeConfig {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private String merchantId;

    @Column(name = "platform_fee_percent", nullable = false)
    @Builder.Default
    private BigDecimal platformFeePercent = new BigDecimal("2.00");

    @Column(name = "gst_percent", nullable = false)
    @Builder.Default
    private BigDecimal gstPercent = new BigDecimal("18.00");

    @Column(name = "fixed_fee_per_txn")
    @Builder.Default
    private BigDecimal fixedFeePerTxn = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
```

### Payment Service Entities

```java
// File: backend/payment-service/src/main/java/com/payflow/payment/model/Order.java
@Entity
@Table(name = "orders")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;             // "INR", "USD"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "description")
    private String description;

    @Column(name = "receipt_number")
    private String receiptNumber;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
```

```java
// File: backend/payment-service/src/main/java/com/payflow/payment/model/Payment.java
@Entity
@Table(name = "payments")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "captured_amount")
    @Builder.Default
    private BigDecimal capturedAmount = BigDecimal.ZERO;

    @Column(name = "refunded_amount")
    @Builder.Default
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;         // AUTHORIZED, CAPTURED, FAILED, etc.

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethodType paymentMethod; // CARD, UPI, NETBANKING

    @Column(name = "auth_code")
    private String authCode;             // Bank authorization code

    @Column(name = "rrn")
    private String rrn;                  // Retrieval Reference Number

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "card_last_four")
    private String cardLastFour;

    @Column(name = "card_network")
    private String cardNetwork;          // VISA, MASTERCARD, RUPAY

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

```java
// File: backend/payment-service/src/main/java/com/payflow/payment/model/Refund.java
@Entity
@Table(name = "refunds")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Refund {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;         // PENDING, PROCESSED, FAILED

    @Column
    private String reason;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
```

---

## 🗃️ Repository Interfaces

### Identity Service Repositories

```java
// File: backend/identity-service/src/main/java/com/payflow/identity/repository/UserRepository.java
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findByRole(Role role);
    List<User> findByActiveTrue();
}

// File: backend/identity-service/src/main/java/com/payflow/identity/repository/RefreshTokenRepository.java
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByTokenAndRevokedFalse(String token);
    List<RefreshToken> findByUserIdAndRevokedFalse(String userId);
    void deleteByExpiresAtBefore(Instant date);
}
```

### Merchant Service Repositories

```java
// File: backend/merchant-service/src/main/java/com/payflow/merchant/repository/MerchantRepository.java
public interface MerchantRepository extends JpaRepository<Merchant, String> {
    Optional<Merchant> findByUserId(String userId);
    boolean existsByUserId(String userId);
    List<Merchant> findByStatus(MerchantStatus status);
}

// File: backend/merchant-service/src/main/java/com/payflow/merchant/repository/ApiKeyRepository.java
public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {
    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);
    List<ApiKey> findByMerchantIdAndActiveTrue(String merchantId);
    long countByMerchantIdAndActiveTrue(String merchantId);
}

// File: backend/merchant-service/src/main/java/com/payflow/merchant/repository/WebhookConfigRepository.java
public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, String> {
    Optional<WebhookConfig> findByMerchantIdAndActiveTrue(String merchantId);
    List<WebhookConfig> findByMerchantId(String merchantId);
}

// File: backend/merchant-service/src/main/java/com/payflow/merchant/repository/FeeConfigRepository.java
public interface FeeConfigRepository extends JpaRepository<FeeConfig, String> {
    Optional<FeeConfig> findByMerchantId(String merchantId);
}
```

### Payment Service Repositories

```java
// File: backend/payment-service/src/main/java/com/payflow/payment/repository/OrderRepository.java
public interface OrderRepository extends JpaRepository<Order, String> {
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
    List<Order> findByMerchantId(String merchantId);
    List<Order> findByMerchantIdAndStatus(String merchantId, OrderStatus status);
    Page<Order> findByMerchantId(String merchantId, Pageable pageable);
}

// File: backend/payment-service/src/main/java/com/payflow/payment/repository/PaymentRepository.java
public interface PaymentRepository extends JpaRepository<Payment, String> {
    Optional<Payment> findByOrderId(String orderId);
    List<Payment> findByMerchantIdAndStatus(String merchantId, PaymentStatus status);
    List<Payment> findByStatusAndCreatedAtBetween(PaymentStatus status, Instant start, Instant end);
    Page<Payment> findByMerchantId(String merchantId, Pageable pageable);
}

// File: backend/payment-service/src/main/java/com/payflow/payment/repository/RefundRepository.java
public interface RefundRepository extends JpaRepository<Refund, String> {
    List<Refund> findByPaymentId(String paymentId);
    Optional<Refund> findByIdempotencyKey(String idempotencyKey);
    BigDecimal sumAmountByPaymentId(String paymentId);
}
```

---

## ⚙️ Service Method Signatures

### Identity Service — AuthService

```java
// File: backend/identity-service/src/main/java/com/payflow/identity/service/AuthService.java
@Service
@RequiredArgsConstructor
public class AuthService {
    public AuthResponse register(RegisterRequest request);
    public AuthResponse login(LoginRequest request);
    public AuthResponse refreshToken(RefreshRequest request);
    public UserProfileResponse getProfile(String userId);
    private AuthResponse generateAuthResponse(User user);
}
```

### Identity Service — JwtService

```java
// File: backend/identity-service/src/main/java/com/payflow/identity/service/JwtService.java
@Service
public class JwtService {
    public String generateAccessToken(String userId, String email, String role);
    public String generateRefreshToken(String userId);
    public Claims extractClaims(String token);
    public String extractUserId(String token);
    public boolean isTokenValid(String token);
    public long getAccessTokenExpirationSeconds();
    private SecretKey getSigningKey();
}
```

### Merchant Service — MerchantService

```java
// File: backend/merchant-service/src/main/java/com/payflow/merchant/service/MerchantService.java
@Service
@RequiredArgsConstructor
public class MerchantService {
    public MerchantResponse createMerchant(String userId, CreateMerchantRequest request);
    public MerchantResponse getMerchant(String merchantId);
    public MerchantResponse getMerchantByUserId(String userId);
    public MerchantResponse updateMerchant(String merchantId, UpdateMerchantRequest request);
    public void activateMerchant(String merchantId);
    public List<MerchantResponse> listMerchants(MerchantStatus status);
}
```

### Merchant Service — ApiKeyService

```java
// File: backend/merchant-service/src/main/java/com/payflow/merchant/service/ApiKeyService.java
@Service
@RequiredArgsConstructor
public class ApiKeyService {
    public ApiKeyResponse generateApiKey(String merchantId, String keyName);
    public ApiKeyValidationResponse validateApiKey(String rawApiKey);
    public List<ApiKeyResponse> listApiKeys(String merchantId);
    public void revokeApiKey(String keyId);
    private String hashApiKey(String rawKey);  // SHA-256
}
```

### Payment Service — PaymentService

```java
// File: backend/payment-service/src/main/java/com/payflow/payment/service/PaymentService.java
@Service
@RequiredArgsConstructor
public class PaymentService {
    public OrderResponse createOrder(String merchantId, CreateOrderRequest request);
    public PaymentResponse authorizePayment(String orderId, AuthorizeRequest request);
    public PaymentResponse capturePayment(String paymentId, CaptureRequest request);
    public PaymentResponse voidPayment(String paymentId);
    public RefundResponse refundPayment(String paymentId, RefundRequest request);
    public OrderResponse getOrder(String orderId);
    public PaymentResponse getPayment(String paymentId);
    public Page<OrderResponse> listOrders(String merchantId, Pageable pageable);
}
```

### Payment Service — IdempotencyService

```java
// File: backend/payment-service/src/main/java/com/payflow/payment/service/IdempotencyService.java
@Service
@RequiredArgsConstructor
public class IdempotencyService {
    public <T> Optional<T> checkIdempotency(String key, Class<T> responseType);
    public <T> void saveIdempotencyResponse(String key, T response, Duration ttl);
    public boolean acquireLock(String key, Duration lockDuration);
    public void releaseLock(String key);
}
```

### Routing Service — RoutingService

```java
// File: backend/routing-service/src/main/java/com/payflow/routing/service/RoutingService.java
@Service
@RequiredArgsConstructor
public class RoutingService {
    public RoutingResponse routePayment(RoutingRequest request);
    public RoutingResponse routeCapture(CaptureRoutingRequest request);
    public RoutingResponse routeRefund(RefundRoutingRequest request);
    private BankRoute selectRoute(RoutingRequest request);
    private Iso8583Message buildAuthMessage(RoutingRequest request);
    private RoutingResponse parseResponse(Iso8583Message bankResponse);
}
```

---

## 🌐 Controller Endpoints

### Identity Service Endpoints

```java
// File: backend/identity-service/src/main/java/com/payflow/identity/controller/AuthController.java
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    @PostMapping("/register")     → 201 Created → AuthResponse
    @PostMapping("/login")        → 200 OK     → AuthResponse
    @PostMapping("/refresh")      → 200 OK     → AuthResponse
    @GetMapping("/profile")       → 200 OK     → UserProfileResponse
    @PostMapping("/logout")       → 204 No Content
}
```

### Merchant Service Endpoints

```java
// File: backend/merchant-service/src/main/java/com/payflow/merchant/controller/MerchantController.java
@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {
    @PostMapping                   → 201 Created → MerchantResponse
    @GetMapping("/{id}")          → 200 OK     → MerchantResponse
    @PutMapping("/{id}")          → 200 OK     → MerchantResponse
    @GetMapping("/me")            → 200 OK     → MerchantResponse
    @PostMapping("/{id}/activate")→ 200 OK     → MerchantResponse
}

// File: backend/merchant-service/src/main/java/com/payflow/merchant/controller/ApiKeyController.java
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/api-keys")
public class ApiKeyController {
    @PostMapping                   → 201 Created → ApiKeyResponse (raw key shown ONCE)
    @GetMapping                    → 200 OK     → List<ApiKeyResponse>
    @DeleteMapping("/{keyId}")    → 204 No Content
    @PostMapping("/validate")     → 200 OK     → ApiKeyValidationResponse
}
```

### Payment Service Endpoints

```java
// File: backend/payment-service/src/main/java/com/payflow/payment/controller/OrderController.java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    @PostMapping                   → 201 Created → OrderResponse
    @GetMapping("/{id}")          → 200 OK     → OrderResponse
    @GetMapping                    → 200 OK     → Page<OrderResponse>
}

// File: backend/payment-service/src/main/java/com/payflow/payment/controller/PaymentController.java
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    @PostMapping("/{orderId}/authorize") → 200 OK → PaymentResponse
    @PostMapping("/{id}/capture")        → 200 OK → PaymentResponse
    @PostMapping("/{id}/void")           → 200 OK → PaymentResponse
    @PostMapping("/{id}/refund")         → 200 OK → RefundResponse
    @GetMapping("/{id}")                 → 200 OK → PaymentResponse
}
```

### Routing Service Endpoints

```java
// File: backend/routing-service/src/main/java/com/payflow/routing/controller/RoutingController.java
@RestController
@RequestMapping("/api/v1/routing")
public class RoutingController {
    @PostMapping("/authorize")    → 200 OK → RoutingResponse
    @PostMapping("/capture")      → 200 OK → RoutingResponse
    @PostMapping("/refund")       → 200 OK → RoutingResponse
    @GetMapping("/health")        → 200 OK → BankHealthResponse
}
```

---

## ⚙️ Spring Cloud Configurations

### Service Registry (Eureka Server) — application.yml

```yaml
# File: backend/service-registry/src/main/resources/application.yml
server:
  port: 8761

spring:
  application:
    name: service-registry

eureka:
  instance:
    hostname: localhost
  client:
    register-with-eureka: false    # Server doesn't register with itself
    fetch-registry: false          # Server doesn't need to fetch
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000
```

### Config Server — application.yml

```yaml
# File: backend/config-server/src/main/resources/application.yml
server:
  port: 8888

spring:
  application:
    name: config-server
  cloud:
    config:
      server:
        native:
          search-locations: classpath:/configs
  profiles:
    active: native

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka
```

### API Gateway — application.yml

```yaml
# File: backend/api-gateway/src/main/resources/application.yml
server:
  port: 8080

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      default-filters:
        - name: RequestLogging
      routes:
        - id: identity-public
          uri: lb://identity-service
          predicates:
            - Path=/api/v1/auth/**
        - id: merchant-service
          uri: lb://merchant-service
          predicates:
            - Path=/api/v1/merchants/**
        - id: payment-service
          uri: lb://payment-service
          predicates:
            - Path=/api/v1/payments/**, /api/v1/orders/**
  data:
    redis:
      host: localhost
      port: 6379

jwt:
  secret: ${JWT_SECRET:your-256-bit-secret-key-for-signing}

rate-limit:
  default-limit: 100
  default-duration: 60

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka
```

### Payment Service — application.yml

```yaml
# File: backend/payment-service/src/main/resources/application.yml
server:
  port: 8083

spring:
  application:
    name: payment-service
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_payment
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka

feign:
  client:
    config:
      routing-service:
        connect-timeout: 5000
        read-timeout: 30000
```

---

## 🗂️ Flyway Migration Summary

### Identity Service Migrations

```sql
-- V1__create_users_table.sql
CREATE TABLE users (
    id          VARCHAR(36) PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name   VARCHAR(255) NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'USER',
    active      BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);

-- V2__create_refresh_tokens_table.sql
CREATE TABLE refresh_tokens (
    id          VARCHAR(36) PRIMARY KEY,
    token       VARCHAR(255) NOT NULL UNIQUE,
    user_id     VARCHAR(36) NOT NULL REFERENCES users(id),
    expires_at  TIMESTAMP NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
```

### Payment Service Migrations

```sql
-- V1__create_orders_table.sql
CREATE TABLE orders (
    id              VARCHAR(36) PRIMARY KEY,
    merchant_id     VARCHAR(36) NOT NULL,
    amount          DECIMAL(15,2) NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'INR',
    status          VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    customer_email  VARCHAR(255),
    customer_phone  VARCHAR(20),
    description     VARCHAR(500),
    receipt_number  VARCHAR(100),
    idempotency_key VARCHAR(255) UNIQUE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP
);

CREATE INDEX idx_orders_merchant ON orders(merchant_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_idempotency ON orders(idempotency_key);

-- V2__create_payments_table.sql
CREATE TABLE payments (
    id               VARCHAR(36) PRIMARY KEY,
    order_id         VARCHAR(36) NOT NULL REFERENCES orders(id),
    merchant_id      VARCHAR(36) NOT NULL,
    amount           DECIMAL(15,2) NOT NULL,
    captured_amount  DECIMAL(15,2) DEFAULT 0,
    refunded_amount  DECIMAL(15,2) DEFAULT 0,
    currency         VARCHAR(3) NOT NULL DEFAULT 'INR',
    status           VARCHAR(20) NOT NULL,
    payment_method   VARCHAR(20),
    auth_code        VARCHAR(50),
    rrn              VARCHAR(50),
    bank_reference   VARCHAR(100),
    failure_reason   VARCHAR(500),
    card_last_four   VARCHAR(4),
    card_network     VARCHAR(20),
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_merchant ON payments(merchant_id);
CREATE INDEX idx_payments_status ON payments(status);

-- V3__create_refunds_table.sql
CREATE TABLE refunds (
    id              VARCHAR(36) PRIMARY KEY,
    payment_id      VARCHAR(36) NOT NULL REFERENCES payments(id),
    order_id        VARCHAR(36) NOT NULL REFERENCES orders(id),
    amount          DECIMAL(15,2) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason          VARCHAR(500),
    bank_reference  VARCHAR(100),
    idempotency_key VARCHAR(255) UNIQUE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refunds_payment ON refunds(payment_id);
```

---

## 📡 ISO 8583 Class Design

### Class Hierarchy

```
┌─────────────────────────────────────────────────────────────┐
│                    ISO 8583 Package                           │
│  com.payflow.routing.iso8583                                 │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─────────────────┐    ┌─────────────────────────────────┐│
│  │ Iso8583Message  │    │ Iso8583MessageBuilder            ││
│  │                 │    │                                   ││
│  │ - mti: String   │    │ + withMti(String)                ││
│  │ - fields: Map   │    │ + withField(int, String)         ││
│  │ - bitmap: byte[]│    │ + withPan(String)                ││
│  │                 │    │ + withAmount(BigDecimal)          ││
│  │ + getField(int) │    │ + withCurrency(String)           ││
│  │ + setField(int) │    │ + withMerchantId(String)         ││
│  │ + hasField(int) │    │ + withRrn(String)                ││
│  └─────────────────┘    │ + build(): Iso8583Message        ││
│                          └─────────────────────────────────┘│
│                                                               │
│  ┌─────────────────────┐  ┌────────────────────────────────┐│
│  │ Iso8583MessageParser│  │ Iso8583Constants                ││
│  │                     │  │                                  ││
│  │ + parse(byte[])     │  │ + MTI_AUTH_REQUEST = "0100"     ││
│  │   : Iso8583Message  │  │ + MTI_AUTH_RESPONSE = "0110"   ││
│  │                     │  │ + MTI_CAPTURE_REQUEST = "0220"  ││
│  │ - parseMti(buf)     │  │ + MTI_CAPTURE_RESPONSE = "0230"││
│  │ - parseBitmap(buf)  │  │ + MTI_REVERSAL_REQUEST = "0420"││
│  │ - parseFields(buf)  │  │ + MTI_REVERSAL_RESPONSE = "0430"│
│  └─────────────────────┘  │                                  ││
│                            │ + FIELD_PAN = 2                 ││
│  ┌─────────────────────┐  │ + FIELD_AMOUNT = 4             ││
│  │ Iso8583Field        │  │ + FIELD_TRANSMISSION_DATE = 7  ││
│  │                     │  │ + FIELD_STAN = 11              ││
│  │ - number: int       │  │ + FIELD_AUTH_CODE = 38         ││
│  │ - name: String      │  │ + FIELD_RESPONSE_CODE = 39    ││
│  │ - type: FieldType   │  │ + FIELD_TERMINAL_ID = 41      ││
│  │ - maxLength: int    │  │ + FIELD_MERCHANT_ID = 42      ││
│  │ - lengthType: FIXED │  │ + FIELD_CURRENCY = 49         ││
│  │              /LLVAR  │  │                                  ││
│  │              /LLLVAR │  │ + RESP_APPROVED = "00"         ││
│  └─────────────────────┘  │ + RESP_DECLINED = "05"         ││
│                            │ + RESP_INSUFFICIENT = "51"     ││
│  ┌─────────────────────┐  │ + RESP_EXPIRED = "54"          ││
│  │ BitmapUtils         │  │ + RESP_TIMEOUT = "68"          ││
│  │                     │  └────────────────────────────────┘│
│  │ + setFieldPresent() │                                     │
│  │ + isFieldPresent()  │                                     │
│  │ + bitmapToHex()     │                                     │
│  │ + hexToBitmap()     │                                     │
│  └─────────────────────┘                                     │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### ISO 8583 Message Structure

```
┌────────────────────────────────────────────────────────────────┐
│                    ISO 8583 MESSAGE FORMAT                       │
├────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────┬──────────────────┬─────────────────────────────┐ │
│  │   MTI    │     BITMAP       │         DATA FIELDS          │ │
│  │ (4 bytes)│   (8/16 bytes)   │    (variable length)         │ │
│  └──────────┴──────────────────┴─────────────────────────────┘ │
│                                                                  │
│  Example Authorization Request (MTI 0100):                       │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 0100                                                      │  │
│  │ 7234000000000000  (bitmap: fields 2,3,4,7,11,41,42,49)  │  │
│  │ Field 2:  4111111111111111   (PAN - card number)         │  │
│  │ Field 3:  000000              (Processing code)           │  │
│  │ Field 4:  000000010000        (Amount: ₹100.00)          │  │
│  │ Field 7:  0615120000          (Transmission date/time)    │  │
│  │ Field 11: 123456              (STAN)                      │  │
│  │ Field 41: TERM0001            (Terminal ID)               │  │
│  │ Field 42: MERCHANT000001      (Merchant ID)              │  │
│  │ Field 49: 356                 (Currency code: INR)        │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
└────────────────────────────────────────────────────────────────┘
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | Maven Multi-Module | Parent POM manages versions, children inherit |
| 2 | JPA Entities | @Builder + @Data + UUID IDs + timestamp auditing |
| 3 | Repository Pattern | Spring Data JPA query methods + custom queries |
| 4 | Service Layer | Business logic separated from controllers |
| 5 | Controller Design | RESTful endpoints with proper HTTP status codes |
| 6 | Spring Cloud Config | Centralized YAML, per-service override |
| 7 | Flyway Migrations | Versioned SQL scripts for schema evolution |
| 8 | ISO 8583 Design | Builder pattern for message construction |
| 9 | Entity Relationships | Orders → Payments → Refunds hierarchy |
| 10 | Idempotency Pattern | Redis-backed lock + response cache |

---

## 📚 Document Index

| # | Document | Description |
|---|----------|-------------|
| 1 | [phase1-system-design.md](./phase1-system-design.md) | System design decisions |
| 2 | [phase2-high-level-design.md](./phase2-high-level-design.md) | HLD with sequence diagrams |
| 3 | [phase3-low-level-design.md](./phase3-low-level-design.md) | **This document** — LLD |
| 4 | [phase4-part01-parent-pom-and-maven-setup.md](./phase4-part01-parent-pom-and-maven-setup.md) | Maven setup |
| 5 | [phase4-part02-common-lib.md](./phase4-part02-common-lib.md) | Common library |

---

## 🚀 Next Steps

1. **Phase 4** — Begin implementation following the signatures defined above
2. Implement entities first, then repositories, then services, then controllers
3. Write Flyway migrations before JPA entities to ensure schema is correct
4. Use MapStruct for DTO mapping between layers

---

*"Make it work, make it right, make it fast — in that order."* — Kent Beck
