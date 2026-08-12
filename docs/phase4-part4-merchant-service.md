# Phase 4 Part 4: Merchant Service Implementation

## Overview

The Merchant Service handles merchant onboarding, API key lifecycle management, webhook configuration, and fee setup. It's the business operations backbone of PayFlow.

## Project Structure

```
merchant-service/
├── src/main/java/com/payflow/merchant/
│   ├── MerchantServiceApplication.java
│   ├── config/
│   │   └── SecurityConfig.java
│   ├── controller/
│   │   ├── MerchantController.java
│   │   ├── ApiKeyController.java
│   │   ├── WebhookController.java
│   │   └── InternalController.java
│   ├── dto/
│   │   ├── request/
│   │   └── response/
│   ├── entity/
│   │   ├── Merchant.java
│   │   ├── ApiKey.java
│   │   ├── WebhookConfig.java
│   │   └── FeeConfig.java
│   ├── repository/
│   ├── service/
│   │   ├── MerchantService.java
│   │   ├── ApiKeyService.java
│   │   └── WebhookConfigService.java
│   └── util/
│       └── ApiKeyGenerator.java
└── src/main/resources/
    ├── application.yml
    └── db/migration/
```

## API Key Generation (SHA-256)

```java
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final MerchantRepository merchantRepository;

    /**
     * API Key Format:
     * - Live: pk_live_<32 random chars>  (public key for client-side)
     * - Test: pk_test_<32 random chars>
     * - Secret: sk_live_<32 random chars> (server-side only)
     *
     * Storage: Only SHA-256 hash is stored. Full key shown once at creation.
     */
    public ApiKeyResponse generateApiKey(UUID merchantId, KeyType type) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new MerchantNotActiveException(merchantId);
        }

        // Generate the raw key
        String prefix = type == KeyType.LIVE ? "pk_live_" : "pk_test_";
        String randomPart = generateSecureRandom(32);
        String fullKey = prefix + randomPart;

        // Store only the hash
        String keyHash = sha256Hash(fullKey);
        String displayPrefix = prefix + randomPart.substring(0, 8);

        ApiKey apiKey = ApiKey.builder()
            .merchant(merchant)
            .keyPrefix(displayPrefix)    // "pk_live_abc12345" (for display)
            .keyHash(keyHash)            // SHA-256 of full key
            .type(type)
            .status(KeyStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .build();

        apiKeyRepository.save(apiKey);

        // Return full key only this once
        return new ApiKeyResponse(
            apiKey.getId().toString(),
            fullKey,             // ← shown only at creation time
            displayPrefix,
            type.name(),
            "ACTIVE",
            apiKey.getCreatedAt().toString()
        );
    }

    public MerchantValidationResponse validateApiKey(String rawKey) {
        String keyHash = sha256Hash(rawKey);

        ApiKey apiKey = apiKeyRepository.findByKeyHashAndStatus(
                keyHash, KeyStatus.ACTIVE)
            .orElseThrow(() -> new InvalidApiKeyException());

        return new MerchantValidationResponse(
            apiKey.getMerchant().getId().toString(),
            apiKey.getMerchant().getBusinessName(),
            apiKey.getType().name()
        );
    }

    public void revokeApiKey(UUID merchantId, UUID keyId) {
        ApiKey apiKey = apiKeyRepository.findById(keyId)
            .orElseThrow(() -> new ApiKeyNotFoundException(keyId));

        if (!apiKey.getMerchant().getId().equals(merchantId)) {
            throw new UnauthorizedAccessException();
        }

        apiKey.setStatus(KeyStatus.REVOKED);
        apiKey.setRevokedAt(LocalDateTime.now());
        apiKeyRepository.save(apiKey);
    }

    private String generateSecureRandom(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(bytes).substring(0, length);
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
```

## Merchant Onboarding Service

```java
@Service
@RequiredArgsConstructor
@Transactional
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final FeeConfigRepository feeConfigRepository;
    private final ApiKeyService apiKeyService;

    public MerchantResponse createMerchant(UUID userId,
                                           CreateMerchantRequest request) {
        // Check if user already has a merchant account
        if (merchantRepository.existsByUserId(userId)) {
            throw new MerchantAlreadyExistsException(userId);
        }

        Merchant merchant = Merchant.builder()
            .userId(userId)
            .businessName(request.businessName())
            .businessType(request.businessType())
            .mccCode(request.mccCode())
            .gstin(request.gstin())
            .panNumber(request.panNumber())
            .settlementAccountNumber(request.accountNumber())
            .settlementIfsc(request.ifscCode())
            .status(MerchantStatus.ACTIVE)  // Auto-approve for demo
            .createdAt(LocalDateTime.now())
            .build();

        merchant = merchantRepository.save(merchant);

        // Create default fee configuration
        createDefaultFeeConfig(merchant);

        return MerchantResponse.from(merchant);
    }

    private void createDefaultFeeConfig(Merchant merchant) {
        List<FeeConfig> defaultFees = List.of(
            FeeConfig.builder()
                .merchant(merchant)
                .paymentMethod(PaymentMethod.CARD)
                .mdrPercent(new BigDecimal("2.00"))
                .fixedFee(BigDecimal.ZERO)
                .gstPercent(new BigDecimal("18.00"))
                .effectiveFrom(LocalDateTime.now())
                .build(),
            FeeConfig.builder()
                .merchant(merchant)
                .paymentMethod(PaymentMethod.UPI)
                .mdrPercent(new BigDecimal("0.50"))
                .fixedFee(BigDecimal.ZERO)
                .gstPercent(new BigDecimal("18.00"))
                .effectiveFrom(LocalDateTime.now())
                .build(),
            FeeConfig.builder()
                .merchant(merchant)
                .paymentMethod(PaymentMethod.NET_BANKING)
                .mdrPercent(new BigDecimal("1.50"))
                .fixedFee(new BigDecimal("5.00"))
                .gstPercent(new BigDecimal("18.00"))
                .effectiveFrom(LocalDateTime.now())
                .build()
        );
        feeConfigRepository.saveAll(defaultFees);
    }
}
```

## Webhook Configuration

```java
@Service
@RequiredArgsConstructor
public class WebhookConfigService {

    private final WebhookConfigRepository webhookConfigRepository;
    private final MerchantRepository merchantRepository;

    /**
     * Supported events:
     * - payment.created
     * - payment.authorized
     * - payment.captured
     * - payment.failed
     * - refund.initiated
     * - refund.completed
     * - settlement.completed
     */
    public WebhookConfigResponse createWebhook(UUID merchantId,
                                                WebhookConfigRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        // Generate webhook secret for HMAC signing
        String webhookSecret = "whsec_" + generateSecureRandom(32);

        WebhookConfig config = WebhookConfig.builder()
            .merchant(merchant)
            .url(request.url())
            .secret(webhookSecret)
            .events(String.join(",", request.events()))
            .status(WebhookStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .build();

        config = webhookConfigRepository.save(config);

        return new WebhookConfigResponse(
            config.getId().toString(),
            config.getUrl(),
            webhookSecret,  // Shown only once
            request.events(),
            "ACTIVE"
        );
    }

    public List<WebhookConfigResponse> getWebhooks(UUID merchantId) {
        return webhookConfigRepository
            .findByMerchantId(merchantId).stream()
            .map(wh -> new WebhookConfigResponse(
                wh.getId().toString(),
                wh.getUrl(),
                "whsec_****" + wh.getSecret().substring(
                    wh.getSecret().length() - 4),
                List.of(wh.getEvents().split(",")),
                wh.getStatus().name()
            ))
            .toList();
    }
}
```

## Fee Configuration

| Payment Method | MDR (%) | Fixed Fee (₹) | GST on MDR (%) |
|---------------|---------|---------------|----------------|
| Card (Visa/MC) | 2.00 | 0.00 | 18.00 |
| UPI | 0.50 | 0.00 | 18.00 |
| Net Banking | 1.50 | 5.00 | 18.00 |
| Debit Card | 0.90 | 0.00 | 18.00 |

### Fee Calculation Example

```
Transaction Amount: ₹1,000 (Card payment)
MDR: ₹1,000 × 2% = ₹20.00
GST on MDR: ₹20 × 18% = ₹3.60
Total Deduction: ₹23.60
Merchant Receives: ₹976.40
```

## Internal API (Service-to-Service)

```java
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final ApiKeyService apiKeyService;
    private final MerchantRepository merchantRepository;
    private final FeeConfigRepository feeConfigRepository;

    /**
     * Called by API Gateway to validate API keys.
     * Not exposed externally (internal network only).
     */
    @GetMapping("/validate-key")
    public MerchantValidationResponse validateKey(
            @RequestParam String key) {
        return apiKeyService.validateApiKey(key);
    }

    /**
     * Called by Settlement Service to get fee configuration.
     */
    @GetMapping("/merchants/{id}/fees")
    public List<FeeConfigResponse> getMerchantFees(
            @PathVariable UUID id,
            @RequestParam PaymentMethod paymentMethod) {
        return feeConfigRepository
            .findByMerchantIdAndPaymentMethod(id, paymentMethod)
            .stream()
            .map(FeeConfigResponse::from)
            .toList();
    }

    /**
     * Called by Payment Service to get merchant details.
     */
    @GetMapping("/merchants/{id}")
    public MerchantResponse getMerchant(@PathVariable UUID id) {
        Merchant merchant = merchantRepository.findById(id)
            .orElseThrow(() -> new MerchantNotFoundException(id));
        return MerchantResponse.from(merchant);
    }
}
```

## Database Migration

```sql
-- V1__create_merchants_table.sql
CREATE TABLE merchants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    business_name VARCHAR(255) NOT NULL,
    business_type VARCHAR(100),
    mcc_code VARCHAR(4),
    gstin VARCHAR(15),
    pan_number VARCHAR(10),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    settlement_account_number VARCHAR(20),
    settlement_ifsc VARCHAR(11),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- V2__create_api_keys_table.sql
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    key_prefix VARCHAR(50) NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP
);

CREATE INDEX idx_api_keys_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_merchant ON api_keys(merchant_id);

-- V3__create_webhook_configs_table.sql
CREATE TABLE webhook_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    url VARCHAR(500) NOT NULL,
    secret VARCHAR(100) NOT NULL,
    events TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- V4__create_fee_configs_table.sql
CREATE TABLE fee_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    payment_method VARCHAR(50) NOT NULL,
    mdr_percent DECIMAL(5,2) NOT NULL,
    fixed_fee DECIMAL(10,2) NOT NULL DEFAULT 0,
    gst_percent DECIMAL(5,2) NOT NULL DEFAULT 18.00,
    effective_from TIMESTAMP NOT NULL,
    UNIQUE(merchant_id, payment_method)
);
```
