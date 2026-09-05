# 🏗️ Phase 4 Part 7f: Merchant Service — Services + Unit Tests (Business Logic)

> **"Services are where the real work happens. Controllers receive. Repositories store. Services THINK."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7f — Services + Unit Tests |
| **What You Build** | MerchantService.java, ApiKeyService.java, WebhookConfigService.java, MerchantServiceTest.java, ApiKeyServiceTest.java |
| **Previous** | [Part 7e — DTOs + Mapper](./phase4-part07e-merchant-dtos-mapper.md) |
| **Next** | [Part 7g — Controller + Docker](./phase4-part07g-merchant-controller-docker.md) |

---

## 📖 Table of Contents

1. [What Services Do and Why They Exist](#1-what-services-do-and-why-they-exist)
2. [Folder Structure After This Part](#2-folder-structure-after-this-part)
3. [Step-by-Step: MerchantService.java](#3-step-by-step-merchantservicejava)
4. [Step-by-Step: ApiKeyService.java](#4-step-by-step-apikeyservicejava)
5. [Step-by-Step: WebhookConfigService.java](#5-step-by-step-webhookconfigservicejava)
6. [How Unit Testing Works](#6-how-unit-testing-works)
7. [Step-by-Step: MerchantServiceTest.java](#7-step-by-step-merchantservicetestjava)
8. [Step-by-Step: ApiKeyServiceTest.java](#8-step-by-step-apikeyservicetestjava)
9. [What You Learned](#9-what-you-learned)

---

## 1. What Services Do and Why They Exist

### The Layer Rule

```
Controller: "I receive HTTP and delegate. I do NOT think."
Service:    "I contain ALL the business logic. I THINK."
Repository: "I talk to the database. I store and retrieve."
```

### Why Separate Service Layer?

| Without Service Layer | With Service Layer |
|---|---|
| Business logic in controller | Business logic in service |
| Can't test logic without HTTP | Test logic with plain Java (no server) |
| Controller becomes 500 lines | Controller stays thin (5-line methods) |
| Logic duplicated if 2 controllers need it | Service shared between controllers |

### Our 3 Services

| Service | Responsibility | Methods |
|---|---|---|
| **MerchantService** | Merchant CRUD + duplicate email check | register, get, getAll, update, deactivate |
| **ApiKeyService** | Key generation (SecureRandom + SHA-256), validation, revocation | generate, validate, getAll, revoke |
| **WebhookConfigService** | Webhook lifecycle + signing secret generation | create, getAll, getActive, update, deactivate |

---

## 2. Folder Structure After This Part

```
backend/merchant-service/src/
├── main/java/com/payflow/merchant/
│   ├── ... (everything from 7a-7e)
│   └── service/                           ← YOU CREATE THIS FOLDER
│       ├── MerchantService.java           ← YOU CREATE THIS
│       ├── ApiKeyService.java             ← YOU CREATE THIS
│       └── WebhookConfigService.java      ← YOU CREATE THIS
└── test/java/com/payflow/merchant/
    └── service/                           ← YOU CREATE THIS FOLDER
        ├── MerchantServiceTest.java       ← YOU CREATE THIS
        └── ApiKeyServiceTest.java         ← YOU CREATE THIS
```

---

## 3. Step-by-Step: MerchantService.java

**File:** `src/main/java/com/payflow/merchant/service/MerchantService.java`

### Line-by-Line

```java
package com.payflow.merchant.service;
```

Services go in the `service` sub-package.

```java
import com.payflow.common.exception.DuplicateResourceException;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.merchant.dto.MerchantRegisterRequest;
import com.payflow.merchant.dto.MerchantResponse;
import com.payflow.merchant.mapper.MerchantMapper;
import com.payflow.merchant.model.Merchant;
import com.payflow.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
```

**KEY IMPORTS:**

| Import | Why |
|---|---|
| `DuplicateResourceException` | From common-lib — thrown when email exists (→ 409) |
| `ResourceNotFoundException` | From common-lib — thrown when merchant not found (→ 404) |
| `MerchantMapper` | MapStruct-generated converter (from 7e) |
| `@Slf4j` | Lombok: generates a `log` field for logging |
| `@Transactional` | Spring: wrap method in a DB transaction |

```java
@Service
```

**WHAT:** "Spring, this is a business logic bean. Create one instance and manage it."

**SAME AS `@Component`** functionally, but communicates intent: "This class contains business logic, not HTTP handling or data access."

```java
@RequiredArgsConstructor
```

**WHAT:** Lombok generates a constructor for ALL `final` fields:

```java
// Lombok generates this:
public MerchantService(MerchantRepository merchantRepository, MerchantMapper merchantMapper) {
    this.merchantRepository = merchantRepository;
    this.merchantMapper = merchantMapper;
}
```

Spring sees this constructor and injects the matching beans automatically. This is **constructor injection** — the recommended way to inject dependencies.

```java
@Slf4j
```

**WHAT:** Lombok generates a logger:
```java
// Lombok generates this:
private static final Logger log = LoggerFactory.getLogger(MerchantService.class);
```

Now you can write `log.info(...)`, `log.warn(...)`, `log.error(...)` anywhere in the class.

**WHY LOG?** Audit trail — in a payment system, you need to know who registered when, what was updated, what was deactivated. Logs are your forensic evidence.

```java
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;
```

**`private final`:** Two things:
1. `private` — only this class can access these fields
2. `final` — must be set in constructor, never changed after (immutability)

**WHY `final`?** Guarantees these dependencies are ALWAYS present. You can't accidentally set `merchantRepository = null` somewhere. Constructor injection + final = bulletproof.

---

### Method: registerMerchant

```java
    @Transactional
    public MerchantResponse registerMerchant(MerchantRegisterRequest request) {
```

**`@Transactional`:** "Wrap this entire method in a database transaction."
- If method completes → COMMIT (changes saved permanently)
- If method throws exception → ROLLBACK (all changes undone)

**WHY?** This method does: email check + save. If save fails after the email check passed, we want clean state — not a half-done operation.

```java
        if (merchantRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Merchant", "email", request.getEmail());
        }
```

**BUSINESS RULE: Email must be unique.**

`existsByEmail` → `SELECT COUNT(*) > 0 FROM merchants WHERE email = ?`
- true → email already registered → throw 409 Conflict
- false → email is available → continue

**The exception message:** `"Merchant already exists with email: billing@rajesh.com"`
The exception handler (in common-lib or API Gateway) catches this and returns:
```json
{"success": false, "error": {"code": "DUPLICATE_RESOURCE", "message": "Merchant already exists with email: billing@rajesh.com"}}
```

```java
        Merchant merchant = merchantMapper.toEntity(request);
        Merchant saved = merchantRepository.save(merchant);
```

**LINE 1:** MapStruct converts DTO → Entity. Auto-maps name, email, businessType, mdrRate. Sets active=true. Ignores id, timestamps.

**LINE 2:** `save()` does:
- Hibernate sees `merchant.id == null` → generates UUID → executes INSERT
- After save: `saved.getId()` is now populated, `saved.getCreatedAt()` is set

```java
        log.info("Merchant registered: id={}, name={}", saved.getId(), saved.getName());
```

**STRUCTURED LOGGING:** `{}` placeholders are filled with values. More efficient than string concatenation — if log level is above INFO, the string isn't even built.

```java
        return merchantMapper.toResponse(saved);
    }
```

**MapStruct** converts Entity → Response DTO. Returns all 8 fields to the controller.

---

### Method: getMerchant

```java
    @Transactional(readOnly = true)
    public MerchantResponse getMerchant(UUID merchantId) {
```

**`readOnly = true`:** "This method only READS data — no inserts, updates, or deletes."

**WHY?** Hibernate optimization. When readOnly=true:
- Hibernate skips "dirty checking" (doesn't compare old vs new field values)
- Faster for read-only queries
- Some databases use read-only replicas for these queries

```java
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId.toString()));
        return merchantMapper.toResponse(merchant);
    }
```

**`findById()`:** Inherited from JpaRepository (free method).

**`.orElseThrow()`:** If Optional is empty (merchant not found) → throw ResourceNotFoundException (→ 404).

**This pattern appears everywhere in Spring services:**
```java
Entity entity = repository.findById(id)
    .orElseThrow(() -> new ResourceNotFoundException("Type", id.toString()));
```

---

### Method: getAllMerchants

```java
    @Transactional(readOnly = true)
    public List<MerchantResponse> getAllMerchants() {
        return merchantRepository.findAll().stream()
                .map(merchantMapper::toResponse)
                .collect(Collectors.toList());
    }
```

**LINE BY LINE:**
1. `findAll()` → `SELECT * FROM merchants` → returns `List<Merchant>`
2. `.stream()` → converts List to a Java Stream (for functional operations)
3. `.map(merchantMapper::toResponse)` → applies `toResponse()` to EACH merchant
4. `.collect(Collectors.toList())` → collects results back into a List

**`merchantMapper::toResponse`** is a **method reference** — shorthand for `m -> merchantMapper.toResponse(m)`.

**IN PLAIN ENGLISH:** "Get all merchants from DB, convert each to a response DTO, collect into a list."

---

### Method: updateMerchant

```java
    @Transactional
    public MerchantResponse updateMerchant(UUID merchantId, MerchantRegisterRequest request) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId.toString()));
```

First: find the existing merchant (or throw 404).

```java
        if (!merchant.getEmail().equals(request.getEmail()) &&
                merchantRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Merchant", "email", request.getEmail());
        }
```

**SMART DUPLICATE CHECK:** Only check email uniqueness IF the email is actually changing.

```
Scenario 1: Merchant keeps same email, changes name
  merchant.email = "shop@x.com"
  request.email  = "shop@x.com"
  → equals() = true → SKIP check (no conflict possible with yourself)

Scenario 2: Merchant changes email to a new one
  merchant.email = "shop@x.com"
  request.email  = "new@y.com"
  → equals() = false → CHECK if "new@y.com" is taken
  → If taken → 409 Conflict
  → If not taken → continue

Scenario 3: Merchant changes email to someone else's email
  merchant.email = "shop@x.com"
  request.email  = "taken@z.com"  (belongs to another merchant)
  → equals() = false → CHECK → existsByEmail = true → 409 Conflict
```

**WHY NOT JUST ALWAYS CHECK?** If merchant keeps the same email and you always check, `existsByEmail` returns true (for THEIR OWN record) → false conflict!

```java
        merchantMapper.updateEntity(request, merchant);
        Merchant updated = merchantRepository.save(merchant);

        log.info("Merchant updated: id={}", updated.getId());
        return merchantMapper.toResponse(updated);
    }
```

**`updateEntity(request, merchant)`:** MapStruct's `@MappingTarget` method — modifies `merchant` in place (updates name, email, businessType, mdrRate; preserves id, active, timestamps).

**`save(merchant)`:** Hibernate sees `merchant.id != null` → executes UPDATE (not INSERT).

---

### Method: deactivateMerchant

```java
    @Transactional
    public void deactivateMerchant(UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", merchantId.toString()));
        merchant.setActive(false);
        merchantRepository.save(merchant);
        log.info("Merchant deactivated: id={}", merchantId);
    }
```

**SOFT DELETE:** Sets `active = false` instead of deleting the row.

**WHY NOT `deleteById()`?** The merchant has:
- API keys (children)
- Webhook configs (children)
- Fee configs (children)
- Transaction history (in payment service)

Hard delete with CASCADE would destroy all of it. Soft delete preserves everything for auditing.

---

## 4. Step-by-Step: ApiKeyService.java

**File:** `src/main/java/com/payflow/merchant/service/ApiKeyService.java`

This is the most security-critical service. I'll focus on the key methods.

### Key Imports

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
```

**ALL JDK built-in** — no third-party crypto libraries needed for API key security.

| Class | Purpose |
|---|---|
| `SecureRandom` | Cryptographically strong random number generator |
| `MessageDigest` | SHA-256 hashing |
| `Base64` | Encode random bytes as URL-safe text |
| `HexFormat` | Convert hash bytes to hex string |

### Class Declaration

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final MerchantRepository merchantRepository;
    private final SecureRandom secureRandom = new SecureRandom();
```

**`SecureRandom` as a field (not local variable):**
Creating `SecureRandom` is expensive (gathers entropy from OS). Create once, reuse forever. Thread-safe by design.

**WHY `SecureRandom` NOT `Random`?**

| Class | Algorithm | Security | Speed |
|---|---|---|---|
| `java.util.Random` | Linear congruential | ❌ PREDICTABLE — attacker can guess next value | Fast |
| `java.security.SecureRandom` | OS entropy (/dev/urandom) | ✅ UNPREDICTABLE | Slightly slower |

For security tokens (API keys, secrets), ALWAYS use `SecureRandom`. `Random` is only for non-security purposes (games, shuffling).

---

### Method: generateApiKey

```java
    @Transactional
    public ApiKeyResponse generateApiKey(UUID merchantId) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResourceNotFoundException("Merchant", merchantId.toString());
        }
```

**Step 1:** Verify merchant exists. Can't create a key for a nonexistent merchant.

`existsById()` — inherited from JpaRepository (free method). Fast — just checks if the ID exists.

```java
        byte[] keyBytes = new byte[32];
        secureRandom.nextBytes(keyBytes);
        String rawKey = "pk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes);
```

**Step 2: Generate the raw key.**

| Line | What Happens | Result |
|---|---|---|
| `new byte[32]` | Create 32-byte (256-bit) array | `[0, 0, 0, ..., 0]` (empty) |
| `secureRandom.nextBytes(keyBytes)` | Fill with random bytes | `[127, 45, 200, 3, ...]` (random) |
| `Base64.getUrlEncoder()` | Get URL-safe Base64 encoder | (no + or / characters — safe for URLs) |
| `.withoutPadding()` | Don't add `=` padding chars | Cleaner output |
| `.encodeToString(keyBytes)` | Convert bytes → text | `"YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXox"` |
| `"pk_" + ...` | Add prefix | `"pk_YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXox"` |

**`pk_` prefix:** Makes the key recognizable as a PayFlow API key (like Stripe's `sk_live_...`).

```java
        String keyHash = sha256(rawKey);
```

**Step 3:** Hash the key. Only the hash is stored in the database.

```java
        String prefix = rawKey.substring(3, 11);
```

**Step 4:** Extract 8 characters for identification.

```
rawKey: "pk_YWJjZGVmZ2hpamtsbW5vcHFy..."
             ^       ^
             3       11
prefix: "YWJjZGVm"  (8 characters)
```

```java
        ApiKey apiKey = ApiKey.builder()
                .keyHash(keyHash)
                .prefix(prefix)
                .merchantId(merchantId)
                .active(true)
                .build();

        ApiKey saved = apiKeyRepository.save(apiKey);
        log.info("API key generated for merchant: {}, prefix: {}", merchantId, prefix);
```

**Step 5:** Save hash + prefix to database. The raw key is NOT stored anywhere.

```java
        return ApiKeyResponse.builder()
                .id(saved.getId())
                .prefix(saved.getPrefix())
                .merchantId(saved.getMerchantId())
                .active(saved.getActive())
                .createdAt(saved.getCreatedAt())
                .rawKey(rawKey)       // ← ONLY TIME this is returned!
                .build();
    }
```

**Step 6:** Return response WITH raw key. After this response, the raw key is gone forever.

---

### Method: validateApiKey

```java
    @Transactional(readOnly = true)
    public UUID validateApiKey(String rawKey) {
        String keyHash = sha256(rawKey);
```

**Step 1:** Hash the incoming key (same SHA-256 algorithm).

```java
        ApiKey apiKey = apiKeyRepository.findByKeyHash(keyHash)
                .orElseThrow(() -> new ResourceNotFoundException("API Key not found"));
```

**Step 2:** Look up by hash. If no match → key doesn't exist.

```java
        if (!apiKey.getActive()) {
            throw new ResourceNotFoundException("API Key has been revoked");
        }

        return apiKey.getMerchantId();
    }
```

**Step 3:** Check if active. If revoked → reject. If active → return the merchant's UUID.

**THIS METHOD IS CALLED ON EVERY API REQUEST** that includes an API key. The Payment Service calls it to identify which merchant is making the request.

---

### Private Helper: sha256

```java
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
```

| Line | What Happens |
|---|---|
| `MessageDigest.getInstance("SHA-256")` | Get SHA-256 hash engine from JDK |
| `input.getBytes(StandardCharsets.UTF_8)` | Convert string to bytes (UTF-8 encoding) |
| `digest.digest(bytes)` | Compute SHA-256 → 32 bytes output |
| `HexFormat.of().formatHex(hash)` | Convert 32 bytes → 64 hex characters |

**EXAMPLE:**
```
Input:  "pk_YWJjZGVmZ2hpamtsbW5vcHFy..."
Output: "a3f8b2c1d4e7f6a5b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7"
         ├──────────────────── 64 hex characters ────────────────────────┤
```

**`NoSuchAlgorithmException`:** This literally cannot happen — SHA-256 is guaranteed to exist in every JDK. The try-catch is because `MessageDigest.getInstance()` declares it as checked. We wrap it in RuntimeException as a safety net.

---

## 5. Step-by-Step: WebhookConfigService.java

**File:** `src/main/java/com/payflow/merchant/service/WebhookConfigService.java`

### Key Method: createWebhookConfig

```java
    @Transactional
    public WebhookConfig createWebhookConfig(UUID merchantId, WebhookConfigRequest request) {
        if (!merchantRepository.existsById(merchantId)) {
            throw new ResourceNotFoundException("Merchant", merchantId.toString());
        }

        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
```

**SAME PATTERN AS API KEY GENERATION** but with `whsec_` prefix instead of `pk_`.

**DIFFERENCE FROM API KEYS:** The webhook secret IS stored in the database (not just a hash). Why?
- API keys: Validated by re-hashing → only hash needed
- Webhook secrets: Used by PayFlow to SIGN outgoing payloads → need the actual secret

```java
        WebhookConfig config = WebhookConfig.builder()
                .url(request.getUrl())
                .secret(secret)
                .events(request.getEvents())
                .merchantId(merchantId)
                .active(true)
                .build();

        WebhookConfig saved = webhookConfigRepository.save(config);
        log.info("Webhook config created for merchant: {}, url: {}", merchantId, request.getUrl());
        return saved;
    }
```

**NOTE:** Returns the entity directly (not a DTO). The WebhookConfig entity doesn't have sensitive internal fields, so it's safe to return as-is. In a stricter design, you'd create a `WebhookConfigResponse` DTO.

### Other Methods (Standard CRUD Pattern)

The remaining methods (`getWebhookConfigs`, `getActiveWebhookConfigs`, `updateWebhookConfig`, `deactivateWebhookConfig`) follow the same find-or-throw + modify + save pattern as MerchantService. No new concepts — just standard CRUD with `@Transactional` and logging.

---

## 6. How Unit Testing Works

### What Is a Unit Test?

A unit test verifies **one method in isolation** — no database, no web server, no network.

```
PRODUCTION:
  MerchantService → calls → MerchantRepository → calls → PostgreSQL

UNIT TEST:
  MerchantService → calls → MOCK MerchantRepository → returns FAKE data
                             ↑
                             Not a real database! Just a fake that returns
                             whatever we tell it to.
```

### Mockito — The Mocking Framework

| Concept | What It Does | Example |
|---|---|---|
| `@Mock` | Creates a fake implementation | `@Mock MerchantRepository` → fake repo |
| `@InjectMocks` | Creates real service with fakes injected | `@InjectMocks MerchantService` → real service, fake deps |
| `when().thenReturn()` | "When this method is called, return this" | `when(repo.existsByEmail("x")).thenReturn(false)` |
| `verify()` | "Assert this method WAS called" | `verify(repo).save(any())` |
| `verify(never())` | "Assert this method was NOT called" | `verify(repo, never()).save(any())` |
| `assertThat()` | AssertJ assertion | `assertThat(result.getName()).isEqualTo("Shop")` |
| `assertThatThrownBy()` | Assert exception is thrown | `assertThatThrownBy(() -> ...).isInstanceOf(...)` |

### Test Structure: Given-When-Then

```java
@Test
void someTest() {
    // GIVEN — set up preconditions (mock returns)
    when(repo.findById(id)).thenReturn(Optional.of(entity));

    // WHEN — call the method under test
    Result result = service.doSomething(id);

    // THEN — verify results
    assertThat(result).isNotNull();
    verify(repo).findById(id);
}
```

---

## 7. Step-by-Step: MerchantServiceTest.java

**File:** `src/test/java/com/payflow/merchant/service/MerchantServiceTest.java`

### Class Setup

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantService Unit Tests")
class MerchantServiceTest {
```

**`@ExtendWith(MockitoExtension.class)`:** "Enable @Mock and @InjectMocks annotations in this test class."

**`@DisplayName("...")`:** Human-readable name shown in test reports.

**NO `public` keyword:** JUnit 5 tests don't need to be public (unlike JUnit 4).

```java
    @Mock private MerchantRepository merchantRepository;
    @Mock private MerchantMapper merchantMapper;
    @InjectMocks private MerchantService merchantService;
```

**WHAT HAPPENS:**
1. Mockito creates a FAKE `MerchantRepository` (all methods return null/empty by default)
2. Mockito creates a FAKE `MerchantMapper`
3. Mockito creates a REAL `MerchantService` and injects the two fakes

```java
    private UUID merchantId;
    private Merchant testMerchant;
    private MerchantRegisterRequest registerRequest;
    private MerchantResponse merchantResponse;

    @BeforeEach
    void setUp() {
```

**`@BeforeEach`:** "Run this method BEFORE every test." Creates fresh test data for each test — prevents tests from affecting each other.

```java
        merchantId = UUID.randomUUID();

        testMerchant = Merchant.builder()
                .id(merchantId)
                .name("Test Shop")
                .email("shop@example.com")
                .businessType("RETAIL")
                .mdrRate(2.0)
                .active(true)
                .createdAt(Instant.now())
                .build();

        registerRequest = new MerchantRegisterRequest();
        registerRequest.setName("Test Shop");
        registerRequest.setEmail("shop@example.com");
        registerRequest.setBusinessType("RETAIL");

        merchantResponse = new MerchantResponse();
        merchantResponse.setId(merchantId);
        merchantResponse.setName("Test Shop");
        merchantResponse.setEmail("shop@example.com");
        merchantResponse.setBusinessType("RETAIL");
        merchantResponse.setActive(true);
    }
```

---

### Test 1: Register Success

```java
    @Test
    @DisplayName("registerMerchant - should save and return merchant response")
    void registerMerchant_Success() {
        // GIVEN
        when(merchantRepository.existsByEmail(registerRequest.getEmail())).thenReturn(false);
        when(merchantMapper.toEntity(registerRequest)).thenReturn(testMerchant);
        when(merchantRepository.save(any(Merchant.class))).thenReturn(testMerchant);
        when(merchantMapper.toResponse(testMerchant)).thenReturn(merchantResponse);
```

**4 mock setups — one for each dependency call in `registerMerchant()`:**
1. `existsByEmail` returns false → email not taken
2. `toEntity` returns our test merchant
3. `save` returns the same merchant (simulates DB save)
4. `toResponse` returns our test response

```java
        // WHEN
        MerchantResponse result = merchantService.registerMerchant(registerRequest);
```

Call the REAL service method. It calls the FAKE dependencies.

```java
        // THEN
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Test Shop");
        assertThat(result.getEmail()).isEqualTo("shop@example.com");
        verify(merchantRepository).save(any(Merchant.class));
    }
```

**`assertThat(result).isNotNull()`:** AssertJ assertion — verify result exists.
**`verify(merchantRepository).save(any())`:** Verify that `save()` was actually called.

---

### Test 2: Duplicate Email

```java
    @Test
    @DisplayName("registerMerchant - should throw DuplicateResourceException for existing email")
    void registerMerchant_DuplicateEmail_Throws() {
        when(merchantRepository.existsByEmail(registerRequest.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> merchantService.registerMerchant(registerRequest))
                .isInstanceOf(DuplicateResourceException.class);

        verify(merchantRepository, never()).save(any(Merchant.class));
    }
```

**KEY ASSERTIONS:**
1. `assertThatThrownBy(...)` → the method THROWS an exception
2. `.isInstanceOf(DuplicateResourceException.class)` → it's the RIGHT exception
3. `verify(never()).save(...)` → `save()` was NEVER called (registration aborted early)

---

### Test 3-5: Get, Update

```java
    @Test
    @DisplayName("getMerchant - should throw ResourceNotFoundException when not found")
    void getMerchant_NotFound_Throws() {
        UUID unknownId = UUID.randomUUID();
        when(merchantRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> merchantService.getMerchant(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
```

**`Optional.empty()`:** Simulates "merchant not found in DB." The service's `.orElseThrow()` triggers → ResourceNotFoundException.

The remaining tests follow the same Given-When-Then pattern.

---

## 8. Step-by-Step: ApiKeyServiceTest.java

**File:** `src/test/java/com/payflow/merchant/service/ApiKeyServiceTest.java`

### Test: Generate API Key

```java
    @Test
    @DisplayName("generateApiKey - should create and return API key with raw key visible")
    void generateApiKey_Success() {
        when(merchantRepository.existsById(merchantId)).thenReturn(true);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> {
            ApiKey saved = invocation.getArgument(0);
            saved.setId(keyId);
            saved.setCreatedAt(Instant.now());
            return saved;
        });
```

**`thenAnswer` vs `thenReturn`:**
- `thenReturn(value)` → always return the same object
- `thenAnswer(invocation -> ...)` → compute the return value dynamically

Here, `thenAnswer` captures what was passed to `save()`, adds an ID and timestamp (simulating what the DB does), and returns it. This is more realistic than `thenReturn`.

```java
        ApiKeyResponse response = apiKeyService.generateApiKey(merchantId);

        assertThat(response).isNotNull();
        assertThat(response.getRawKey()).startsWith("pk_");
        assertThat(response.getRawKey()).isNotBlank();
        assertThat(response.getMerchantId()).isEqualTo(merchantId);
        assertThat(response.getActive()).isTrue();
        assertThat(response.getPrefix()).isNotBlank();

        verify(apiKeyRepository).save(any(ApiKey.class));
    }
```

**KEY ASSERTION:** `response.getRawKey().startsWith("pk_")` — verifies the key has the correct prefix format.

### Test: Validate Revoked Key

```java
    @Test
    @DisplayName("validateApiKey - should throw ResourceNotFoundException for revoked key")
    void validateApiKey_RevokedKey_Throws() {
        ApiKey revokedKey = ApiKey.builder()
                .id(keyId).keyHash("sha256-hash-revoked").prefix("revk1234")
                .merchantId(merchantId).active(false)  // ← REVOKED
                .createdAt(Instant.now())
                .build();

        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(revokedKey));

        assertThatThrownBy(() -> apiKeyService.validateApiKey("pk_revokedKeyValue"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("revoked");
    }
```

**`.hasMessageContaining("revoked")`:** Not just the right exception type, but the right MESSAGE. This prevents a test from passing if a different ResourceNotFoundException is thrown for a different reason.

### Test: Revoke Key

```java
    @Test
    @DisplayName("revokeApiKey - should deactivate the API key")
    void revokeApiKey_Success() {
        when(apiKeyRepository.findById(keyId)).thenReturn(Optional.of(testApiKey));
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(testApiKey);

        apiKeyService.revokeApiKey(keyId);

        assertThat(testApiKey.getActive()).isFalse();
        verify(apiKeyRepository).save(testApiKey);
    }
```

**`assertThat(testApiKey.getActive()).isFalse()`:** Verifies the service set `active = false` on the entity object. Since Mockito uses the same object reference, the change is visible here.

---

## 9. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **@Service** | Marks a class as business logic bean — Spring manages it |
| 2 | **@RequiredArgsConstructor** | Lombok generates constructor for final fields → Spring injects dependencies |
| 3 | **@Slf4j** | Lombok generates logger → `log.info()`, `log.warn()`, `log.error()` |
| 4 | **@Transactional** | Wraps method in DB transaction: success=commit, exception=rollback |
| 5 | **@Transactional(readOnly=true)** | Optimization for read-only methods — skips dirty checking |
| 6 | **orElseThrow()** | Convert Optional.empty() to a clean exception (not NPE) |
| 7 | **SecureRandom** | Cryptographically strong random — ALWAYS use for security tokens |
| 8 | **SHA-256 in Java** | `MessageDigest.getInstance("SHA-256")` → `.digest()` → `HexFormat` |
| 9 | **Smart duplicate check** | Only check email uniqueness if the email actually changed |
| 10 | **Stream + map + collect** | Functional way to convert List<Entity> → List<DTO> |
| 11 | **@Mock** | Creates fake dependency (all methods return null/empty) |
| 12 | **@InjectMocks** | Creates real service with fakes injected |
| 13 | **when().thenReturn()** | Set up fake behavior ("when X is called, return Y") |
| 14 | **when().thenAnswer()** | Dynamic fake behavior (compute return value from input) |
| 15 | **verify()** | Assert a method WAS called |
| 16 | **verify(never())** | Assert a method was NOT called |
| 17 | **assertThatThrownBy()** | Assert the right exception type AND message |
| 18 | **Given-When-Then** | Test structure: setup → action → assertion |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part07-merchant-service-overview.md) | Merchant Service Overview |
| [Part 7a](./phase4-part07a-merchant-project-setup.md) | Project Setup |
| [Part 7b](./phase4-part07b-merchant-entities.md) | Entities |
| [Part 7c](./phase4-part07c-merchant-migrations.md) | Flyway Migrations |
| [Part 7d](./phase4-part07d-merchant-repositories.md) | Repositories |
| [Part 7e](./phase4-part07e-merchant-dtos-mapper.md) | DTOs + Mapper |
| **Part 7f** | **Services + Tests** (You are here) |
| [Part 7g](./phase4-part07g-merchant-controller-docker.md) | Controller + Docker |

---

*Next: [Part 7g — Controller + curl + Dockerfile (HTTP + Deploy)](./phase4-part07g-merchant-controller-docker.md) →*
