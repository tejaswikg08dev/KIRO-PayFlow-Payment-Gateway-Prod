# 🏗️ Phase 4 Part 7g: Merchant Service — Controller + curl Testing + Dockerfile (HTTP + Deploy)

> **"The controller is the waiter — it takes the order, passes it to the kitchen, and serves the result. It never cooks."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 7g — Controller + curl Testing + Dockerfile |
| **What You Build** | MerchantController.java, Dockerfile, curl test commands |
| **Previous** | [Part 7f — Services + Tests](./phase4-part07f-merchant-services-tests.md) |
| **Next** | [Phase 4 Part 8a — Payment Service Entities](./phase4-part08a-payment-entities.md) |

---

## 📖 Table of Contents

1. [What the Controller Does](#1-what-the-controller-does)
2. [Why One Controller (Not Three)](#2-why-one-controller-not-three)
3. [Folder Structure After This Part](#3-folder-structure-after-this-part)
4. [Step-by-Step: MerchantController.java](#4-step-by-step-merchantcontrollerjava)
5. [Step-by-Step: Dockerfile](#5-step-by-step-dockerfile)
6. [Testing with curl — Every Endpoint](#6-testing-with-curl--every-endpoint)
7. [How to Run and Verify Everything](#7-how-to-run-and-verify-everything)
8. [What You Learned](#8-what-you-learned)

---

## 1. What the Controller Does

The controller has exactly ONE job: **translate HTTP ↔ Java**.

```
HTTP Request  →  [deserialize JSON]  →  [validate @Valid]  →  [call service]  →  [serialize JSON]  →  HTTP Response
```

| Step | Who Does It | Example |
|---|---|---|
| Receive HTTP | Spring MVC + Tomcat | `POST /v1/merchants` arrives |
| Deserialize JSON → Java | Jackson (automatic) | `{"name":"Shop"}` → `MerchantRegisterRequest` |
| Validate input | Jakarta Validation (`@Valid`) | `@NotBlank` checks name is present |
| Call business logic | Controller calls Service | `merchantService.registerMerchant(request)` |
| Serialize Java → JSON | Jackson (automatic) | `MerchantResponse` → `{"id":"uuid",...}` |
| Set HTTP status | Controller via `ResponseEntity` | `201 Created` or `200 OK` |
| Wrap in standard format | `ApiResponse.success(data)` | `{"success":true,"data":{...}}` |

**The controller itself has ZERO business logic.** Every method is 2-3 lines: call service, wrap response, return.

---

## 2. Why One Controller (Not Three)

All 14 endpoints live in ONE controller because they're all **sub-resources of Merchant**:

```
/v1/merchants                        ← Merchant CRUD
/v1/merchants/{id}/api-keys          ← API Keys (belong to a merchant)
/v1/merchants/{id}/webhooks          ← Webhooks (belong to a merchant)
```

**Alternative: 3 controllers?**

| Approach | Files | URL Prefix |
|---|---|---|
| 1 controller (our choice) | `MerchantController.java` | All under `/v1/merchants` |
| 3 controllers | `MerchantController.java` + `ApiKeyController.java` + `WebhookController.java` | Same URLs but split across files |

We chose 1 controller because:
- All URLs share the same `/v1/merchants` prefix
- Sub-resources logically belong to the parent
- Single file is easier to navigate when all endpoints are related
- The controller is organized with clear section comments

---

## 3. Folder Structure After This Part

```
backend/merchant-service/
├── Dockerfile                                     ← YOU CREATE THIS
└── src/main/java/com/payflow/merchant/
    ├── ... (everything from 7a-7f)
    └── controller/                                ← YOU CREATE THIS FOLDER
        └── MerchantController.java                ← YOU CREATE THIS
```

---

## 4. Step-by-Step: MerchantController.java

**File:** `src/main/java/com/payflow/merchant/controller/MerchantController.java`

### Class Declaration

```java
package com.payflow.merchant.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.merchant.dto.*;
import com.payflow.merchant.model.WebhookConfig;
import com.payflow.merchant.service.ApiKeyService;
import com.payflow.merchant.service.MerchantService;
import com.payflow.merchant.service.WebhookConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
```

**KEY IMPORTS:**

| Import | What | Used For |
|---|---|---|
| `ApiResponse` | From common-lib | Wrap all responses in `{"success":true,"data":{...}}` |
| `@Valid` | Jakarta Validation | Trigger DTO validation annotations |
| `@RequiredArgsConstructor` | Lombok | Constructor injection for 3 services |
| `ResponseEntity` | Spring MVC | Control HTTP status code + body |
| `@RestController`, `@RequestMapping`, `@PostMapping`, etc. | Spring MVC | HTTP endpoint annotations |

```java
@RestController
```

**WHAT:** Combines two annotations:
- `@Controller` — "I handle HTTP requests"
- `@ResponseBody` — "My return values should be serialized to JSON (not rendered as HTML views)"

**WITHOUT `@RestController`:** Spring would try to find an HTML template named after the return value. With it, Jackson serializes the return value to JSON automatically.

```java
@RequestMapping("/v1/merchants")
```

**WHAT:** "All endpoints in this class start with `/v1/merchants`."

Every `@GetMapping`, `@PostMapping`, etc. below is RELATIVE to this base path:
- `@PostMapping` → `POST /v1/merchants`
- `@GetMapping("/{merchantId}")` → `GET /v1/merchants/{merchantId}`
- `@PostMapping("/{merchantId}/api-keys")` → `POST /v1/merchants/{merchantId}/api-keys`

```java
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private final ApiKeyService apiKeyService;
    private final WebhookConfigService webhookConfigService;
```

**THREE SERVICES INJECTED.** Each handles its own domain:
- `merchantService` → Merchant CRUD (5 endpoints)
- `apiKeyService` → API Key management (4 endpoints)
- `webhookConfigService` → Webhook configuration (4 endpoints)

The controller delegates to the right service for each endpoint. It never calls repositories directly.

---

### Section 1: Merchant CRUD (5 endpoints)

```java
    // ─── Merchant CRUD ───────────────────────────────────────────────────────────
```

Comment separators organize the 3 groups visually.

#### POST /v1/merchants — Register

```java
    @PostMapping
    public ResponseEntity<ApiResponse<MerchantResponse>> registerMerchant(
            @Valid @RequestBody MerchantRegisterRequest request) {
```

**LINE BY LINE:**

| Part | Meaning |
|---|---|
| `@PostMapping` | Handle HTTP POST to `/v1/merchants` (base path, no extra path) |
| `ResponseEntity<ApiResponse<MerchantResponse>>` | Return type: HTTP response wrapping ApiResponse wrapping MerchantResponse |
| `@Valid` | "Validate the request DTO BEFORE calling this method" |
| `@RequestBody` | "Deserialize the HTTP JSON body into `MerchantRegisterRequest`" |

**WHAT `@Valid` DOES:**
```
Client sends: {"name": "", "email": "bad", "businessType": null}
                    ↓
Jackson deserializes → MerchantRegisterRequest object
                    ↓
@Valid triggers validation:
  name = "" → @NotBlank FAILS → "Merchant name is required"
  email = "bad" → @Email FAILS → "Invalid email format"
  businessType = null → @NotBlank FAILS → "Business type is required"
                    ↓
Spring throws MethodArgumentNotValidException → 400 Bad Request
Controller method body NEVER RUNS.
```

```java
        MerchantResponse response = merchantService.registerMerchant(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
```

**TWO LINES OF ACTUAL LOGIC:**
1. Call service → get response
2. Wrap in ResponseEntity (201) + ApiResponse wrapper

**WHY `HttpStatus.CREATED` (201) NOT `OK` (200)?**
HTTP convention: POST that creates a new resource returns 201. GET/PUT/DELETE return 200.

| HTTP Method | Action | Status Code |
|---|---|---|
| POST (create) | Register merchant, Generate key, Create webhook | **201 Created** |
| GET (read) | Get merchant, List keys, List webhooks | **200 OK** |
| PUT (update) | Update merchant, Update webhook | **200 OK** |
| DELETE (deactivate) | Deactivate merchant/key/webhook | **200 OK** |

#### GET /v1/merchants/{merchantId} — Get One

```java
    @GetMapping("/{merchantId}")
    public ResponseEntity<ApiResponse<MerchantResponse>> getMerchant(
            @PathVariable UUID merchantId) {
```

**`@PathVariable UUID merchantId`:**
Extracts the value from the URL path:
```
GET /v1/merchants/a1b2c3d4-e5f6-7890-abcd-ef1234567890
                  └──────────────────┬──────────────────┘
                                merchantId = UUID.fromString("a1b2c3d4-...")
```

Spring automatically converts the path string to a `UUID` object. If the string isn't a valid UUID → Spring returns 400 Bad Request automatically.

```java
        MerchantResponse response = merchantService.getMerchant(merchantId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
```

**`ResponseEntity.ok(...)`** = shorthand for `ResponseEntity.status(HttpStatus.OK).body(...)`.

#### GET /v1/merchants — List All

```java
    @GetMapping
    public ResponseEntity<ApiResponse<List<MerchantResponse>>> getAllMerchants() {
        List<MerchantResponse> response = merchantService.getAllMerchants();
        return ResponseEntity.ok(ApiResponse.success(response));
    }
```

No path parameter — `@GetMapping` without a path matches the base URL `/v1/merchants`.

**Return type: `List<MerchantResponse>`** — the JSON response is an array of merchants.

#### PUT /v1/merchants/{merchantId} — Update

```java
    @PutMapping("/{merchantId}")
    public ResponseEntity<ApiResponse<MerchantResponse>> updateMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody MerchantRegisterRequest request) {
        MerchantResponse response = merchantService.updateMerchant(merchantId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
```

**TWO PARAMETERS:**
1. `@PathVariable` — which merchant to update (from URL)
2. `@RequestBody` — new data (from JSON body)

**SAME DTO FOR CREATE AND UPDATE:** `MerchantRegisterRequest` is reused. Both operations need the same fields. Some teams create separate `MerchantUpdateRequest` — but if they're identical, one DTO is simpler.

#### DELETE /v1/merchants/{merchantId} — Deactivate

```java
    @DeleteMapping("/{merchantId}")
    public ResponseEntity<ApiResponse<Void>> deactivateMerchant(
            @PathVariable UUID merchantId) {
        merchantService.deactivateMerchant(merchantId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
```

**`ApiResponse<Void>`:** No data in the response — just `{"success": true, "data": null}`.

**WHY NOT ACTUALLY DELETE?** This is a soft delete — sets `active=false`. The data remains for auditing. The HTTP method is `DELETE` because that's the RESTful convention, even though internally it's an update.

---

### Section 2: API Key Management (4 endpoints)

```java
    // ─── API Key Management ──────────────────────────────────────────────────────
```

#### POST /v1/merchants/{id}/api-keys — Generate

```java
    @PostMapping("/{merchantId}/api-keys")
    public ResponseEntity<ApiResponse<ApiKeyResponse>> generateApiKey(
            @PathVariable UUID merchantId) {
        ApiKeyResponse response = apiKeyService.generateApiKey(merchantId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
```

**NO `@RequestBody`** — generating an API key doesn't need input from the client. The service generates everything (random key, hash, prefix).

**The response includes `rawKey`** — shown ONCE. After this, rawKey is null in all responses.

#### GET /v1/merchants/{id}/api-keys — List

```java
    @GetMapping("/{merchantId}/api-keys")
    public ResponseEntity<ApiResponse<List<ApiKeyResponse>>> getApiKeys(
            @PathVariable UUID merchantId) {
        List<ApiKeyResponse> response = apiKeyService.getApiKeys(merchantId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
```

**Every `ApiKeyResponse` in this list has `rawKey = null`** — the raw key is never exposed after creation.

#### DELETE /v1/merchants/{id}/api-keys/{keyId} — Revoke

```java
    @DeleteMapping("/{merchantId}/api-keys/{keyId}")
    public ResponseEntity<ApiResponse<Void>> revokeApiKey(
            @PathVariable UUID merchantId,
            @PathVariable UUID keyId) {
        apiKeyService.revokeApiKey(keyId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
```

**TWO `@PathVariable`s:** `merchantId` from the parent path and `keyId` from the sub-resource path.

**NOTE:** `merchantId` isn't actually used in `revokeApiKey(keyId)` — the keyId is globally unique. But it's in the URL for RESTful consistency: "this key belongs to THIS merchant."

#### POST /v1/merchants/api-keys/validate — Validate

```java
    @PostMapping("/api-keys/validate")
    public ResponseEntity<ApiResponse<UUID>> validateApiKey(
            @RequestParam String key) {
        UUID merchantId = apiKeyService.validateApiKey(key);
        return ResponseEntity.ok(ApiResponse.success(merchantId));
    }
```

**`@RequestParam String key`:** Extracts from the URL query string:
```
POST /v1/merchants/api-keys/validate?key=pk_YWJjZGVmZ2hpamtsbW5vcHFy
                                      └────────────┬────────────────┘
                                                key = "pk_YWJjZGVm..."
```

**WHY `@RequestParam` NOT `@RequestBody`?**
- The API key is a single string, not a JSON object
- Query parameters are simpler for single values
- The Payment Service calls this endpoint internally — simpler to construct the URL

**Returns `UUID`** — the merchantId associated with the valid key.

---

### Section 3: Webhook Configuration (4 endpoints)

```java
    // ─── Webhook Configuration ───────────────────────────────────────────────────
```

#### POST /v1/merchants/{id}/webhooks — Create

```java
    @PostMapping("/{merchantId}/webhooks")
    public ResponseEntity<ApiResponse<WebhookConfig>> createWebhookConfig(
            @PathVariable UUID merchantId,
            @Valid @RequestBody WebhookConfigRequest request) {
        WebhookConfig config = webhookConfigService.createWebhookConfig(merchantId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(config));
    }
```

**NOTE: Returns `WebhookConfig` entity directly** (not a DTO). This is a simplification — the entity doesn't have sensitive internal fields, so it's safe to return as-is. In a stricter design, you'd create a `WebhookConfigResponse` DTO.

#### GET, PUT, DELETE — Standard Pattern

```java
    @GetMapping("/{merchantId}/webhooks")
    public ResponseEntity<ApiResponse<List<WebhookConfig>>> getWebhookConfigs(
            @PathVariable UUID merchantId) {
        List<WebhookConfig> configs = webhookConfigService.getWebhookConfigs(merchantId);
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @PutMapping("/{merchantId}/webhooks/{configId}")
    public ResponseEntity<ApiResponse<WebhookConfig>> updateWebhookConfig(
            @PathVariable UUID merchantId,
            @PathVariable UUID configId,
            @Valid @RequestBody WebhookConfigRequest request) {
        WebhookConfig config = webhookConfigService.updateWebhookConfig(configId, request);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @DeleteMapping("/{merchantId}/webhooks/{configId}")
    public ResponseEntity<ApiResponse<Void>> deactivateWebhookConfig(
            @PathVariable UUID merchantId,
            @PathVariable UUID configId) {
        webhookConfigService.deactivateWebhookConfig(configId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
```

All follow the same pattern: extract path variables → call service → wrap response.

---

## 5. Step-by-Step: Dockerfile

**File:** `backend/merchant-service/Dockerfile`

### What It Does

Creates a Docker image that can run the merchant service anywhere — your laptop, a server, AWS, etc.

### Line-by-Line

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
```

**`FROM`:** "Start with this base image." This image has Maven 3.9 + JDK 17 pre-installed.

**`AS builder`:** Names this stage "builder." We'll reference it later.

**WHY TWO STAGES?**
```
Stage 1 (builder): Has Maven + JDK → compiles code → produces JAR (~800MB image)
Stage 2 (runtime): Has only JRE → runs the JAR → tiny image (~180MB)

The final image ONLY contains Stage 2 — no compiler, no Maven, no source code.
```

```dockerfile
WORKDIR /app
```

**`WORKDIR`:** "All subsequent commands run in `/app` directory." Like `cd /app`.

```dockerfile
# Copy parent pom and all modules (Maven reactor requires all to be present)
COPY pom.xml .
COPY common-lib ./common-lib
COPY service-registry ./service-registry
COPY config-server ./config-server
COPY api-gateway ./api-gateway
COPY identity-service ./identity-service
COPY merchant-service ./merchant-service
COPY payment-service ./payment-service
COPY routing-service ./routing-service
COPY settlement-service ./settlement-service
COPY webhook-service ./webhook-service
COPY notification-service ./notification-service
COPY bank-simulator ./bank-simulator
```

**WHY COPY ALL MODULES?** Maven's parent POM (`backend/pom.xml`) declares all modules in its `<modules>` section. Maven's reactor needs all module directories to exist, even if we're only building one. Without them → `Could not find artifact com.payflow:common-lib`.

```dockerfile
# Build only the target service and its dependencies
RUN mvn clean package -pl merchant-service -am -DskipTests -B
```

**FLAG BY FLAG:**

| Flag | Meaning |
|---|---|
| `clean` | Delete previous build artifacts |
| `package` | Compile + run tests + create JAR |
| `-pl merchant-service` | "Project List: only build merchant-service" |
| `-am` | "Also Make: build dependencies too" (common-lib) |
| `-DskipTests` | Don't run tests (faster Docker builds) |
| `-B` | Batch mode (non-interactive, cleaner logs) |

**Result:** `merchant-service/target/merchant-service-1.0.0-SNAPSHOT.jar` (fat JAR with all dependencies).

```dockerfile
# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
```

**NEW BASE IMAGE:** Only JRE (Java Runtime Environment) — no JDK (compiler), no Maven. Alpine Linux = minimal OS (~5MB).

| Image | Size | Has |
|---|---|---|
| `maven:3.9-eclipse-temurin-17` | ~800MB | JDK + Maven + build tools |
| `eclipse-temurin:17-jre-alpine` | ~180MB | JRE only — just enough to run Java |

```dockerfile
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow
```

**SECURITY:**
1. Create a non-root user `payflow` and group `payflow`
2. Switch to that user for all subsequent commands

**WHY NOT RUN AS ROOT?**
If the app has a vulnerability, an attacker gains root access to the container → can escape to the host. Running as non-root limits the damage.

```dockerfile
COPY --from=builder /app/merchant-service/target/*.jar app.jar
```

**`--from=builder`:** Copy the JAR from Stage 1 into Stage 2. This is the bridge between stages.

Only the JAR is copied — not Maven, not the JDK, not the source code. The final image is minimal.

```dockerfile
EXPOSE 8082
```

**DOCUMENTATION ONLY.** Tells humans and tools "this container listens on port 8082." Doesn't actually open the port — you do that with `docker run -p 8082:8082`.

```dockerfile
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8082/actuator/health || exit 1
```

**DOCKER HEALTH CHECK:**

| Parameter | Meaning |
|---|---|
| `--interval=15s` | Check every 15 seconds |
| `--timeout=10s` | If check takes >10s, consider it failed |
| `--retries=5` | After 5 consecutive failures, mark container unhealthy |
| `--start-period=30s` | Wait 30s after start before checking (JVM needs time to boot) |
| `CMD wget ...` | The actual check: call `/actuator/health` |

If `/actuator/health` returns `{"status":"UP"}` → healthy. If unreachable → unhealthy → Docker/K8s can restart.

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**"When the container starts, run this command."** Equivalent to: `java -jar app.jar`

This starts the Spring Boot application inside the container.

### Build and Run

```bash
# Build the image (from backend/ directory)
docker build -t payflow/merchant-service:latest -f merchant-service/Dockerfile .

# Run the container
docker run -d \
  --name merchant-service \
  -p 8082:8082 \
  -e SPRING_PROFILES_ACTIVE=docker \
  payflow/merchant-service:latest
```

---

## 6. Testing with curl — Every Endpoint

### Prerequisites

```bash
# Merchant Service running on port 8082
# PostgreSQL running with payflow_merchant database
cd backend/merchant-service
mvn spring-boot:run
```

### 1. Register a Merchant

```bash
curl -s -X POST http://localhost:8082/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Rajesh Electronics",
    "email": "billing@rajesh-electronics.com",
    "businessType": "RETAIL",
    "mdrRate": 2.0
  }' | jq
```

**Expected (201 Created):**
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

**Save the `id` — you'll need it for all subsequent calls:**
```bash
MERCHANT_ID="a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

### 2. Test Duplicate Email (409)

```bash
curl -s -X POST http://localhost:8082/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{"name":"Duplicate","email":"billing@rajesh-electronics.com","businessType":"RETAIL"}' | jq
```

**Expected (409 Conflict):**
```json
{
  "success": false,
  "error": {
    "code": "DUPLICATE_RESOURCE",
    "message": "Merchant already exists with email: billing@rajesh-electronics.com"
  }
}
```

### 3. Test Validation Error (400)

```bash
curl -s -X POST http://localhost:8082/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{"name":"","email":"bad","businessType":"","mdrRate":-5}' | jq
```

**Expected (400 Bad Request):** Multiple field errors.

### 4. Get Merchant by ID

```bash
curl -s "http://localhost:8082/v1/merchants/$MERCHANT_ID" | jq
```

### 5. List All Merchants

```bash
curl -s http://localhost:8082/v1/merchants | jq
```

### 6. Update Merchant

```bash
curl -s -X PUT "http://localhost:8082/v1/merchants/$MERCHANT_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Rajesh Electronics (Updated)",
    "email": "billing@rajesh-electronics.com",
    "businessType": "E_COMMERCE",
    "mdrRate": 2.5
  }' | jq
```

### 7. Generate API Key ⚠️

```bash
curl -s -X POST "http://localhost:8082/v1/merchants/$MERCHANT_ID/api-keys" | jq
```

**Expected (201 Created):**
```json
{
  "success": true,
  "data": {
    "id": "key-uuid",
    "prefix": "YWJjZGVm",
    "merchantId": "a1b2c3d4-...",
    "active": true,
    "createdAt": "2024-01-15T10:35:00Z",
    "rawKey": "pk_YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXox"
  }
}
```

**⚠️ SAVE THE `rawKey` VALUE! It's shown ONLY NOW. Never again.**

```bash
API_KEY="pk_YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXox"
KEY_ID="key-uuid-from-response"
```

### 8. List API Keys (rawKey is null)

```bash
curl -s "http://localhost:8082/v1/merchants/$MERCHANT_ID/api-keys" | jq
```

**Notice:** `"rawKey": null` — the raw key is NEVER shown in listings.

### 9. Validate API Key

```bash
curl -s -X POST "http://localhost:8082/v1/merchants/api-keys/validate?key=$API_KEY" | jq
```

**Expected (200 OK):**
```json
{
  "success": true,
  "data": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

Returns the merchantId.

### 10. Create Webhook Config

```bash
curl -s -X POST "http://localhost:8082/v1/merchants/$MERCHANT_ID/webhooks" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://rajesh-electronics.com/payflow-webhook",
    "events": ["payment.authorized", "payment.captured", "payment.refunded"]
  }' | jq
```

**Expected (201 Created):** Response includes auto-generated `"secret": "whsec_..."`.

### 11. List Webhook Configs

```bash
curl -s "http://localhost:8082/v1/merchants/$MERCHANT_ID/webhooks" | jq
```

### 12. Revoke API Key

```bash
curl -s -X DELETE "http://localhost:8082/v1/merchants/$MERCHANT_ID/api-keys/$KEY_ID" | jq
```

### 13. Validate Revoked Key (Should Fail)

```bash
curl -s -X POST "http://localhost:8082/v1/merchants/api-keys/validate?key=$API_KEY" | jq
# Expected: 404 — "API Key has been revoked"
```

### 14. Deactivate Merchant

```bash
curl -s -X DELETE "http://localhost:8082/v1/merchants/$MERCHANT_ID" | jq
# Check: GET the merchant — active should be false
curl -s "http://localhost:8082/v1/merchants/$MERCHANT_ID" | jq '.data.active'
# Expected: false
```

---

## 7. How to Run and Verify Everything

### Step 1: Build common-lib

```bash
cd backend
mvn install -pl common-lib -am -DskipTests
```

### Step 2: Ensure PostgreSQL is running

```sql
CREATE DATABASE payflow_merchant;
GRANT ALL PRIVILEGES ON DATABASE payflow_merchant TO payflow;
\c payflow_merchant
GRANT ALL ON SCHEMA public TO payflow;
```

### Step 3: Run unit tests

```bash
cd merchant-service
mvn test
```

**Expected:**
```
[INFO] Tests run: 5, Failures: 0, Errors: 0 -- MerchantServiceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0 -- ApiKeyServiceTest
[INFO] BUILD SUCCESS
```

### Step 4: Start the service

```bash
mvn spring-boot:run
```

**Expected console output:**
```
Flyway: Migrating schema "public" to version "1 - create merchants table"
Flyway: Migrating schema "public" to version "2 - create api keys table"
Flyway: Migrating schema "public" to version "3 - create webhook configs table"
Flyway: Migrating schema "public" to version "4 - create fee configs table"
Started MerchantServiceApplication in 4.2 seconds
```

### Step 5: Verify Swagger UI

Open: http://localhost:8082/swagger-ui.html

All 14 endpoints should be listed with request/response schemas.

### Step 6: Run curl tests

Execute the curl commands from Section 6 above.

### ✅ Verification Checklist

- [ ] All 4 Flyway migrations run successfully
- [ ] `POST /v1/merchants` → 201 Created
- [ ] Duplicate email → 409 Conflict
- [ ] Invalid input → 400 with field errors
- [ ] `GET /v1/merchants/{id}` → 200 with merchant data
- [ ] `PUT /v1/merchants/{id}` → 200 with updated data
- [ ] `DELETE /v1/merchants/{id}` → 200, active=false
- [ ] `POST /{id}/api-keys` → 201 with rawKey visible
- [ ] `GET /{id}/api-keys` → 200 with rawKey=null
- [ ] `POST /api-keys/validate?key=pk_...` → 200 with merchantId
- [ ] Revoked key validation → 404
- [ ] `POST /{id}/webhooks` → 201 with whsec_ secret
- [ ] All 11 unit tests pass
- [ ] Swagger UI loads

---

## 8. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **@RestController** | `@Controller` + `@ResponseBody` — returns JSON, not HTML |
| 2 | **@RequestMapping** | Sets the base URL path for all endpoints in the class |
| 3 | **@PostMapping/@GetMapping/@PutMapping/@DeleteMapping** | Map HTTP methods to Java methods |
| 4 | **@PathVariable** | Extract value from URL path (`/merchants/{id}` → `id`) |
| 5 | **@RequestParam** | Extract value from query string (`?key=pk_...` → `key`) |
| 6 | **@Valid + @RequestBody** | Deserialize JSON + validate before method runs |
| 7 | **ResponseEntity** | Control HTTP status code (201 Created vs 200 OK) |
| 8 | **ApiResponse.success()** | Standard wrapper: `{"success":true,"data":{...}}` |
| 9 | **One controller, 3 sections** | Sub-resources live under parent resource path |
| 10 | **Multi-stage Docker build** | Builder stage (800MB) → Runtime stage (180MB) |
| 11 | **Non-root Docker user** | Security: `adduser payflow` + `USER payflow` |
| 12 | **HEALTHCHECK** | Docker checks `/actuator/health` every 15s |
| 13 | **-pl and -am flags** | Build specific module + its dependencies |
| 14 | **ENTRYPOINT** | The command Docker runs when container starts |

---

## 🎉 Merchant Service Complete!

You've built the entire Merchant Service from scratch across 7 parts:

| Part | What You Built | Files |
|---|---|---|
| **7a** | Project Setup | pom.xml, application.yml, main class, security |
| **7b** | Entities | Merchant, ApiKey, WebhookConfig, FeeConfig |
| **7c** | Migrations | V1-V4 SQL files |
| **7d** | Repositories | 3 data access interfaces |
| **7e** | DTOs + Mapper | 4 DTOs, MerchantMapper with 3 methods |
| **7f** | Services + Tests | 3 services, 11 unit tests |
| **7g** | Controller + Docker | 14 endpoints, Dockerfile |

**Total: 22 Java files + 4 SQL files + 2 config files + 1 Dockerfile = 29 files.**

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
| [Part 7f](./phase4-part07f-merchant-services-tests.md) | Services + Tests |
| **Part 7g** | **Controller + Docker** (You are here) |

---

*Next: [Phase 4 Part 8a — Payment Service Entities](./phase4-part08a-payment-entities.md) →*
