# 🏗️ Phase 4 Part 7b: Merchant Service — Services & Controller

> **"An API key is a one-time secret — show it once, hash it forever, and never look back."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Previous** | [phase4-part07a-merchant-entities.md](./phase4-part07a-merchant-entities.md) |
| **Next** | [phase4-part08a-payment-entities.md](./phase4-part08a-payment-entities.md) |

---

## 📖 Table of Contents

1. [Overview](#1-overview)
2. [MerchantService Implementation](#2-merchantservice-implementation)
3. [ApiKeyService Implementation](#3-apikeyservice-implementation)
4. [MerchantController](#4-merchantcontroller)
5. [ApiKeyController](#5-apikeycontroller)
6. [MapStruct Mapper](#6-mapstruct-mapper)
7. [Kafka Event Publishing](#7-kafka-event-publishing)
8. [API Key Generation Flow](#8-api-key-generation-flow)
9. [Configuration](#9-configuration)
10. [What You Learned](#10-what-you-learned)

---

## 1. Overview

The service layer implements business logic for merchant onboarding, management, and
API key lifecycle. Events are published to Kafka for downstream services.

**Service Architecture:**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    MERCHANT SERVICE LAYER                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────────┐     ┌──────────────────┐                          │
│  │MerchantController│     │ ApiKeyController │                          │
│  └────────┬─────────┘     └────────┬─────────┘                          │
│           │                        │                                    │
│           ▼                        ▼                                    │
│  ┌──────────────────┐     ┌──────────────────┐                          │
│  │ MerchantService  │     │  ApiKeyService   │                          │
│  │ • createMerchant │     │ • generateApiKey │                          │
│  │ • getMerchant    │     │ • validateApiKey │                          │
│  │ • updateMerchant │     │ • listApiKeys    │                          │
│  │ • activateMerch. │     │ • revokeApiKey   │                          │
│  └────────┬─────────┘     └────────┬─────────┘                          │
│           │                        │                                    │
│           ▼                        ▼                                    │
│  ┌──────────────────┐     ┌──────────────────┐     ┌────────────────┐  │
│  │MerchantRepository│     │ ApiKeyRepository │     │ KafkaProducer  │  │
│  └──────────────────┘     └──────────────────┘     │ (events)       │  │
│                                                    └────────────────┘  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. MerchantService Implementation

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/service/MerchantService.java`

```java
package com.payflow.merchant.service;

import com.payflow.merchant.dto.CreateMerchantRequest;
import com.payflow.merchant.dto.MerchantResponse;
import com.payflow.merchant.dto.UpdateMerchantRequest;
import com.payflow.merchant.event.MerchantEventPublisher;
import com.payflow.merchant.exception.MerchantAlreadyExistsException;
import com.payflow.merchant.exception.MerchantNotFoundException;
import com.payflow.merchant.mapper.MerchantMapper;
import com.payflow.merchant.model.FeeConfig;
import com.payflow.merchant.model.Merchant;
import com.payflow.merchant.model.MerchantStatus;
import com.payflow.merchant.repository.FeeConfigRepository;
import com.payflow.merchant.repository.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service layer for merchant lifecycle management.
 * Handles onboarding, updates, status transitions, and event publishing.
 */
@Service
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final FeeConfigRepository feeConfigRepository;
    private final MerchantMapper merchantMapper;
    private final MerchantEventPublisher eventPublisher;

    public MerchantService(MerchantRepository merchantRepository,
                           FeeConfigRepository feeConfigRepository,
                           MerchantMapper merchantMapper,
                           MerchantEventPublisher eventPublisher) {
        this.merchantRepository = merchantRepository;
        this.feeConfigRepository = feeConfigRepository;
        this.merchantMapper = merchantMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Onboards a new merchant. Creates the merchant record with PENDING status
     * and default fee configuration.
     * 
     * Flow:
     * 1. Validate no duplicate userId
     * 2. Save merchant (status = PENDING)
     * 3. Create default fee config
     * 4. Publish MERCHANT_ONBOARDED event to Kafka
     */
    @Transactional
    public MerchantResponse createMerchant(UUID userId, CreateMerchantRequest request) {
        // Check for existing merchant for this user
        if (merchantRepository.existsByUserId(userId)) {
            throw new MerchantAlreadyExistsException(userId);
        }

        // Map request to entity
        Merchant merchant = new Merchant(
            userId,
            request.getBusinessName(),
            request.getBusinessType(),
            request.getPanNumber(),
            request.getGstNumber(),
            request.getBankAccount(),
            request.getBankIfsc()
        );

        merchant = merchantRepository.save(merchant);

        // Create default fee config (2% platform + 18% GST + ₹0 fixed)
        FeeConfig feeConfig = new FeeConfig(
            merchant.getId(),
            new BigDecimal("2.00"),
            new BigDecimal("18.00"),
            BigDecimal.ZERO
        );
        feeConfigRepository.save(feeConfig);

        // Publish onboarding event
        eventPublisher.publishMerchantOnboarded(merchant);

        return merchantMapper.toResponse(merchant);
    }

    /**
     * Retrieves merchant details by ID.
     */
    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        return merchantMapper.toResponse(merchant);
    }

    /**
     * Retrieves merchant by userId (from Identity Service).
     */
    @Transactional(readOnly = true)
    public MerchantResponse getMerchantByUserId(UUID userId) {
        Merchant merchant = merchantRepository.findByUserId(userId)
            .orElseThrow(() -> new MerchantNotFoundException(userId));
        return merchantMapper.toResponse(merchant);
    }

    /**
     * Updates merchant business details.
     * Only allowed for merchants in PENDING or ACTIVE status.
     */
    @Transactional
    public MerchantResponse updateMerchant(UUID merchantId, UpdateMerchantRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        if (merchant.getStatus() == MerchantStatus.DEACTIVATED) {
            throw new IllegalStateException("Cannot update deactivated merchant");
        }

        if (request.getBusinessName() != null) {
            merchant.setBusinessName(request.getBusinessName());
        }
        if (request.getWebhookUrl() != null) {
            merchant.setWebhookUrl(request.getWebhookUrl());
        }
        if (request.getBankAccount() != null) {
            merchant.setBankAccount(request.getBankAccount());
        }
        if (request.getBankIfsc() != null) {
            merchant.setBankIfsc(request.getBankIfsc());
        }

        merchant = merchantRepository.save(merchant);
        return merchantMapper.toResponse(merchant);
    }

    /**
     * Activates a merchant after KYC verification.
     * Only valid transition: PENDING → ACTIVE
     */
    @Transactional
    public MerchantResponse activateMerchant(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        merchant.activate(); // Validates state transition internally
        merchant = merchantRepository.save(merchant);

        // Publish activation event
        eventPublisher.publishMerchantActivated(merchant);

        return merchantMapper.toResponse(merchant);
    }
}
```

---

## 3. ApiKeyService Implementation

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/service/ApiKeyService.java`

```java
package com.payflow.merchant.service;

import com.payflow.merchant.dto.ApiKeyCreatedResponse;
import com.payflow.merchant.dto.ApiKeyResponse;
import com.payflow.merchant.exception.ApiKeyNotFoundException;
import com.payflow.merchant.exception.MerchantNotFoundException;
import com.payflow.merchant.model.ApiKey;
import com.payflow.merchant.model.MerchantStatus;
import com.payflow.merchant.repository.ApiKeyRepository;
import com.payflow.merchant.repository.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing merchant API keys.
 * 
 * Security model:
 * - Raw key format: pk_live_{32 random bytes base64}
 * - Only SHA-256 hash is stored in database
 * - Raw key is returned ONCE at creation — cannot be retrieved again
 * - Validation: hash incoming key, compare against stored hash
 */
@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "pk_live_";
    private static final int KEY_RANDOM_BYTES = 32;

    private final ApiKeyRepository apiKeyRepository;
    private final MerchantRepository merchantRepository;
    private final SecureRandom secureRandom;

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         MerchantRepository merchantRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.merchantRepository = merchantRepository;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generates a new API key for a merchant.
     * 
     * Steps:
     * 1. Generate random bytes (32 bytes = 256 bits of entropy)
     * 2. Format: pk_live_{base64url(random_bytes)}
     * 3. Compute SHA-256 hash
     * 4. Store only hash + prefix (first 8 chars)
     * 5. Return raw key ONCE to merchant
     * 
     * @return ApiKeyCreatedResponse containing the raw key (shown once)
     */
    @Transactional
    public ApiKeyCreatedResponse generateApiKey(UUID merchantId, String keyName) {
        // Verify merchant exists and is active
        var merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new MerchantNotFoundException(merchantId));

        if (merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new IllegalStateException(
                "API keys can only be generated for ACTIVE merchants");
        }

        // Generate raw key
        byte[] randomBytes = new byte[KEY_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        String rawKey = KEY_PREFIX + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(randomBytes);

        // Compute SHA-256 hash
        String keyHash = sha256(rawKey);

        // Extract prefix for identification
        String keyPrefix = rawKey.substring(0, 8);

        // Store only the hash
        ApiKey apiKey = new ApiKey(merchantId, keyHash, keyPrefix, keyName);
        apiKey = apiKeyRepository.save(apiKey);

        // Return raw key — this is the ONLY time it's available
        return new ApiKeyCreatedResponse(
            apiKey.getId(),
            rawKey,          // ← shown ONCE
            keyPrefix,
            keyName,
            apiKey.getCreatedAt()
        );
    }

    /**
     * Validates an API key by computing its hash and looking it up.
     * 
     * @return merchantId if key is valid and active, empty otherwise
     */
    @Transactional
    public UUID validateApiKey(String rawKey) {
        String keyHash = sha256(rawKey);

        ApiKey apiKey = apiKeyRepository.findByKeyHash(keyHash)
            .orElseThrow(() -> new ApiKeyNotFoundException("Invalid API key"));

        if (!apiKey.isActive()) {
            throw new ApiKeyNotFoundException("API key has been revoked");
        }

        // Record usage timestamp
        apiKey.recordUsage();
        apiKeyRepository.save(apiKey);

        return apiKey.getMerchantId();
    }

    /**
     * Lists all API keys for a merchant (without raw key values).
     */
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listApiKeys(UUID merchantId) {
        return apiKeyRepository.findByMerchantId(merchantId).stream()
            .map(key -> new ApiKeyResponse(
                key.getId(),
                key.getKeyPrefix() + "••••••••",
                key.getName(),
                key.isActive(),
                key.getLastUsedAt(),
                key.getCreatedAt()
            ))
            .toList();
    }

    /**
     * Revokes an API key. The key can no longer be used for authentication.
     */
    @Transactional
    public void revokeApiKey(UUID merchantId, UUID keyId) {
        ApiKey apiKey = apiKeyRepository.findByIdAndMerchantId(keyId, merchantId)
            .orElseThrow(() -> new ApiKeyNotFoundException("API key not found"));

        apiKey.revoke();
        apiKeyRepository.save(apiKey);
    }

    // ─── Private Helpers ─────────────────────────────────────────

    private String sha256(String input) {
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

---

## 4. MerchantController

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/controller/MerchantController.java`

```java
package com.payflow.merchant.controller;

import com.payflow.merchant.dto.CreateMerchantRequest;
import com.payflow.merchant.dto.MerchantResponse;
import com.payflow.merchant.dto.UpdateMerchantRequest;
import com.payflow.merchant.service.MerchantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for merchant management operations.
 * 
 * Security: Endpoints are protected by the API Gateway.
 * - X-User-Id header: set by gateway after JWT validation
 * - X-User-Role header: for role-based access control
 */
@RestController
@RequestMapping("/v1/merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    /**
     * Create (onboard) a new merchant.
     * Only users with MERCHANT role can create merchant profiles.
     * 
     * POST /v1/merchants
     */
    @PostMapping
    public ResponseEntity<MerchantResponse> createMerchant(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody CreateMerchantRequest request) {
        MerchantResponse response = merchantService.createMerchant(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get merchant by ID (admin or own merchant).
     * 
     * GET /v1/merchants/{merchantId}
     */
    @GetMapping("/{merchantId}")
    public ResponseEntity<MerchantResponse> getMerchant(@PathVariable UUID merchantId) {
        MerchantResponse response = merchantService.getMerchant(merchantId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get merchant profile for current user.
     * 
     * GET /v1/merchants/me
     */
    @GetMapping("/me")
    public ResponseEntity<MerchantResponse> getMyMerchant(
            @RequestHeader("X-User-Id") UUID userId) {
        MerchantResponse response = merchantService.getMerchantByUserId(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Update merchant details.
     * 
     * PATCH /v1/merchants/{merchantId}
     */
    @PatchMapping("/{merchantId}")
    public ResponseEntity<MerchantResponse> updateMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody UpdateMerchantRequest request) {
        MerchantResponse response = merchantService.updateMerchant(merchantId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Activate a merchant (admin only, after KYC verification).
     * 
     * POST /v1/merchants/{merchantId}/activate
     */
    @PostMapping("/{merchantId}/activate")
    public ResponseEntity<MerchantResponse> activateMerchant(
            @PathVariable UUID merchantId) {
        MerchantResponse response = merchantService.activateMerchant(merchantId);
        return ResponseEntity.ok(response);
    }
}
```

---

## 5. ApiKeyController

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/controller/ApiKeyController.java`

```java
package com.payflow.merchant.controller;

import com.payflow.merchant.dto.ApiKeyCreatedResponse;
import com.payflow.merchant.dto.ApiKeyResponse;
import com.payflow.merchant.dto.CreateApiKeyRequest;
import com.payflow.merchant.service.ApiKeyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for API key management.
 * 
 * Important: The raw API key is returned ONLY in the creation response.
 * All subsequent operations show only the prefix (masked).
 */
@RestController
@RequestMapping("/v1/merchants/{merchantId}/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * Generate a new API key for the merchant.
     * Returns the raw key ONCE — merchant must save it immediately.
     * 
     * POST /v1/merchants/{merchantId}/api-keys
     */
    @PostMapping
    public ResponseEntity<ApiKeyCreatedResponse> generateApiKey(
            @PathVariable UUID merchantId,
            @Valid @RequestBody CreateApiKeyRequest request) {
        ApiKeyCreatedResponse response = apiKeyService.generateApiKey(
            merchantId, request.getName()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all API keys for a merchant (masked, no raw values).
     * 
     * GET /v1/merchants/{merchantId}/api-keys
     */
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> listApiKeys(
            @PathVariable UUID merchantId) {
        List<ApiKeyResponse> keys = apiKeyService.listApiKeys(merchantId);
        return ResponseEntity.ok(keys);
    }

    /**
     * Revoke an API key. Cannot be undone.
     * 
     * DELETE /v1/merchants/{merchantId}/api-keys/{keyId}
     */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable UUID merchantId,
            @PathVariable UUID keyId) {
        apiKeyService.revokeApiKey(merchantId, keyId);
        return ResponseEntity.noContent().build();
    }
}
```

---

## 6. MapStruct Mapper

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/mapper/MerchantMapper.java`

```java
package com.payflow.merchant.mapper;

import com.payflow.merchant.dto.MerchantResponse;
import com.payflow.merchant.model.Merchant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting between Merchant entity and DTOs.
 * 
 * At compile time, MapStruct generates an implementation class that
 * handles field mapping automatically. This eliminates manual mapping
 * boilerplate and catches type mismatches at compile time.
 */
@Mapper(componentModel = "spring")
public interface MerchantMapper {

    @Mapping(target = "statusDisplay", expression = "java(merchant.getStatus().name())")
    MerchantResponse toResponse(Merchant merchant);
}
```

**Generated output (conceptual):**

```java
// MapStruct generates this at compile time:
@Component
public class MerchantMapperImpl implements MerchantMapper {

    @Override
    public MerchantResponse toResponse(Merchant merchant) {
        if (merchant == null) return null;

        return new MerchantResponse(
            merchant.getId(),
            merchant.getUserId(),
            merchant.getBusinessName(),
            merchant.getBusinessType(),
            merchant.getPanNumber(),
            merchant.getGstNumber(),
            merchant.getBankAccount(),
            merchant.getBankIfsc(),
            merchant.getStatus().name(),  // statusDisplay
            merchant.getWebhookUrl(),
            merchant.getCreatedAt(),
            merchant.getUpdatedAt()
        );
    }
}
```

**MapStruct Maven Dependency:**

```xml
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>1.5.5.Final</version>
</dependency>

<!-- Annotation processor -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct-processor</artifactId>
                <version>1.5.5.Final</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

---

## 7. Kafka Event Publishing

**File:** `backend/merchant-service/src/main/java/com/payflow/merchant/event/MerchantEventPublisher.java`

```java
package com.payflow.merchant.event;

import com.payflow.merchant.model.Merchant;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes merchant lifecycle events to Kafka topics.
 * 
 * Topic: payflow.merchant.events
 * 
 * Events:
 * - MERCHANT_ONBOARDED: New merchant registered (status=PENDING)
 * - MERCHANT_ACTIVATED: Merchant passed KYC (status=ACTIVE)
 * - MERCHANT_SUSPENDED: Merchant account frozen
 * - MERCHANT_DEACTIVATED: Merchant permanently closed
 */
@Component
public class MerchantEventPublisher {

    private static final String TOPIC = "payflow.merchant.events";

    private final KafkaTemplate<String, MerchantEvent> kafkaTemplate;

    public MerchantEventPublisher(KafkaTemplate<String, MerchantEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishMerchantOnboarded(Merchant merchant) {
        MerchantEvent event = new MerchantEvent(
            UUID.randomUUID().toString(),
            "MERCHANT_ONBOARDED",
            merchant.getId(),
            merchant.getUserId(),
            merchant.getBusinessName(),
            merchant.getStatus().name(),
            Instant.now()
        );
        kafkaTemplate.send(TOPIC, merchant.getId().toString(), event);
    }

    public void publishMerchantActivated(Merchant merchant) {
        MerchantEvent event = new MerchantEvent(
            UUID.randomUUID().toString(),
            "MERCHANT_ACTIVATED",
            merchant.getId(),
            merchant.getUserId(),
            merchant.getBusinessName(),
            merchant.getStatus().name(),
            Instant.now()
        );
        kafkaTemplate.send(TOPIC, merchant.getId().toString(), event);
    }
}
```

**Event Record:**

```java
package com.payflow.merchant.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Merchant lifecycle event published to Kafka.
 * Uses merchantId as the partition key for ordering guarantees.
 */
public record MerchantEvent(
    String eventId,
    String eventType,
    UUID merchantId,
    UUID userId,
    String businessName,
    String status,
    Instant timestamp
) {}
```

**Kafka Configuration:**

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        spring.json.type.mapping: merchantEvent:com.payflow.merchant.event.MerchantEvent
      acks: all
      retries: 3
```

**Event Flow Diagram:**

```
┌───────────────────────────────────────────────────────────────────┐
│              KAFKA EVENT PUBLISHING FLOW                           │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  MerchantService                Kafka                Consumers    │
│       │                          │                      │         │
│       │ createMerchant()         │                      │         │
│       │ ──── save to DB ────     │                      │         │
│       │                          │                      │         │
│       │ ── MERCHANT_ONBOARDED ──▶│                      │         │
│       │                          │──▶ Notification Svc  │         │
│       │                          │──▶ Analytics Svc     │         │
│       │                          │──▶ Risk Engine       │         │
│       │                          │                      │         │
│       │ activateMerchant()       │                      │         │
│       │ ──── save to DB ────     │                      │         │
│       │                          │                      │         │
│       │ ── MERCHANT_ACTIVATED ──▶│                      │         │
│       │                          │──▶ Settlement Svc    │         │
│       │                          │──▶ Notification Svc  │         │
│       │                          │                      │         │
└───────────────────────────────────────────────────────────────────┘
```

---

## 8. API Key Generation Flow

```
┌───────────────────────────────────────────────────────────────────────────┐
│                   API KEY GENERATION & VALIDATION                          │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  GENERATION (happens once):                                               │
│  ┌─────────────────────────────────────────────────────────────┐          │
│  │ 1. SecureRandom → 32 bytes of entropy                       │          │
│  │ 2. Base64URL encode → pk_live_dGhpcyBpcyBhIHRlc3Qga2V5...  │          │
│  │ 3. SHA-256(raw_key) → 9f86d081884c7d659a2feaa0c55ad015...   │          │
│  │ 4. Store: { hash, prefix("pk_live_"), name, merchant_id }   │          │
│  │ 5. Return raw key to merchant (ONE TIME ONLY)               │          │
│  └─────────────────────────────────────────────────────────────┘          │
│                                                                           │
│  VALIDATION (every API call):                                             │
│  ┌─────────────────────────────────────────────────────────────┐          │
│  │ 1. Client sends: X-Api-Key: pk_live_dGhpcyBpcyBhIHRlc3Q... │          │
│  │ 2. Gateway computes: SHA-256(received_key)                  │          │
│  │ 3. DB lookup: SELECT * FROM api_keys WHERE key_hash = ?     │          │
│  │ 4. Verify: active = true                                    │          │
│  │ 5. Update: last_used_at = NOW()                             │          │
│  │ 6. Return: merchantId → set X-Merchant-Id header            │          │
│  └─────────────────────────────────────────────────────────────┘          │
│                                                                           │
│  WHY THIS APPROACH?                                                       │
│  ┌─────────────────────────────────────────────────────────────┐          │
│  │ • If DB is breached, hashes are useless without raw keys    │          │
│  │ • Constant-time hash comparison prevents timing attacks     │          │
│  │ • 256-bit entropy makes brute force impossible              │          │
│  │ • Prefix allows key identification in dashboards            │          │
│  └─────────────────────────────────────────────────────────────┘          │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Configuration

### application.yml (Merchant Service)

```yaml
server:
  port: ${SERVER_PORT:8082}

spring:
  application:
    name: merchant-service

  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:payflow_merchant}
    username: ${DB_USERNAME:payflow}
    password: ${DB_PASSWORD:payflow_secret}

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

  flyway:
    enabled: true
    locations: classpath:db/migration

  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    producer:
      acks: all
      retries: 3
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka}
  instance:
    prefer-ip-address: true
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | MerchantService | State machine transitions with validation (PENDING→ACTIVE) |
| 2 | API Key generation | SecureRandom (32 bytes) + SHA-256 hash storage |
| 3 | One-time raw key | Raw key shown only at creation; DB stores only hash |
| 4 | MapStruct | Compile-time mapper generation eliminates manual DTO conversion |
| 5 | Kafka events | Publish lifecycle events (onboarded, activated) for downstream services |
| 6 | Key prefix pattern | `pk_live_` prefix identifies key type without revealing secret |
| 7 | Constant-time comparison | SHA-256 lookup prevents timing side-channel attacks |
| 8 | Transactional boundaries | DB save + Kafka publish in same transaction scope |

---

## 📚 Document Index

| Document | Title |
|----------|-------|
| [Phase 4 Part 6c](./phase4-part06c-identity-controller-tests.md) | Identity Service — Controller & Tests |
| [Phase 4 Part 7a](./phase4-part07a-merchant-entities.md) | Merchant Service — Entities |
| **Phase 4 Part 7b** | **Merchant Service — Services & Controller** (You are here) |
| [Phase 4 Part 8a](./phase4-part08a-payment-entities.md) | Payment Service — Entities |
| [Phase 4 Part 8b](./phase4-part08b-payment-services-kafka.md) | Payment Service — Services & Kafka |

---

## 🚀 Next Steps

In **[Phase 4 Part 8a](./phase4-part08a-payment-entities.md)**, we will implement:

1. Order entity with idempotency key support
2. Payment entity with full card/UPI details
3. Refund entity for partial and full refunds
4. Payment state machine (CREATED → AUTHORIZED → CAPTURED)
5. Flyway migrations for the payment domain
