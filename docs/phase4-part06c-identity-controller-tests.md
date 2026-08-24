# 🏗️ Phase 4 Part 6c: Identity Service — Controller, DTOs & Tests

> **"An endpoint without tests is a promise without proof — trust nothing unverified."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Previous** | [phase4-part06b-identity-jwt-auth.md](./phase4-part06b-identity-jwt-auth.md) |
| **Next** | [phase4-part07a-merchant-entities.md](./phase4-part07a-merchant-entities.md) |

---

## 📖 Table of Contents

1. [Overview & Purpose](#1-overview--purpose)
2. [Step-by-Step: DTOs (Request & Response Objects)](#2-step-by-step-dtos-request--response-objects)
3. [Step-by-Step: AuthController](#3-step-by-step-authcontroller)
4. [Step-by-Step: IdentityExceptionHandler](#4-step-by-step-identityexceptionhandler)
5. [Step-by-Step: UserMapper](#5-step-by-step-usermapper)
6. [Understanding the Tests](#6-understanding-the-tests)
7. [Unit Tests: AuthServiceTest](#7-unit-tests-authservicetest)
8. [Unit Tests: JwtServiceTest](#8-unit-tests-jwtservicetest)
9. [Controller Tests: AuthControllerTest](#9-controller-tests-authcontrollertest)
10. [Testing with curl](#10-testing-with-curl)
11. [Error Response Format](#11-error-response-format)
12. [How to Run & Verify](#12-how-to-run--verify)
13. [What You Learned](#13-what-you-learned)

---

## 1. Overview & Purpose

In **Part 6a** we built the data layer (entities, repositories, migrations).
In **Part 6b** we built the brain (JwtService, AuthService, SecurityConfig).
Now in **Part 6c** we build the **interface** — the HTTP layer that the outside world talks to.

### What We Build in This Part

| Component | Purpose |
|-----------|---------|
| **DTOs** | Define what JSON goes IN (requests) and OUT (responses) |
| **AuthController** | Receives HTTP requests, delegates to AuthService |
| **IdentityExceptionHandler** | Converts Java exceptions to proper HTTP error responses |
| **UserMapper** | Converts User entity to UserProfileResponse DTO |
| **Tests** | Prove everything works correctly |

### Layer Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    HTTP REQUEST FLOW                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Client sends JSON                                                  │
│       │                                                             │
│       ▼                                                             │
│  ┌──────────────────────────────────────────────────────────┐      │
│  │ SPRING MVC: Deserializes JSON → RegisterRequest object    │      │
│  │             Runs @Valid validation annotations             │      │
│  │             If invalid → MethodArgumentNotValidException  │      │
│  └──────────────────────────┬───────────────────────────────┘      │
│                             │ (valid request)                       │
│                             ▼                                       │
│  ┌──────────────────────────────────────────────────────────┐      │
│  │ AuthController: Receives request, delegates to service    │      │
│  │                 Wraps response in ApiResponse<T>          │      │
│  │                 Sets HTTP status code (201, 200, etc.)    │      │
│  └──────────────────────────┬───────────────────────────────┘      │
│                             │                                       │
│                             ▼                                       │
│  ┌──────────────────────────────────────────────────────────┐      │
│  │ AuthService: Business logic (from Part 6b)                │      │
│  │              May throw DuplicateResourceException          │      │
│  │              May throw UnauthorizedException               │      │
│  └──────────────────────────┬───────────────────────────────┘      │
│                             │ (exception thrown?)                    │
│                             ▼                                       │
│  ┌──────────────────────────────────────────────────────────┐      │
│  │ IdentityExceptionHandler: Catches exceptions              │      │
│  │                           Converts to JSON error response │      │
│  │                           Sets appropriate HTTP status     │      │
│  └──────────────────────────────────────────────────────────┘      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Step-by-Step: DTOs (Request & Response Objects)

### What Are DTOs and Why Do We Need Them?

**DTO = Data Transfer Object** — a simple class that carries data between layers.

**Why not just use the User entity directly?**

| Problem with exposing entities | How DTOs solve it |
|-------------------------------|-------------------|
| Entity has `passwordHash` → leaked to client! | DTO only has fields the client should see |
| Entity fields might not match what client sends | Request DTO has exactly what client provides |
| Validation annotations clutter the entity | Validation lives on the DTO |
| Entity changes → API changes (tight coupling) | DTO is the contract; entity can change freely |

### RegisterRequest

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/RegisterRequest.java`

```java
package com.payflow.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for user registration.
 * 
 * WHAT: Defines the JSON structure the client must send to /v1/auth/register
 * WHY:  Separates API contract from internal entity structure
 * HOW:  Jakarta validation annotations reject bad input BEFORE it hits the service
 * 
 * Example valid request:
 * {
 *   "fullName": "Tejaswi Kumar",
 *   "email": "tejaswi@example.com",
 *   "password": "MySecureP@ss1",
 *   "role": "MERCHANT"
 * }
 */
@Data                // Lombok: getters, setters, toString, equals, hashCode
@Builder             // Lombok: RegisterRequest.builder().email("x").build()
@NoArgsConstructor   // Required for JSON deserialization (Jackson needs no-arg constructor)
@AllArgsConstructor  // Required for @Builder
public class RegisterRequest {

    @NotBlank(message = "Full name is required")
    // @NotBlank rejects: null, "", "   " (empty or whitespace-only)
    @Size(min = 2, max = 100)
    // Between 2 and 100 characters
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    // @Email validates: must contain @ and a domain. Rejects "notanemail"
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be 8-100 characters")
    // Minimum 8 chars for security. Maximum 100 to prevent BCrypt DoS
    // (extremely long passwords take forever to hash).
    private String password;

    private String role;
    // Optional — defaults to "USER" in AuthService if null
    // Not @NotBlank because it's optional
}
```

### LoginRequest

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/LoginRequest.java`

```java
package com.payflow.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for user login.
 * 
 * Example:
 * {
 *   "email": "tejaswi@example.com",
 *   "password": "MySecureP@ss1"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;
    // No @Size here — we don't reveal password requirements during login
    // (that would help an attacker narrow down possible passwords)
}
```

### RefreshRequest

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/RefreshRequest.java`

```java
package com.payflow.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for token refresh.
 * 
 * Example:
 * {
 *   "refreshToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
```

### AuthResponse

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/AuthResponse.java`

```java
package com.payflow.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO returned after successful authentication (register/login/refresh).
 * 
 * Example response:
 * {
 *   "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
 *   "refreshToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
 *   "expiresIn": 900,
 *   "user": {
 *     "id": "uuid-123",
 *     "email": "tejaswi@example.com",
 *     "fullName": "Tejaswi Kumar",
 *     "role": "MERCHANT",
 *     "createdAt": "2024-01-15T10:30:00Z"
 *   }
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;
    // JWT for authorizing API calls. Send as: Authorization: Bearer <accessToken>

    private String refreshToken;
    // Opaque UUID token for getting new access tokens when this one expires

    private long expiresIn;
    // Seconds until access token expires (900 = 15 minutes)
    // Client uses this to know WHEN to refresh: setTimeout(refresh, expiresIn * 1000)

    private UserProfileResponse user;
    // Basic user info — saves an extra API call to /profile after login
}
```

### UserProfileResponse

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/UserProfileResponse.java`

```java
package com.payflow.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Response DTO for user profile information.
 * Used in AuthResponse (nested) and GET /v1/auth/profile (standalone).
 * 
 * NOTE: Does NOT include passwordHash, active status, or updatedAt.
 * Only information the client/UI needs to display.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private String id;        // User's UUID
    private String email;     // Display + contact
    private String fullName;  // Display name in UI
    private String role;      // "USER", "MERCHANT", or "ADMIN"
    private Instant createdAt; // Account creation timestamp
}
```

### Validation Annotations Cheat Sheet

| Annotation | What It Validates | Example |
|------------|------------------|---------|
| `@NotBlank` | Not null, not empty, not whitespace | Rejects: null, "", "   " |
| `@Email` | Valid email format | Rejects: "abc", "@x", "a@" |
| `@Size(min, max)` | String length within range | `@Size(min=8)` rejects "short" |
| `@NotNull` | Not null (but empty string OK) | Rejects: null. Allows: "" |
| `@Pattern` | Matches regex | `@Pattern(regexp="^(USER|MERCHANT)$")` |

---

## 3. Step-by-Step: AuthController

**File:** `backend/identity-service/src/main/java/com/payflow/identity/controller/AuthController.java`

### What It Does
The controller is the **entry point** for HTTP requests. It:
1. Receives the HTTP request
2. Deserializes JSON into Java objects
3. Triggers validation (`@Valid`)
4. Delegates to AuthService
5. Wraps the result in `ApiResponse` and sets the HTTP status code

### Why Controllers Should Be Thin
Controllers should do ZERO business logic. Their only job is HTTP translation:
- HTTP request → Java method call
- Java return value → HTTP response

All actual logic lives in the service layer. This makes business logic testable without HTTP.

### Implementation (Matching Actual Source Code)

```java
package com.payflow.identity.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.identity.dto.*;
import com.payflow.identity.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for authentication endpoints.
 * 
 * BASE PATH: /v1/auth
 * 
 * DESIGN:
 * • Thin controller — delegates ALL logic to AuthService
 * • Uses @Valid for input validation (triggers DTO annotations)
 * • Wraps responses in ApiResponse<T> for consistent JSON structure
 * • Uses common-lib's ApiResponse (shared across all microservices)
 */
@RestController                    // Handles HTTP requests, returns JSON (not views)
@RequestMapping("/v1/auth")        // All endpoints start with /v1/auth
@RequiredArgsConstructor           // Lombok: injects AuthService via constructor
public class AuthController {

    private final AuthService authService;

    // ═══════════════════════════════════════════════════════════════════
    // POST /v1/auth/register
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a new user account.
     * 
     * REQUEST:
     *   POST /v1/auth/register
     *   Content-Type: application/json
     *   Body: {"fullName": "...", "email": "...", "password": "...", "role": "..."}
     * 
     * RESPONSE (201 Created):
     *   {
     *     "success": true,
     *     "data": {
     *       "accessToken": "eyJ...",
     *       "refreshToken": "uuid",
     *       "expiresIn": 900,
     *       "user": { ... }
     *     }
     *   }
     * 
     * ERROR CASES:
     *   400 — Validation failed (missing/invalid fields)
     *   409 — Email already registered
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        // @Valid: Triggers Jakarta validation annotations on RegisterRequest
        //         If validation fails → MethodArgumentNotValidException → 400 response
        // @RequestBody: Tells Spring to parse the HTTP body as JSON → RegisterRequest

        AuthResponse response = authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)   // 201 — resource was created
                .body(ApiResponse.success(response));
        // ApiResponse.success() wraps in: { "success": true, "data": { ... } }
    }

    // ═══════════════════════════════════════════════════════════════════
    // POST /v1/auth/login
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Authenticates a user with email + password.
     * 
     * REQUEST:
     *   POST /v1/auth/login
     *   Body: {"email": "...", "password": "..."}
     * 
     * RESPONSE (200 OK):
     *   { "success": true, "data": { "accessToken": "...", ... } }
     * 
     * ERROR CASES:
     *   400 — Validation failed
     *   401 — Invalid email or password
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
        // 200 OK — no resource was created, just returning existing data
    }

    // ═══════════════════════════════════════════════════════════════════
    // POST /v1/auth/refresh
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Gets new tokens using a valid refresh token.
     * 
     * REQUEST:
     *   POST /v1/auth/refresh
     *   Body: {"refreshToken": "a1b2c3d4-e5f6-..."}
     * 
     * RESPONSE (200 OK):
     *   { "success": true, "data": { "accessToken": "NEW", "refreshToken": "NEW", ... } }
     * 
     * ERROR CASES:
     *   400 — Refresh token is blank
     *   401 — Invalid, expired, or already-used refresh token
     * 
     * SECURITY: Implements token rotation (old token revoked, new one issued)
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ═══════════════════════════════════════════════════════════════════
    // GET /v1/auth/profile
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the profile of the authenticated user.
     * 
     * HOW AUTHENTICATION WORKS FOR THIS ENDPOINT:
     * 1. Client sends request with "Authorization: Bearer <JWT>" header
     * 2. API Gateway validates the JWT (checks signature + expiry)
     * 3. API Gateway extracts userId from JWT and adds "X-User-Id" header
     * 4. Request reaches this endpoint with X-User-Id already set
     * 5. We just look up the user by that ID
     * 
     * REQUEST:
     *   GET /v1/auth/profile
     *   Headers: X-User-Id: <userId>  (set by API Gateway)
     * 
     * RESPONSE (200 OK):
     *   { "success": true, "data": { "id": "...", "email": "...", ... } }
     * 
     * WHY X-User-Id HEADER?
     * → Single Responsibility: Gateway handles JWT validation
     * → Performance: Validation happens once at gateway, not in every service
     * → Simplicity: Downstream services just read a header, no JWT parsing
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(
            @RequestHeader("X-User-Id") String userId) {
        // @RequestHeader: Extracts the X-User-Id HTTP header value
        UserProfileResponse profile = authService.getProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(profile));
    }
}
```

### Endpoint Summary

| Method | Path | Auth? | Status Codes | Description |
|--------|------|:-----:|-------------|-------------|
| POST | `/v1/auth/register` | No | 201, 400, 409 | Create new account |
| POST | `/v1/auth/login` | No | 200, 400, 401 | Authenticate |
| POST | `/v1/auth/refresh` | No | 200, 400, 401 | Rotate tokens |
| GET | `/v1/auth/profile` | Yes* | 200, 404 | Get user profile |

*Auth is handled by API Gateway via X-User-Id header, not by this service directly.

### Why `ApiResponse<T>` Wrapper?

Instead of returning raw objects, we wrap in `ApiResponse`:

```json
// SUCCESS:
{
  "success": true,
  "data": { ... actual response ... }
}

// ERROR:
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [ ... ]
  }
}
```

**Why?** Consistent structure makes it easy for the frontend to handle responses:
```javascript
const result = await fetch("/v1/auth/login", ...);
const json = await result.json();
if (json.success) {
  // Use json.data
} else {
  // Show json.error.message
}
```

---

## 4. Step-by-Step: IdentityExceptionHandler

**File:** `backend/identity-service/src/main/java/com/payflow/identity/exception/IdentityExceptionHandler.java`

### What It Does
Catches exceptions thrown anywhere in the service and converts them to proper HTTP error responses. Without this, Spring would return ugly HTML error pages.

### Why It's Important
- Frontend needs **consistent JSON** error format (not HTML)
- HTTP status codes must be **meaningful** (409 for duplicate, 401 for unauthorized)
- Internal details must **NEVER leak** to clients (no stack traces)

### Implementation (Matching Actual Source Code)

```java
package com.payflow.identity.exception;

import com.payflow.common.dto.ApiResponse;
import com.payflow.common.dto.ErrorResponse;
import com.payflow.common.dto.ValidationError;
import com.payflow.common.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Global exception handler for identity-service.
 * Converts exceptions into structured API error responses.
 * 
 * HOW IT WORKS:
 * 1. Controller method throws an exception (e.g., DuplicateResourceException)
 * 2. Spring intercepts it BEFORE returning to the client
 * 3. Finds the matching @ExceptionHandler method here
 * 4. Returns the structured error response instead
 * 
 * WHY @RestControllerAdvice?
 * → Applies to ALL controllers in this service automatically
 * → No try-catch blocks needed in controllers
 * → Centralized error formatting
 */
@RestControllerAdvice
public class IdentityExceptionHandler {

    /**
     * DUPLICATE EMAIL (409 CONFLICT)
     * 
     * Triggered when: User tries to register with an email that already exists
     * Source: AuthService.register() → DuplicateResourceException
     * 
     * Example response:
     * HTTP 409
     * { "success": false, "error": { "code": "DUPLICATE_RESOURCE", "message": "User with email 'x@y.com' already exists" } }
     */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    /**
     * UNAUTHORIZED (401)
     * 
     * Triggered when: Wrong password, invalid token, expired token, disabled account
     * Source: AuthService.login(), AuthService.refreshToken()
     * 
     * SECURITY: Message is intentionally vague for login failures
     * to prevent user enumeration.
     * 
     * Example response:
     * HTTP 401
     * { "success": false, "error": { "code": "UNAUTHORIZED", "message": "Invalid email or password" } }
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    /**
     * NOT FOUND (404)
     * 
     * Triggered when: User ID from header doesn't exist (profile endpoint)
     * Source: AuthService.getProfile()
     * 
     * Example response:
     * HTTP 404
     * { "success": false, "error": { "code": "NOT_FOUND", "message": "User not found" } }
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorResponse.of(ex.getErrorCode(), ex.getMessage())));
    }

    /**
     * VALIDATION ERRORS (400 BAD REQUEST)
     * 
     * Triggered when: @Valid fails on request DTO
     * Source: Spring MVC (automatic, before controller code runs)
     * 
     * Example: RegisterRequest with email = "notanemail" and password = "short"
     * 
     * Example response:
     * HTTP 400
     * {
     *   "success": false,
     *   "error": {
     *     "code": "VALIDATION_ERROR",
     *     "message": "Request validation failed",
     *     "details": [
     *       { "field": "email", "message": "Invalid email format", "rejectedValue": "notanemail" },
     *       { "field": "password", "message": "Password must be 8-100 characters", "rejectedValue": "short" }
     *     ]
     *   }
     * }
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ValidationError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::mapFieldError)
                .toList();
        ErrorResponse error = ErrorResponse.withDetails(
                "VALIDATION_ERROR", "Request validation failed", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(error));
    }

    /**
     * CATCH-ALL (500 INTERNAL SERVER ERROR)
     * 
     * Triggered when: Any unexpected exception (NullPointerException, DB errors, etc.)
     * 
     * SECURITY: NEVER expose internal details to clients!
     * Log the real error server-side, return generic message to client.
     * 
     * Example response:
     * HTTP 500
     * { "success": false, "error": { "code": "INTERNAL_ERROR", "message": "An unexpected error occurred" } }
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        // In production, also log the real exception:
        // log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorResponse.of("INTERNAL_ERROR",
                        "An unexpected error occurred")));
    }

    // ─── Helper ─────────────────────────────────────────────────────

    private ValidationError mapFieldError(FieldError fieldError) {
        return ValidationError.builder()
                .field(fieldError.getField())
                .message(fieldError.getDefaultMessage())
                .rejectedValue(fieldError.getRejectedValue())
                .build();
    }
}
```

### Exception → HTTP Status Mapping

| Exception | HTTP Status | Error Code | When |
|-----------|:-----------:|------------|------|
| `DuplicateResourceException` | 409 Conflict | DUPLICATE_RESOURCE | Email already registered |
| `UnauthorizedException` | 401 Unauthorized | UNAUTHORIZED | Wrong password, invalid token |
| `ResourceNotFoundException` | 404 Not Found | NOT_FOUND | User ID doesn't exist |
| `MethodArgumentNotValidException` | 400 Bad Request | VALIDATION_ERROR | @Valid failed |
| `Exception` (catch-all) | 500 Internal Server Error | INTERNAL_ERROR | Unexpected bugs |

---

## 5. Step-by-Step: UserMapper

**File:** `backend/identity-service/src/main/java/com/payflow/identity/mapper/UserMapper.java`

### What It Does
MapStruct generates code at **compile time** to convert a `User` entity to a `UserProfileResponse` DTO.

### Why Use MapStruct Instead of Manual Mapping?

| Manual | MapStruct |
|--------|-----------|
| Write `new UserProfileResponse(user.getId(), user.getEmail(), ...)` everywhere | Write mapping interface once, reuse everywhere |
| If you add a field to DTO, you might forget to map it | Compiler warns about unmapped fields |
| Runtime errors if mapping is wrong | Compile-time errors |

### Implementation

```java
package com.payflow.identity.mapper;

import com.payflow.identity.dto.UserProfileResponse;
import com.payflow.identity.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for User → UserProfileResponse conversion.
 * 
 * At compile time, MapStruct generates an implementation class
 * (UserMapperImpl) that does the field-by-field copying.
 * 
 * WHY @Mapping for role?
 * • User.role is type Role (enum)
 * • UserProfileResponse.role is type String
 * • We need to call .name() to convert enum → String
 */
@Mapper(componentModel = "spring")
// componentModel = "spring": Makes this a Spring bean, injectable with @Autowired
public interface UserMapper {

    @Mapping(target = "role", expression = "java(user.getRole().name())")
    // Custom mapping: Role.MERCHANT → "MERCHANT" string
    UserProfileResponse toProfileResponse(User user);
    
    // MapStruct auto-maps matching field names:
    // user.getId()        → response.setId()        ✓ (same name + type)
    // user.getEmail()     → response.setEmail()     ✓
    // user.getFullName()  → response.setFullName()  ✓
    // user.getCreatedAt() → response.setCreatedAt() ✓
    // user.getRole()      → response.setRole()      ✗ (Role vs String — needs @Mapping)
}
```

---

## 6. Understanding the Tests

### Test Types in This Project

| Test Type | What It Tests | Dependencies | Speed |
|-----------|--------------|-------------|-------|
| **Unit test** (AuthServiceTest) | Business logic in isolation | Mocked repos, mocked JWT | Very fast |
| **Unit test** (JwtServiceTest) | JWT generation/validation | None (uses reflection for config) | Very fast |
| **Controller test** (AuthControllerTest) | HTTP layer + validation | Mocked AuthService, real Spring MVC | Fast |
| **Integration test** | Full flow with real DB | Testcontainers PostgreSQL | Slower |

### Testing Pyramid

```
         ┌───────────┐
         │Integration│  ← Few: expensive, slow, but prove full flow works
         │   Tests   │
        ┌┴───────────┴┐
        │ Controller   │  ← Some: verify HTTP status codes, validation
        │   Tests      │
       ┌┴──────────────┴┐
       │  Unit Tests     │  ← Many: fast, isolated, test every edge case
       │(Service + JWT)  │
       └────────────────┘
```

---

## 7. Unit Tests: AuthServiceTest

**File:** `backend/identity-service/src/test/java/com/payflow/identity/service/AuthServiceTest.java`

### What It Tests
The AuthService business logic in **complete isolation** — no database, no web server, no JWT library. Everything is mocked.

### How Mocking Works

```
PRODUCTION:
  AuthService → calls → UserRepository → calls → PostgreSQL

UNIT TEST:
  AuthService → calls → MockUserRepository → returns fake data (no DB!)
```

This means:
- Tests run in milliseconds (no DB startup)
- Tests are deterministic (no flaky network/DB issues)
- Each test verifies ONE specific behavior

### Implementation (Matching Actual Source Code)

```java
package com.payflow.identity.service;

import com.payflow.common.exception.DuplicateResourceException;
import com.payflow.common.exception.UnauthorizedException;
import com.payflow.identity.dto.*;
import com.payflow.identity.model.RefreshToken;
import com.payflow.identity.model.Role;
import com.payflow.identity.model.User;
import com.payflow.identity.repository.RefreshTokenRepository;
import com.payflow.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService.
 * 
 * WHAT: Tests each business logic path in isolation.
 * HOW: Uses Mockito to replace real dependencies with controllable fakes.
 * WHY: Fast feedback, tests every edge case without infrastructure.
 * 
 * PATTERN: Given-When-Then (Arrange-Act-Assert)
 *   Given: Set up preconditions (mock returns)
 *   When:  Call the method under test
 *   Then:  Verify the result and interactions
 */
@ExtendWith(MockitoExtension.class)  // Activates @Mock and @InjectMocks
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    // Fake UserRepository — returns whatever we tell it to

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtService jwtService;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private AuthService authService;
    // Creates real AuthService, injecting all the @Mock objects above

    private User testUser;
    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        // Create a reusable test user
        testUser = User.builder()
                .id("user-123")
                .email("john@example.com")
                .passwordHash("encoded-password")
                .fullName("John Doe")
                .role(Role.USER)
                .active(true)
                .createdAt(Instant.now())
                .build();

        registerRequest = RegisterRequest.builder()
                .email("john@example.com")
                .password("password123")
                .fullName("John Doe")
                .build();

        loginRequest = LoginRequest.builder()
                .email("john@example.com")
                .password("password123")
                .build();
    }

    // ═══ REGISTRATION TESTS ═══════════════════════════════════════════

    @Test
    @DisplayName("register - should create user and return auth response")
    void register_Success() {
        // GIVEN: Email doesn't exist, password encoder works, save succeeds
        when(userRepository.existsByEmail(registerRequest.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(registerRequest.getPassword())).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(jwtService.generateAccessToken(anyString(), anyString(), anyString()))
                .thenReturn("access-token-123");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // WHEN: We call register
        AuthResponse response = authService.register(registerRequest);

        // THEN: Response has tokens and user info
        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("access-token-123");
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
        assertThat(response.getUser().getEmail()).isEqualTo("john@example.com");

        // VERIFY: Correct methods were called
        verify(userRepository).save(any(User.class));           // User was persisted
        verify(refreshTokenRepository).save(any(RefreshToken.class)); // Token was persisted
    }

    @Test
    @DisplayName("register - should throw DuplicateResourceException for existing email")
    void register_DuplicateEmail_Throws() {
        // GIVEN: Email already exists
        when(userRepository.existsByEmail(registerRequest.getEmail())).thenReturn(true);

        // WHEN/THEN: Exception is thrown
        assertThatThrownBy(() -> authService.register(registerRequest))
                .isInstanceOf(DuplicateResourceException.class);

        // VERIFY: Nothing was saved (registration aborted early)
        verify(userRepository, never()).save(any(User.class));
    }

    // ═══ LOGIN TESTS ══════════════════════════════════════════════════

    @Test
    @DisplayName("login - should return auth response for valid credentials")
    void login_Success() {
        // GIVEN: User exists and password matches
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), testUser.getPasswordHash()))
                .thenReturn(true);
        when(jwtService.generateAccessToken(anyString(), anyString(), anyString()))
                .thenReturn("access-token-456");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // WHEN
        AuthResponse response = authService.login(loginRequest);

        // THEN
        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("access-token-456");
        assertThat(response.getUser().getEmail()).isEqualTo("john@example.com");
    }

    @Test
    @DisplayName("login - should throw UnauthorizedException for wrong password")
    void login_WrongPassword_Throws() {
        // GIVEN: User exists but password doesn't match
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(loginRequest.getPassword(), testUser.getPasswordHash()))
                .thenReturn(false);  // Password mismatch!

        // WHEN/THEN
        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid email or password");
    }

    // ═══ TOKEN REFRESH TESTS ══════════════════════════════════════════

    @Test
    @DisplayName("refreshToken - should generate new tokens with valid refresh token")
    void refreshToken_Success() {
        // GIVEN: Valid non-expired, non-revoked token exists
        RefreshToken existingToken = RefreshToken.builder()
                .id("token-id-1")
                .token("valid-refresh-token")
                .userId("user-123")
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(false)
                .build();

        RefreshRequest refreshRequest = new RefreshRequest();
        refreshRequest.setRefreshToken("valid-refresh-token");

        when(refreshTokenRepository.findByTokenAndRevokedFalse("valid-refresh-token"))
                .thenReturn(Optional.of(existingToken));
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(testUser));
        when(jwtService.generateAccessToken(anyString(), anyString(), anyString()))
                .thenReturn("new-access-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(3600L);

        // WHEN
        AuthResponse response = authService.refreshToken(refreshRequest);

        // THEN
        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        assertThat(response.getRefreshToken()).isNotBlank();

        // VERIFY: Old token was revoked + new token was saved
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("refreshToken - should throw UnauthorizedException for expired token")
    void refreshToken_ExpiredToken_Throws() {
        // GIVEN: Token exists but expired yesterday
        RefreshToken expiredToken = RefreshToken.builder()
                .id("token-id-2")
                .token("expired-refresh-token")
                .userId("user-123")
                .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))  // EXPIRED
                .revoked(false)
                .build();

        RefreshRequest refreshRequest = new RefreshRequest();
        refreshRequest.setRefreshToken("expired-refresh-token");

        when(refreshTokenRepository.findByTokenAndRevokedFalse("expired-refresh-token"))
                .thenReturn(Optional.of(expiredToken));

        // WHEN/THEN
        assertThatThrownBy(() -> authService.refreshToken(refreshRequest))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Refresh token expired");
    }
}
```

### Key Testing Concepts

| Concept | What It Means | Example |
|---------|--------------|---------|
| `@Mock` | Creates a fake implementation | `@Mock UserRepository` → fake that returns nothing by default |
| `@InjectMocks` | Creates real object with mocks injected | `@InjectMocks AuthService` → real service with fake dependencies |
| `when().thenReturn()` | "When this method is called, return this value" | `when(repo.findByEmail("x")).thenReturn(Optional.of(user))` |
| `verify()` | Assert that a method was called | `verify(repo).save(any())` → "save() must have been called" |
| `verify(never())` | Assert a method was NOT called | `verify(repo, never()).save(any())` → "save() must NOT have been called" |
| `assertThatThrownBy()` | Assert that an exception is thrown | Checks both exception type and message |

---

## 8. Unit Tests: JwtServiceTest

**File:** `backend/identity-service/src/test/java/com/payflow/identity/service/JwtServiceTest.java`

### What It Tests
JWT token generation and validation — without any Spring context or mocking.

```java
@DisplayName("JwtService Unit Tests")
class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        // Use reflection to set @Value fields (normally injected by Spring)
        ReflectionTestUtils.setField(jwtService, "jwtSecret",
                "payflow-test-secret-key-that-is-at-least-32-bytes-long-for-hmac");
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 3600000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", 604800000L);
    }

    @Test
    @DisplayName("generateAccessToken - should return a valid JWT with 3 parts")
    void generateAccessToken_ReturnsNonNull() {
        String token = jwtService.generateAccessToken("user-123", "john@example.com", "USER");

        assertThat(token).isNotNull().isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);  // header.payload.signature
    }

    @Test
    @DisplayName("extractUserId - should return the subject from the token")
    void extractUserId_ReturnsCorrectSubject() {
        String token = jwtService.generateAccessToken("user-456", "jane@example.com", "MERCHANT");
        String userId = jwtService.extractUserId(token);
        assertThat(userId).isEqualTo("user-456");
    }

    @Test
    @DisplayName("isTokenValid - should return false for expired token")
    void isTokenValid_WithExpiredToken_ReturnsFalse() {
        // Set expiration to negative (already expired)
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", -1000L);
        String token = jwtService.generateAccessToken("user-expired", "x@y.com", "USER");
        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    @Test
    @DisplayName("isTokenValid - should return false for tampered token")
    void isTokenValid_WithTamperedToken_ReturnsFalse() {
        String token = jwtService.generateAccessToken("user-123", "john@example.com", "USER");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }

    @Test
    @DisplayName("extractClaims - should contain email and role")
    void extractClaims_ContainsCustomClaims() {
        String token = jwtService.generateAccessToken("user-claims", "test@x.com", "MERCHANT");
        var claims = jwtService.extractClaims(token);
        assertThat(claims.getSubject()).isEqualTo("user-claims");
        assertThat(claims.get("email", String.class)).isEqualTo("test@x.com");
        assertThat(claims.get("role", String.class)).isEqualTo("MERCHANT");
    }
}
```

---

## 9. Controller Tests: AuthControllerTest

**File:** `backend/identity-service/src/test/java/com/payflow/identity/controller/AuthControllerTest.java`

### What It Tests
HTTP behavior — correct status codes, JSON structure, and validation errors. Uses `@WebMvcTest` which starts ONLY the web layer (no database, no service logic).

```java
@WebMvcTest(AuthController.class)   // Only loads controller + Spring MVC
@DisplayName("AuthController Integration Tests")
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;      // Simulates HTTP requests
    @MockBean private AuthService authService; // Fake service (injected into controller)
    @Autowired private ObjectMapper objectMapper; // JSON serializer

    @Test
    @DisplayName("POST /v1/auth/register - should return 201 Created on success")
    void register_ReturnsCreated() throws Exception {
        // GIVEN: AuthService returns a successful response
        AuthResponse mockResponse = AuthResponse.builder()
                .accessToken("jwt-access-token")
                .refreshToken("refresh-token-uuid")
                .expiresIn(3600L)
                .user(UserProfileResponse.builder()
                        .id("user-123").email("john@example.com")
                        .fullName("John Doe").role("USER").build())
                .build();
        when(authService.register(any(RegisterRequest.class))).thenReturn(mockResponse);

        // WHEN: We send a valid registration request
        RegisterRequest request = RegisterRequest.builder()
                .email("john@example.com").password("password123").fullName("John Doe").build();

        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // THEN: HTTP 201 with correct JSON structure
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").value("jwt-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-uuid"))
                .andExpect(jsonPath("$.data.user.email").value("john@example.com"));
    }

    @Test
    @DisplayName("POST /v1/auth/register - should return 400 for missing email")
    void register_MissingEmail_ReturnsBadRequest() throws Exception {
        // WHEN: Email is missing from request
        RegisterRequest request = RegisterRequest.builder()
                .password("password123").fullName("John Doe").build();

        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // THEN: 400 Bad Request (validation failed)
                .andExpect(status().isBadRequest());
        // AuthService is NEVER called (validation fails before it reaches the service)
    }

    @Test
    @DisplayName("POST /v1/auth/register - should return 400 for invalid email format")
    void register_InvalidEmail_ReturnsBadRequest() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("not-an-email").password("password123").fullName("John Doe").build();

        mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
```

### MockMvc Explained

| MockMvc method | What it does |
|---------------|-------------|
| `mockMvc.perform(post("/v1/auth/register"))` | Simulates sending POST request |
| `.contentType(MediaType.APPLICATION_JSON)` | Sets Content-Type header |
| `.content(json)` | Sets request body |
| `.andExpect(status().isCreated())` | Asserts HTTP 201 response |
| `.andExpect(jsonPath("$.data.accessToken").value("x"))` | Asserts JSON field value |

---

## 10. Testing with curl

### Prerequisites
- Identity Service running on port 8081
- PostgreSQL running with `payflow_identity` database

### Register a New User

```bash
curl -s -X POST http://localhost:8081/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Tejaswi Kumar",
    "email": "tejaswi@payflow.com",
    "password": "S3cur3P@ss!",
    "role": "MERCHANT"
  }' | jq
```

**Expected Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhYmMxMjMuLi4...",
    "refreshToken": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "expiresIn": 900,
    "user": {
      "id": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
      "email": "tejaswi@payflow.com",
      "fullName": "Tejaswi Kumar",
      "role": "MERCHANT",
      "createdAt": "2024-01-15T10:30:00Z"
    }
  }
}
```

### Login

```bash
curl -s -X POST http://localhost:8081/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "tejaswi@payflow.com",
    "password": "S3cur3P@ss!"
  }' | jq
```

### Refresh Token (use refreshToken from login response)

```bash
curl -s -X POST http://localhost:8081/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
  }' | jq
```

### Get Profile (use X-User-Id from response)

```bash
curl -s -X GET http://localhost:8081/v1/auth/profile \
  -H "X-User-Id: 5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d" | jq
```

### Test Validation Errors

```bash
curl -s -X POST http://localhost:8081/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "",
    "email": "not-an-email",
    "password": "short"
  }' | jq
```

**Expected (400 Bad Request):**
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [
      { "field": "fullName", "message": "Full name is required" },
      { "field": "email", "message": "Invalid email format" },
      { "field": "password", "message": "Password must be 8-100 characters" }
    ]
  }
}
```

### Test Duplicate Email

```bash
# Register same email twice:
curl -s -X POST http://localhost:8081/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Test","email":"dup@test.com","password":"Pass12345"}' | jq

curl -s -X POST http://localhost:8081/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Test2","email":"dup@test.com","password":"Pass12345"}' | jq
# Second one returns 409 Conflict
```

---

## 11. Error Response Format

All errors follow a consistent structure from `common-lib`:

```
┌──────────────────────────────────────────────────────────────────┐
│                    ERROR RESPONSE MAPPING                         │
├──────────────────────────────────┬──────┬────────────────────────┤
│ Scenario                         │ HTTP │ Error Code             │
├──────────────────────────────────┼──────┼────────────────────────┤
│ Email already registered         │ 409  │ DUPLICATE_RESOURCE     │
│ Wrong email or password          │ 401  │ UNAUTHORIZED           │
│ Invalid/expired refresh token    │ 401  │ UNAUTHORIZED           │
│ @Valid validation failed         │ 400  │ VALIDATION_ERROR       │
│ User ID not found (profile)      │ 404  │ NOT_FOUND              │
│ Unexpected bug                   │ 500  │ INTERNAL_ERROR         │
├──────────────────────────────────┴──────┴────────────────────────┤
│                                                                  │
│  Success format:                                                 │
│  { "success": true, "data": { ... } }                           │
│                                                                  │
│  Error format:                                                   │
│  { "success": false, "error": { "code": "...", "message": "..." } }  │
│                                                                  │
│  Validation error format (includes field-level details):         │
│  {                                                               │
│    "success": false,                                             │
│    "error": {                                                    │
│      "code": "VALIDATION_ERROR",                                 │
│      "message": "Request validation failed",                     │
│      "details": [                                                │
│        { "field": "email", "message": "Invalid email format" }   │
│      ]                                                           │
│    }                                                             │
│  }                                                               │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## 12. How to Run & Verify

### Run All Identity Service Tests

```bash
cd backend/identity-service
mvn test
```

**Expected output:**
```
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0  -- JwtServiceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0  -- AuthServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0  -- AuthControllerTest
[INFO] BUILD SUCCESS
```

### Run Only Specific Tests

```bash
# Just JWT tests
mvn test -Dtest=JwtServiceTest

# Just AuthService tests
mvn test -Dtest=AuthServiceTest

# Just Controller tests
mvn test -Dtest=AuthControllerTest
```

### Run the Service and Test with curl

```bash
# 1. Make sure PostgreSQL is running with payflow_identity database
# 2. Build common-lib first (if not done)
cd backend && mvn install -pl common-lib -am -DskipTests

# 3. Start Identity Service
cd identity-service && mvn spring-boot:run

# 4. In another terminal, test endpoints
curl -s -X POST http://localhost:8081/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Test User","email":"test@example.com","password":"Password1!"}' | jq
```

### Verify Swagger UI

Open browser: http://localhost:8081/swagger-ui/index.html

You should see all 4 endpoints documented with request/response schemas.

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | **DTOs with validation** | `@Valid` + Jakarta annotations reject bad input before service layer |
| 2 | **Thin controllers** | Controllers only handle HTTP; all logic in service layer |
| 3 | **@RestControllerAdvice** | Centralized exception handling — no try-catch in controllers |
| 4 | **ApiResponse wrapper** | Consistent JSON structure: `{ "success": true/false, "data"/"error": ... }` |
| 5 | **MapStruct** | Compile-time entity→DTO mapping; safer than manual conversion |
| 6 | **Mockito unit tests** | Test business logic in isolation with fake dependencies |
| 7 | **@WebMvcTest** | Test HTTP layer without starting full application |
| 8 | **MockMvc** | Simulate HTTP requests in tests without a real server |
| 9 | **Given-When-Then** | Test structure: setup, action, assertion |
| 10 | **Security in errors** | Never reveal whether email exists (same message for all login failures) |

---

## 📚 Document Index

| Document | Title |
|----------|-------|
| [Phase 4 Part 6a](./phase4-part06a-identity-entities.md) | Identity Service — Entities & Migrations |
| [Phase 4 Part 6b](./phase4-part06b-identity-jwt-auth.md) | Identity Service — JWT & Authentication |
| **Phase 4 Part 6c** | **Identity Service — Controller & Tests** (You are here) |
| [Phase 4 Part 7a](./phase4-part07a-merchant-entities.md) | Merchant Service — Entities |
| [Phase 4 Part 7b](./phase4-part07b-merchant-services.md) | Merchant Service — Services & Controller |

---

## 🚀 Next Steps

In **[Phase 4 Part 7a](./phase4-part07a-merchant-entities.md)**, we will implement:

1. Merchant entity with KYC fields (PAN, GST, bank details)
2. ApiKey entity with SHA-256 hashing
3. WebhookConfig and FeeConfig entities
4. Flyway migrations for all merchant tables
5. Entity relationship diagram for the merchant domain

---

## ✅ Identity Service Checklist

Before moving to the Merchant Service, verify:

- [ ] All 4 migrations run successfully (check Flyway output on startup)
- [ ] Register returns 201 with tokens
- [ ] Login returns 200 with tokens
- [ ] Duplicate email returns 409
- [ ] Wrong password returns 401
- [ ] Invalid input returns 400 with field errors
- [ ] Refresh returns new token pair (old token becomes invalid)
- [ ] Profile returns user info with valid X-User-Id
- [ ] All unit tests pass (`mvn test`)
- [ ] Swagger UI loads at /swagger-ui/index.html
