# 🏗️ Phase 4 Part 7e: Merchant Service — DTOs + MerchantMapper (Input/Output Contracts)

> **"Never expose your entities to the outside world. DTOs are the bodyguards that control what goes in and what comes out."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7e — DTOs + MerchantMapper |
| **What You Build** | MerchantRegisterRequest.java, MerchantResponse.java, ApiKeyResponse.java, WebhookConfigRequest.java, MerchantMapper.java |
| **Previous** | [Part 7d — Repositories](./phase4-part07d-merchant-repositories.md) |
| **Next** | [Part 7f — Services + Tests](./phase4-part07f-merchant-services-tests.md) |

---

## 📖 Table of Contents

1. [What Are DTOs and Why Do We Need Them?](#1-what-are-dtos-and-why-do-we-need-them)
2. [Input DTOs vs Output DTOs](#2-input-dtos-vs-output-dtos)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: MerchantRegisterRequest.java](#4-step-by-step-merchantregisterrequestjava)
5. [Step-by-Step: MerchantResponse.java](#5-step-by-step-merchantresponsejava)
6. [Step-by-Step: ApiKeyResponse.java](#6-step-by-step-apikeyresponsejava)
7. [Step-by-Step: WebhookConfigRequest.java](#7-step-by-step-webhookconfigrequestjava)
8. [Step-by-Step: MerchantMapper.java (MapStruct)](#8-step-by-step-merchantmapperjava-mapstruct)
9. [How DTOs Flow Through the Application](#9-how-dtos-flow-through-the-application)
10. [Validation Annotations Cheat Sheet](#10-validation-annotations-cheat-sheet)
11. [What You Learned](#11-what-you-learned)

---

## 1. What Are DTOs and Why Do We Need Them?

**DTO = Data Transfer Object.** A simple class that carries data between layers.

### Why Not Just Use Entities Directly?

```java
// ❌ DANGEROUS: Exposing the entity directly
@PostMapping
public Merchant registerMerchant(@RequestBody Merchant merchant) {
    return merchantRepository.save(merchant);
}
// PROBLEMS:
// 1. Client can SET id, createdAt, active (they shouldn't!)
// 2. Response includes ALL entity fields (maybe some are internal)
// 3. Validation annotations clutter the entity
// 4. If entity changes, API contract changes (breaks clients)
```

```java
// ✅ SAFE: Using DTOs
@PostMapping
public ApiResponse<MerchantResponse> registerMerchant(@Valid @RequestBody MerchantRegisterRequest request) {
    MerchantResponse response = merchantService.registerMerchant(request);
    return ApiResponse.success(response);
}
// BENEFITS:
// 1. Client can only send: name, email, businessType, mdrRate (controlled)
// 2. Response only shows: id, name, email, businessType, mdrRate, active, timestamps
// 3. Validation lives on the DTO, entity stays clean
// 4. Entity can change without breaking the API (DTO is the contract)
```

### The Rule

```
CLIENT → [Input DTO] → Controller → Service → Repository → DATABASE
                                                                ↓
CLIENT ← [Output DTO] ← Controller ← Service ← Repository ← DATABASE

Entities NEVER leave the service layer.
DTOs are the only thing the controller and client see.
```

---

## 2. Input DTOs vs Output DTOs

| Type | Direction | Purpose | Example |
|---|---|---|---|
| **Input DTO (Request)** | Client → Server | What the client SENDS | `MerchantRegisterRequest`, `WebhookConfigRequest` |
| **Output DTO (Response)** | Server → Client | What the client RECEIVES | `MerchantResponse`, `ApiKeyResponse` |

**Why separate?**
- Input: Has validation annotations (`@NotBlank`, `@Email`) — client input must be checked
- Output: Has no validation — we control what we send, no checking needed
- Input: Doesn't have `id`, `createdAt` — client doesn't set these
- Output: Has `id`, `createdAt` — client needs to see these

---

## 3. Folder Structure After This Part

```
backend/merchant-service/src/main/java/com/payflow/merchant/
├── MerchantServiceApplication.java    ← from 7a
├── config/SecurityConfig.java         ← from 7a
├── model/                             ← from 7b
│   ├── Merchant.java, ApiKey.java, WebhookConfig.java, FeeConfig.java
├── repository/                        ← from 7d
│   ├── MerchantRepository.java, ApiKeyRepository.java, WebhookConfigRepository.java
├── dto/                               ← YOU CREATE THIS FOLDER
│   ├── MerchantRegisterRequest.java   ← YOU CREATE THIS
│   ├── MerchantResponse.java          ← YOU CREATE THIS
│   ├── ApiKeyResponse.java            ← YOU CREATE THIS
│   └── WebhookConfigRequest.java      ← YOU CREATE THIS
└── mapper/                            ← YOU CREATE THIS FOLDER
    └── MerchantMapper.java            ← YOU CREATE THIS
```

---

## 4. Step-by-Step: MerchantRegisterRequest.java

**File:** `src/main/java/com/payflow/merchant/dto/MerchantRegisterRequest.java`

This DTO defines what the client must send to register a merchant.

### Line-by-Line

```java
package com.payflow.merchant.dto;
```

DTOs go in the `dto` sub-package — convention across all PayFlow services.

```java
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
```

**IMPORTS EXPLAINED:**

| Import | What It Provides |
|---|---|
| `jakarta.validation.constraints.*` | Validation annotations (`@NotBlank`, `@Email`, `@Positive`) |
| `lombok.*` | `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` |

**NOTE:** These are `jakarta.validation` (not `javax.validation`). Spring Boot 3 uses Jakarta EE.

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
```

Same Lombok annotations as entities, but with a DIFFERENT purpose:
- On **entities:** `@NoArgsConstructor` is for JPA/Hibernate
- On **DTOs:** `@NoArgsConstructor` is for Jackson (JSON deserializer)

When the client sends JSON:
```json
{"name": "Rajesh Electronics", "email": "billing@rajesh.com", "businessType": "RETAIL"}
```

Jackson does:
1. Creates empty `MerchantRegisterRequest` using no-arg constructor
2. Calls `setName("Rajesh Electronics")`, `setEmail("billing@rajesh.com")`, etc.

Without `@NoArgsConstructor` → Jackson can't deserialize → 400 Bad Request.

```java
public class MerchantRegisterRequest {
```

**NAMING CONVENTION:** `{Entity}{Action}Request`
- `MerchantRegisterRequest` — used for both registration (POST) and update (PUT)
- Some teams create separate `MerchantUpdateRequest` — but if the fields are identical, one DTO suffices

```java
    @NotBlank(message = "Merchant name is required")
    private String name;
```

**`@NotBlank`:**

| Annotation | Rejects | Accepts |
|---|---|---|
| `@NotNull` | `null` | `""`, `"   "` |
| `@NotEmpty` | `null`, `""` | `"   "` |
| `@NotBlank` | `null`, `""`, `"   "` | `"anything with content"` |

`@NotBlank` is the strictest — rejects null, empty string, AND whitespace-only strings. Perfect for text fields.

**`message = "Merchant name is required"`:**
This custom message appears in the error response when validation fails:
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "details": [{"field": "name", "message": "Merchant name is required"}]
  }
}
```

**WHEN DOES VALIDATION RUN?**
When the controller parameter has `@Valid`:
```java
public ResponseEntity<...> registerMerchant(@Valid @RequestBody MerchantRegisterRequest request) {
//                                          ^^^^^^
// @Valid triggers validation BEFORE the method body runs.
// If ANY field fails → MethodArgumentNotValidException → 400 Bad Request
// Controller code NEVER executes for invalid input.
```

```java
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;
```

**TWO VALIDATIONS ON ONE FIELD:**
1. `@NotBlank` — must not be empty
2. `@Email` — must match email format (contains `@` and domain)

Both must pass. If email is blank → `@NotBlank` fails first. If email is "notanemail" → `@Email` fails.

**WHAT `@Email` ACCEPTS AND REJECTS:**
```
✅ "billing@rajesh-electronics.com"
✅ "user@domain.co.in"
❌ "notanemail"
❌ "@domain.com"
❌ "user@"
❌ ""  (caught by @NotBlank first)
```

```java
    @NotBlank(message = "Business type is required")
    private String businessType;
```

A freeform string: "RETAIL", "E_COMMERCE", "FOOD_DELIVERY", "SAAS", etc.

**WHY NOT AN ENUM IN THE DTO?**
Same reason as the entity — new business types shouldn't require code changes. The DTO accepts any string.

```java
    @Positive(message = "MDR rate must be positive")
    private Double mdrRate;
```

**`@Positive`:**
The value must be greater than 0.

| Value | @Positive Result |
|---|---|
| `null` | ✅ Passes! (`@Positive` skips null values) |
| `2.0` | ✅ Passes |
| `0.0` | ❌ Fails (zero is not positive) |
| `-1.5` | ❌ Fails (negative) |

**WHY DOES `null` PASS?** Because `mdrRate` is optional — a merchant can register without specifying an MDR rate. If you wanted to require it, you'd add `@NotNull` too:
```java
@NotNull(message = "MDR rate is required")   // Must be present
@Positive(message = "MDR rate must be positive")  // Must be > 0
private Double mdrRate;
```

But in our case, null is allowed — so only `@Positive` (which skips nulls).

```java
}
```

**THAT'S THE ENTIRE INPUT DTO: 4 fields with validation.** Compare to the entity's 8 fields — the DTO excludes `id`, `active`, `createdAt`, `updatedAt` (all auto-managed).

---

## 5. Step-by-Step: MerchantResponse.java

**File:** `src/main/java/com/payflow/merchant/dto/MerchantResponse.java`

### Line-by-Line

```java
package com.payflow.merchant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;
```

**NO validation imports.** Output DTOs don't need validation — we control what we send.

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantResponse {

    private UUID id;
    private String name;
    private String email;
    private String businessType;
    private Double mdrRate;
    private Boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
```

**ALL 8 FIELDS.** The response includes everything the client needs to display a merchant.

**COMPARISON — Input vs Output:**

| Field | In Request DTO? | In Response DTO? | Why? |
|---|---|---|---|
| `id` | ❌ No | ✅ Yes | Auto-generated by DB, client needs to see it |
| `name` | ✅ Yes | ✅ Yes | Client provides it, client sees it |
| `email` | ✅ Yes | ✅ Yes | Same |
| `businessType` | ✅ Yes | ✅ Yes | Same |
| `mdrRate` | ✅ Yes | ✅ Yes | Same |
| `active` | ❌ No | ✅ Yes | Auto-set to true, client can see status |
| `createdAt` | ❌ No | ✅ Yes | Auto-set by Hibernate, client sees when created |
| `updatedAt` | ❌ No | ✅ Yes | Auto-set by Hibernate, client sees last update |

**Example JSON response:**
```json
{
  "success": true,
  "data": {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "name": "Rajesh Electronics",
    "email": "billing@rajesh-electronics.com",
    "businessType": "RETAIL",
    "mdrRate": 2.0,
    "active": true,
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T10:30:00Z"
  }
}
```

---

## 6. Step-by-Step: ApiKeyResponse.java

**File:** `src/main/java/com/payflow/merchant/dto/ApiKeyResponse.java`

This DTO has a **special security behavior**: the `rawKey` field.

### Line-by-Line

```java
package com.payflow.merchant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyResponse {

    private UUID id;
    private String prefix;
    private UUID merchantId;
    private Boolean active;
    private Instant createdAt;
```

Standard fields — nothing special.

```java
    /**
     * The raw API key is only returned once at creation time.
     * After that, only the prefix is available.
     */
    private String rawKey;
```

**THIS IS THE CRITICAL FIELD.**

**ON CREATION (POST /api-keys):**
```json
{
  "id": "uuid-key-1",
  "prefix": "YWJjZGVm",
  "merchantId": "uuid-merchant-1",
  "active": true,
  "createdAt": "2024-01-15T10:30:00Z",
  "rawKey": "pk_YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXox"
}
// ⚠️ SAVE THIS rawKey! You'll never see it again!
```

**ON LISTING (GET /api-keys):**
```json
{
  "id": "uuid-key-1",
  "prefix": "YWJjZGVm",
  "merchantId": "uuid-merchant-1",
  "active": true,
  "createdAt": "2024-01-15T10:30:00Z",
  "rawKey": null
}
// rawKey is ALWAYS null in listings. Only the prefix is visible.
```

**WHY?**
- The raw key is NEVER stored in the database (only the SHA-256 hash is stored)
- Once the creation response is returned, the raw key exists ONLY in the merchant's hands
- If the merchant loses it → they must generate a new key (and revoke the old one)
- This is the same pattern Stripe, GitHub, and AWS use for API keys

**HOW IT'S CONTROLLED IN CODE:**
```java
// In ApiKeyService.generateApiKey() — creation:
return ApiKeyResponse.builder()
        .rawKey(rawKey)    // ← SET: shown only this one time
        .build();

// In ApiKeyService.toResponse() — listing:
return ApiKeyResponse.builder()
        .rawKey(null)      // ← NULL: never show stored keys
        .build();
```

```java
}
```

**COMPARISON — ApiKey Entity vs ApiKeyResponse DTO:**

| Field | Entity (ApiKey.java) | Response DTO | Why Different? |
|---|---|---|---|
| `id` | ✅ | ✅ | Same |
| `keyHash` | ✅ | ❌ | NEVER expose the hash to clients (internal security detail) |
| `prefix` | ✅ | ✅ | Client sees prefix for identification |
| `merchantId` | ✅ | ✅ | Same |
| `active` | ✅ | ✅ | Same |
| `createdAt` | ✅ | ✅ | Same |
| `updatedAt` | ✅ | ❌ | Not useful for client (when was key last updated? nobody cares) |
| `rawKey` | ❌ Not in entity! | ✅ | Only exists in the DTO, only at creation time |

The DTO has a field (`rawKey`) that the entity doesn't have. And the entity has a field (`keyHash`) that the DTO doesn't have. **That's exactly why DTOs exist — they're a different shape than entities.**

---

## 7. Step-by-Step: WebhookConfigRequest.java

**File:** `src/main/java/com/payflow/merchant/dto/WebhookConfigRequest.java`

### Line-by-Line

```java
package com.payflow.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.URL;
```

**NEW IMPORT: `org.hibernate.validator.constraints.URL`**

This is NOT from Jakarta standard (`jakarta.validation.constraints`). It's from **Hibernate Validator** — the implementation of Jakarta Validation that Spring Boot uses.

| Package | What | Example Annotations |
|---|---|---|
| `jakarta.validation.constraints.*` | Standard annotations (any validator impl) | `@NotBlank`, `@Email`, `@Positive`, `@Size` |
| `org.hibernate.validator.constraints.*` | Hibernate-specific extras | `@URL`, `@CreditCardNumber`, `@ISBN` |

Both work seamlessly in Spring Boot. `@URL` just isn't part of the Jakarta standard.

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookConfigRequest {

    @NotBlank(message = "Webhook URL is required")
    @URL(message = "Invalid URL format")
    private String url;
```

**`@URL` — WHAT IT VALIDATES:**

```
✅ "https://merchant-site.com/webhook"         ← valid HTTPS
✅ "http://localhost:3000/webhook"              ← valid HTTP (ok for dev)
✅ "https://api.example.com/v1/payflow/notify"  ← valid with path
❌ "not-a-url"                                  ← no scheme
❌ "ftp://files.example.com"                    ← FTP (depends on config)
❌ ""                                           ← caught by @NotBlank first
```

`@URL` checks: valid scheme (http/https) + valid host. It does NOT check if the URL is reachable — that's a runtime concern, not a validation concern.

**TWO ANNOTATIONS:**
1. `@NotBlank` → must not be empty
2. `@URL` → must be a valid URL format

Both must pass.

```java
    @NotEmpty(message = "At least one event must be specified")
    private String[] events;
```

**`@NotEmpty` on an array — WHAT IT CHECKS:**

| Value | @NotEmpty Result |
|---|---|
| `null` | ❌ Fails |
| `[]` (empty array) | ❌ Fails |
| `["payment.captured"]` | ✅ Passes (at least 1 element) |
| `["payment.captured", "payment.refunded"]` | ✅ Passes |

**WHY `@NotEmpty` NOT `@NotBlank`?**
- `@NotBlank` is for **Strings** (checks whitespace)
- `@NotEmpty` is for **Collections and Arrays** (checks size > 0)
- Using `@NotBlank` on an array → compilation error

**EXAMPLE JSON:**
```json
{
  "url": "https://my-shop.com/payflow-webhook",
  "events": ["payment.authorized", "payment.captured", "payment.refunded"]
}
```

**FIELDS NOT IN THIS DTO (auto-generated by the service):**
- `id` — auto-generated UUID
- `secret` — auto-generated signing secret (`whsec_...`)
- `merchantId` — comes from the URL path `/{merchantId}/webhooks`
- `active` — defaults to true
- `createdAt`, `updatedAt` — Hibernate auto-manages

```java
}
```

---

## 8. Step-by-Step: MerchantMapper.java (MapStruct)

**File:** `src/main/java/com/payflow/merchant/mapper/MerchantMapper.java`

### What Is MapStruct?

MapStruct is a **compile-time code generator** that writes entity ↔ DTO conversion code for you.

**WITHOUT MapStruct (manual mapping):**
```java
// You write this in EVERY service method:
Merchant merchant = new Merchant();
merchant.setName(request.getName());
merchant.setEmail(request.getEmail());
merchant.setBusinessType(request.getBusinessType());
merchant.setMdrRate(request.getMdrRate());
merchant.setActive(true);
// 5 lines per conversion. 10 fields = 10 lines. Error-prone.
```

**WITH MapStruct:**
```java
Merchant merchant = merchantMapper.toEntity(request);
// ONE line. MapStruct generates the 5-line conversion at compile time.
```

### Line-by-Line

```java
package com.payflow.merchant.mapper;

import com.payflow.merchant.dto.MerchantRegisterRequest;
import com.payflow.merchant.dto.MerchantResponse;
import com.payflow.merchant.model.Merchant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
```

**IMPORTS EXPLAINED:**

| Import | What |
|---|---|
| `@Mapper` | Marks this interface for MapStruct code generation |
| `@Mapping` | Customize individual field mappings |
| `@MappingTarget` | "Don't create new object — update THIS existing one" |

```java
@Mapper(componentModel = "spring")
```

**`@Mapper`:** "MapStruct, generate an implementation class for this interface at compile time."

**`componentModel = "spring"`:** "Register the generated class as a Spring bean." This means you can inject it:
```java
@Service
@RequiredArgsConstructor
public class MerchantService {
    private final MerchantMapper merchantMapper;  // ← Spring injects the generated impl
}
```

Without `componentModel = "spring"`, you'd have to create the mapper manually: `MerchantMapper.INSTANCE`.

**WHAT HAPPENS AT COMPILE TIME:**
```
You write:     MerchantMapper.java (interface — 20 lines)
                     │
                     ▼ (Maven compile → MapStruct annotation processor runs)
                     │
MapStruct generates: MerchantMapperImpl.java (class — 80+ lines)
                     Located in: target/generated-sources/annotations/
```

```java
public interface MerchantMapper {
```

**IT'S AN INTERFACE.** You never write `MerchantMapperImpl` — MapStruct generates it.

```java
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", constant = "true")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Merchant toEntity(MerchantRegisterRequest request);
```

**METHOD: Convert DTO → new Entity (for registration)**

**`@Mapping(target = "id", ignore = true)`:**
"When creating the Merchant, do NOT set the `id` field."
Why? Because `@GeneratedValue(UUID)` in the entity generates the ID automatically. If MapStruct set it from the request, it would override the auto-generation.

**`@Mapping(target = "active", constant = "true")`:**
"Always set `active = true` when creating a new merchant."
The request DTO doesn't have an `active` field — new merchants are always active. `constant = "true"` means MapStruct hardcodes this value.

**`@Mapping(target = "createdAt", ignore = true)` and `updatedAt`:**
These are managed by `@CreationTimestamp` / `@UpdateTimestamp` in the entity. MapStruct should NOT touch them.

**WHAT MapStruct GENERATES (you never see this, but this is what runs):**
```java
// Generated file: MerchantMapperImpl.java
@Override
public Merchant toEntity(MerchantRegisterRequest request) {
    if (request == null) return null;
    
    Merchant.MerchantBuilder merchant = Merchant.builder();
    merchant.name(request.getName());              // ← auto-mapped (same name)
    merchant.email(request.getEmail());            // ← auto-mapped
    merchant.businessType(request.getBusinessType()); // ← auto-mapped
    merchant.mdrRate(request.getMdrRate());         // ← auto-mapped
    merchant.active(true);                         // ← from constant = "true"
    // id → ignored
    // createdAt → ignored
    // updatedAt → ignored
    return merchant.build();
}
```

Fields with the **same name** in both DTO and Entity are mapped automatically. You only need `@Mapping` for exceptions.

```java
    MerchantResponse toResponse(Merchant merchant);
```

**METHOD: Convert Entity → Response DTO (for all API responses)**

**NO @Mapping annotations needed.** Why? Because ALL field names match between `Merchant` and `MerchantResponse`:
- `merchant.getId()` → `response.setId()`
- `merchant.getName()` → `response.setName()`
- `merchant.getEmail()` → `response.setEmail()`
- ... and so on for all 8 fields.

When names match perfectly, MapStruct handles everything automatically.

```java
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "active", ignore = true)
    void updateEntity(MerchantRegisterRequest request, @MappingTarget Merchant merchant);
```

**METHOD: Update EXISTING Entity from DTO (for PUT/update)**

**THIS IS THE KEY CONCEPT: `@MappingTarget`**

```java
// WITHOUT @MappingTarget (toEntity):
Merchant newMerchant = merchantMapper.toEntity(request);
// Creates a BRAND NEW Merchant object (id=null, active=null, createdAt=null)
// All auto-managed fields are lost!

// WITH @MappingTarget (updateEntity):
merchantMapper.updateEntity(request, existingMerchant);
// Modifies the EXISTING Merchant object IN PLACE
// Preserves: id, active, createdAt, updatedAt
// Updates: name, email, businessType, mdrRate (from request)
```

**VISUAL:**
```
BEFORE updateEntity():
  existingMerchant = {
    id: "uuid-123",           ← preserved
    name: "Old Name",         ← will be overwritten
    email: "old@x.com",       ← will be overwritten
    businessType: "RETAIL",   ← will be overwritten
    mdrRate: 1.5,             ← will be overwritten
    active: true,             ← preserved (ignored in mapping)
    createdAt: "2024-01-01",  ← preserved (ignored in mapping)
    updatedAt: "2024-01-01"   ← preserved (Hibernate updates this on save)
  }

AFTER updateEntity(request, existingMerchant):
  existingMerchant = {
    id: "uuid-123",           ← SAME (ignored)
    name: "New Name",         ← UPDATED from request
    email: "new@x.com",       ← UPDATED from request
    businessType: "E_COMMERCE", ← UPDATED from request
    mdrRate: 2.0,             ← UPDATED from request
    active: true,             ← SAME (ignored)
    createdAt: "2024-01-01",  ← SAME (ignored)
    updatedAt: "2024-01-01"   ← SAME (Hibernate updates on save)
  }
```

**WHY `void` RETURN TYPE?**
The entity is modified **in place** (Java passes objects by reference). The caller already has the reference — no need to return it.

```java
}
```

**THE ENTIRE MAPPER: 3 methods, ~20 lines. MapStruct generates 80+ lines of implementation code at compile time.**

---

## 9. How DTOs Flow Through the Application

### Registration Flow (POST /v1/merchants)

```
Client JSON                    MerchantRegisterRequest         Merchant (Entity)              MerchantResponse
────────────                   ─────────────────────           ────────────────                ────────────────
{                              name: "Rajesh..."               id: UUID (generated)           id: "uuid-123"
  "name": "Rajesh..."         email: "billing@..."            name: "Rajesh..."              name: "Rajesh..."
  "email": "billing@..."     businessType: "RETAIL"           email: "billing@..."           email: "billing@..."
  "businessType": "RETAIL"    mdrRate: 2.0                    businessType: "RETAIL"         businessType: "RETAIL"
  "mdrRate": 2.0                                              mdrRate: 2.0                   mdrRate: 2.0
}                                                              active: true                   active: true
                                                               createdAt: NOW                 createdAt: "2024-..."
                                                               updatedAt: NOW                 updatedAt: "2024-..."

        │                              │                              │                              │
        ▼                              ▼                              ▼                              ▼
   Jackson                      @Valid validates              mapper.toEntity()              mapper.toResponse()
   deserializes                 (NotBlank, Email, etc)        + repo.save()
```

### Update Flow (PUT /v1/merchants/{id})

```
Client JSON                    MerchantRegisterRequest         Merchant (EXISTING)
────────────                   ─────────────────────           ──────────────────
{                              name: "Updated Name"            id: "uuid-123"        ← PRESERVED
  "name": "Updated Name"      email: "new@x.com"             name: "Updated Name"  ← OVERWRITTEN
  "email": "new@x.com"        businessType: "E_COMMERCE"      email: "new@x.com"    ← OVERWRITTEN
  "businessType":"E_COMMERCE"  mdrRate: 2.5                   businessType: "E_COM" ← OVERWRITTEN
  "mdrRate": 2.5                                              mdrRate: 2.5          ← OVERWRITTEN
}                                                              active: true           ← PRESERVED
                                                               createdAt: "2024-01"  ← PRESERVED
        │                              │                              │
        ▼                              ▼                              ▼
   Jackson                      @Valid validates              mapper.updateEntity()
   deserializes                                               (uses @MappingTarget)
```

---

## 10. Validation Annotations Cheat Sheet

### All Annotations Used in Merchant Service DTOs

| Annotation | On Field | What It Checks | Null Handling |
|---|---|---|---|
| `@NotBlank` | name, email, businessType, url | Not null, not empty, not whitespace | null → FAIL |
| `@Email` | email | Valid email format (has @ and domain) | null → PASS (combine with @NotBlank) |
| `@Positive` | mdrRate | Value > 0 | null → PASS (field is optional) |
| `@URL` | url | Valid URL format (scheme + host) | null → PASS (combine with @NotBlank) |
| `@NotEmpty` | events[] | Array not null and not empty (length > 0) | null → FAIL |

### How Validation Failures Look

```json
// POST /v1/merchants with invalid data:
{
  "name": "",
  "email": "not-an-email",
  "businessType": "   ",
  "mdrRate": -5.0
}

// Response (400 Bad Request):
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [
      {"field": "name", "message": "Merchant name is required"},
      {"field": "email", "message": "Invalid email format"},
      {"field": "businessType", "message": "Business type is required"},
      {"field": "mdrRate", "message": "MDR rate must be positive"}
    ]
  }
}
```

---

## 11. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Why DTOs exist** | Never expose entities directly — DTOs control what goes in and out |
| 2 | **Input vs Output DTOs** | Input has validation; Output doesn't. Input lacks id/timestamps; Output includes them. |
| 3 | **@NotBlank** | Rejects null, empty, and whitespace — strictest string check |
| 4 | **@Email** | Validates email format (@ + domain) — passes null (combine with @NotBlank) |
| 5 | **@Positive** | Value must be > 0 — passes null (field is optional) |
| 6 | **@URL** | Hibernate Validator extra — validates scheme + host |
| 7 | **@NotEmpty on arrays** | Array must exist AND have at least 1 element |
| 8 | **rawKey security pattern** | Shown once at creation, null in all subsequent responses |
| 9 | **MapStruct @Mapper** | Interface → Spring generates implementation at compile time |
| 10 | **componentModel = "spring"** | Generated class is a Spring bean (injectable) |
| 11 | **@Mapping(ignore = true)** | Don't map this field (for auto-generated fields like id, timestamps) |
| 12 | **@Mapping(constant = "true")** | Hardcode a value (new merchants are always active) |
| 13 | **@MappingTarget** | Update existing object in place (preserve id, active, timestamps) |
| 14 | **void updateEntity()** | Modifies object by reference — no return needed |
| 15 | **Auto-mapping by name** | Same field names in DTO and Entity → MapStruct maps automatically |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part07-merchant-service-overview.md) | Merchant Service Overview |
| [Part 7a](./phase4-part07a-merchant-project-setup.md) | Project Setup |
| [Part 7b](./phase4-part07b-merchant-entities.md) | Entities |
| [Part 7c](./phase4-part07c-merchant-migrations.md) | Flyway Migrations |
| [Part 7d](./phase4-part07d-merchant-repositories.md) | Repositories |
| **Part 7e** | **DTOs + Mapper** (You are here) |
| [Part 7f](./phase4-part07f-merchant-services-tests.md) | Services + Tests |
| [Part 7g](./phase4-part07g-merchant-controller-docker.md) | Controller + Docker |

---

*Next: [Part 7f — Services + Tests (Business Logic)](./phase4-part07f-merchant-services-tests.md) →*
