# 🏗️ Phase 4 Part 6c: Identity Service — Controller & Tests

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

1. [Overview](#1-overview)
2. [DTOs — Request & Response Objects](#2-dtos--request--response-objects)
3. [AuthController Implementation](#3-authcontroller-implementation)
4. [GlobalExceptionHandler](#4-globalexceptionhandler)
5. [Unit Tests with Mockito](#5-unit-tests-with-mockito)
6. [Integration Tests with SpringBootTest](#6-integration-tests-with-springboottest)
7. [curl Examples](#7-curl-examples)
8. [Error Response Format](#8-error-response-format)
9. [What You Learned](#9-what-you-learned)

---

## 1. Overview

The controller layer ties everything together — it receives HTTP requests, validates input,
delegates to the service layer, and returns structured responses.

**Component Architecture:**

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CONTROLLER LAYER                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  HTTP Request                                                       │
│       │                                                             │
│       ▼                                                             │
│  ┌──────────────────────┐                                           │
│  │   AuthController     │  Receives request, validates input        │
│  │   • register()       │                                           │
│  │   • login()          │                                           │
│  │   • refresh()        │                                           │
│  │   • getProfile()     │                                           │
│  └──────────┬───────────┘                                           │
│             │                                                       │
│             ▼                                                       │
│  ┌──────────────────────┐                                           │
│  │    AuthService       │  Business logic execution                 │
│  └──────────┬───────────┘                                           │
│             │                                                       │
│             ▼                                                       │
│  ┌──────────────────────┐     ┌──────────────────────────────┐     │
│  │   UserRepository     │     │  GlobalExceptionHandler       │     │
│  │   RefreshTokenRepo   │     │  Maps exceptions → HTTP codes │     │
│  └──────────────────────┘     └──────────────────────────────┘     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

**Endpoint Summary:**

| Method | Path | Auth Required | Description |
|--------|------|:-------------:|-------------|
| POST | `/api/v1/auth/register` | No | Create new account |
| POST | `/api/v1/auth/login` | No | Authenticate user |
| POST | `/api/v1/auth/refresh` | No | Rotate tokens |
| GET | `/api/v1/auth/profile` | Yes (userId header) | Get current user profile |

---

## 2. DTOs — Request & Response Objects

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/RegisterRequest.java`

```java
package com.payflow.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for user registration.
 * Validates input before it reaches the service layer.
 */
public class RegisterRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 64, message = "Password must be between 8 and 64 characters")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]+$",
        message = "Password must contain uppercase, lowercase, digit, and special character"
    )
    private String password;

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 150, message = "Full name must be between 2 and 150 characters")
    private String fullName;

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "^(USER|MERCHANT|ADMIN)$", message = "Role must be USER, MERCHANT, or ADMIN")
    private String role;

    // Constructors
    public RegisterRequest() {}

    public RegisterRequest(String email, String password, String fullName, String role) {
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.role = role;
    }

    // Getters and Setters
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
```

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/LoginRequest.java`

```java
package com.payflow.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    public LoginRequest() {}

    public LoginRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
```

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/RefreshRequest.java`

```java
package com.payflow.identity.dto;

import jakarta.validation.constraints.NotBlank;

public class RefreshRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;

    public RefreshRequest() {}

    public RefreshRequest(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
```

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/AuthResponse.java`

```java
package com.payflow.identity.dto;

import java.util.UUID;

/**
 * Response returned after successful authentication (register or login).
 */
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private UUID userId;
    private String tokenType;
    private long expiresIn;

    public AuthResponse(String accessToken, String refreshToken, UUID userId) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
        this.tokenType = "Bearer";
        this.expiresIn = 900; // 15 minutes in seconds
    }

    // Getters
    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public UUID getUserId() { return userId; }
    public String getTokenType() { return tokenType; }
    public long getExpiresIn() { return expiresIn; }
}
```

**File:** `backend/identity-service/src/main/java/com/payflow/identity/dto/UserProfileResponse.java`

```java
package com.payflow.identity.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public class UserProfileResponse {

    private UUID id;
    private String email;
    private String fullName;
    private String role;
    private LocalDateTime createdAt;

    public UserProfileResponse(UUID id, String email, String fullName,
                               String role, LocalDateTime createdAt) {
        this.id = id;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.createdAt = createdAt;
    }

    // Getters
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getRole() { return role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

---

## 3. AuthController Implementation

**File:** `backend/identity-service/src/main/java/com/payflow/identity/controller/AuthController.java`

```java
package com.payflow.identity.controller;

import com.payflow.identity.dto.*;
import com.payflow.identity.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for authentication operations.
 * 
 * Base path: /api/v1/auth
 * All endpoints are publicly accessible (auth handled at gateway level).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Register a new user account.
     * 
     * POST /api/v1/auth/register
     * Returns 201 CREATED with access + refresh tokens.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticate with email and password.
     * 
     * POST /api/v1/auth/login
     * Returns 200 OK with access + refresh tokens.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Refresh an access token using a valid refresh token.
     * Implements token rotation (old token revoked, new pair issued).
     * 
     * POST /api/v1/auth/refresh
     * Returns 200 OK with new access + refresh tokens.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        AuthResponse response = authService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    /**
     * Get the profile of the authenticated user.
     * The userId is passed by the API Gateway after JWT validation.
     * 
     * GET /api/v1/auth/profile
     * Header: X-User-Id (set by gateway)
     * Returns 200 OK with user profile.
     */
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(
            @RequestHeader("X-User-Id") UUID userId) {
        UserProfileResponse response = authService.getProfile(userId);
        return ResponseEntity.ok(response);
    }
}
```

---

## 4. GlobalExceptionHandler

**File:** `backend/identity-service/src/main/java/com/payflow/identity/exception/GlobalExceptionHandler.java`

```java
package com.payflow.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler that maps service exceptions to HTTP responses.
 * Provides consistent error response format across all endpoints.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles duplicate email registration attempts.
     * Maps to 409 CONFLICT.
     */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.CONFLICT.value(),
            "DUPLICATE_EMAIL",
            ex.getMessage(),
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handles invalid login credentials.
     * Maps to 401 UNAUTHORIZED.
     * Note: intentionally vague message to prevent user enumeration.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.UNAUTHORIZED.value(),
            "INVALID_CREDENTIALS",
            "Invalid email or password",
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * Handles invalid or expired tokens.
     * Maps to 401 UNAUTHORIZED.
     */
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.UNAUTHORIZED.value(),
            "INVALID_TOKEN",
            ex.getMessage(),
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * Handles Bean Validation errors (@Valid annotation failures).
     * Maps to 400 BAD_REQUEST with field-level error details.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(
            MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ValidationErrorResponse error = new ValidationErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "VALIDATION_FAILED",
            "Request validation failed",
            fieldErrors,
            LocalDateTime.now()
        );
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Catch-all handler for unexpected exceptions.
     * Maps to 500 INTERNAL_SERVER_ERROR.
     * Never expose internal details to clients.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        ErrorResponse error = new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
```

**Error Response Records:**

```java
package com.payflow.identity.exception;

import java.time.LocalDateTime;
import java.util.Map;

public record ErrorResponse(
    int status,
    String code,
    String message,
    LocalDateTime timestamp
) {}

public record ValidationErrorResponse(
    int status,
    String code,
    String message,
    Map<String, String> fieldErrors,
    LocalDateTime timestamp
) {}
```

---

## 5. Unit Tests with Mockito

**File:** `backend/identity-service/src/test/java/com/payflow/identity/service/AuthServiceTest.java`

```java
package com.payflow.identity.service;

import com.payflow.identity.dto.AuthResponse;
import com.payflow.identity.dto.LoginRequest;
import com.payflow.identity.dto.RegisterRequest;
import com.payflow.identity.exception.DuplicateEmailException;
import com.payflow.identity.exception.InvalidCredentialsException;
import com.payflow.identity.exception.InvalidTokenException;
import com.payflow.identity.model.RefreshToken;
import com.payflow.identity.model.Role;
import com.payflow.identity.model.User;
import com.payflow.identity.repository.RefreshTokenRepository;
import com.payflow.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtService jwtService;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
            userRepository, refreshTokenRepository,
            jwtService, passwordEncoder, 7L
        );
    }

    @Nested
    @DisplayName("Registration Tests")
    class RegisterTests {

        @Test
        @DisplayName("Should register user successfully")
        void register_Success() {
            // Given
            RegisterRequest request = new RegisterRequest(
                "john@example.com", "P@ssw0rd!", "John Doe", "USER"
            );
            User savedUser = new User("john@example.com", "hashed", "John Doe", Role.USER);

            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("hashed");
            when(userRepository.save(any(User.class))).thenReturn(savedUser);
            when(jwtService.generateAccessToken(any(User.class))).thenReturn("access-token");
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            AuthResponse response = authService.register(request);

            // Then
            assertThat(response.getAccessToken()).isEqualTo("access-token");
            assertThat(response.getRefreshToken()).isNotBlank();
            verify(userRepository).save(any(User.class));
            verify(passwordEncoder).encode("P@ssw0rd!");
        }

        @Test
        @DisplayName("Should throw DuplicateEmailException when email exists")
        void register_DuplicateEmail() {
            // Given
            RegisterRequest request = new RegisterRequest(
                "existing@example.com", "P@ssw0rd!", "Jane", "USER"
            );
            when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

            // When/Then
            assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateEmailException.class);
            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Login Tests")
    class LoginTests {

        @Test
        @DisplayName("Should login successfully with valid credentials")
        void login_Success() {
            // Given
            LoginRequest request = new LoginRequest("john@example.com", "P@ssw0rd!");
            User user = new User("john@example.com", "hashed", "John Doe", Role.USER);

            when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("P@ssw0rd!", "hashed")).thenReturn(true);
            when(jwtService.generateAccessToken(user)).thenReturn("access-token");
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            // When
            AuthResponse response = authService.login(request);

            // Then
            assertThat(response.getAccessToken()).isEqualTo("access-token");
        }

        @Test
        @DisplayName("Should throw InvalidCredentialsException for wrong password")
        void login_WrongPassword() {
            // Given
            LoginRequest request = new LoginRequest("john@example.com", "wrong");
            User user = new User("john@example.com", "hashed", "John", Role.USER);

            when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

            // When/Then
            assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
        }

        @Test
        @DisplayName("Should throw InvalidCredentialsException for unknown email")
        void login_UnknownEmail() {
            // Given
            LoginRequest request = new LoginRequest("unknown@example.com", "pass");
            when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);
        }
    }

    @Nested
    @DisplayName("Token Refresh Tests")
    class RefreshTests {

        @Test
        @DisplayName("Should detect reuse of revoked token and invalidate all sessions")
        void refresh_ReuseDetection() {
            // Given — a token that was already revoked
            UUID userId = UUID.randomUUID();
            RefreshToken revokedToken = new RefreshToken("old-token", userId,
                LocalDateTime.now().plusDays(7));
            revokedToken.revoke();

            when(refreshTokenRepository.findByToken("old-token"))
                .thenReturn(Optional.of(revokedToken));

            // When/Then
            assertThatThrownBy(() -> authService.refreshToken("old-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("reuse detected");

            // Verify all tokens for this user are revoked
            verify(refreshTokenRepository).revokeAllByUserId(userId);
        }
    }
}
```

---

## 6. Integration Tests with SpringBootTest

**File:** `backend/identity-service/src/test/java/com/payflow/identity/controller/AuthControllerIntegrationTest.java`

```java
package com.payflow.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.identity.dto.LoginRequest;
import com.payflow.identity.dto.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("payflow_identity_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @Order(1)
    @DisplayName("POST /register — should create account and return tokens")
    void register_Success() throws Exception {
        RegisterRequest request = new RegisterRequest(
            "integration@test.com", "P@ssw0rd!123", "Test User", "MERCHANT"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.userId").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    @Order(2)
    @DisplayName("POST /register — should return 409 for duplicate email")
    void register_DuplicateEmail() throws Exception {
        RegisterRequest request = new RegisterRequest(
            "integration@test.com", "P@ssw0rd!123", "Test User", "MERCHANT"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    @Order(3)
    @DisplayName("POST /login — should authenticate and return tokens")
    void login_Success() throws Exception {
        LoginRequest request = new LoginRequest("integration@test.com", "P@ssw0rd!123");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    @Order(4)
    @DisplayName("POST /login — should return 401 for wrong password")
    void login_WrongPassword() throws Exception {
        LoginRequest request = new LoginRequest("integration@test.com", "WrongPass1!");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @Order(5)
    @DisplayName("POST /register — should return 400 for invalid input")
    void register_ValidationError() throws Exception {
        RegisterRequest request = new RegisterRequest(
            "not-an-email", "short", "", "INVALID_ROLE"
        );

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.email").exists())
            .andExpect(jsonPath("$.fieldErrors.password").exists());
    }
}
```

---

## 7. curl Examples

### Register a New User

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@payflow.com",
    "password": "S3cur3P@ss!",
    "fullName": "Rajesh Kumar",
    "role": "MERCHANT"
  }'
```

**Response (201 Created):**
```json
{
  "accessToken": "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiI1YTZiN...",
  "refreshToken": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "userId": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Login

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@payflow.com",
    "password": "S3cur3P@ss!"
  }'
```

**Response (200 OK):**
```json
{
  "accessToken": "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiI1YTZiN...",
  "refreshToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "userId": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

### Refresh Token

```bash
curl -X POST http://localhost:8080/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  }'
```

### Get Profile

```bash
curl -X GET http://localhost:8080/api/v1/auth/profile \
  -H "Authorization: Bearer eyJhbGciOiJIUzM4NCJ9..." \
  -H "X-User-Id: 5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d"
```

**Response (200 OK):**
```json
{
  "id": "5a6b7c8d-9e0f-1a2b-3c4d-5e6f7a8b9c0d",
  "email": "merchant@payflow.com",
  "fullName": "Rajesh Kumar",
  "role": "MERCHANT",
  "createdAt": "2024-01-15T10:30:00"
}
```

---

## 8. Error Response Format

All errors follow a consistent JSON structure:

```
┌─────────────────────────────────────────────────────────────────┐
│                   ERROR RESPONSE MAPPING                         │
├──────────────────────────┬──────────┬───────────────────────────┤
│ Exception                │ HTTP     │ Error Code                │
├──────────────────────────┼──────────┼───────────────────────────┤
│ DuplicateEmailException  │ 409      │ DUPLICATE_EMAIL           │
│ InvalidCredentialsExc.   │ 401      │ INVALID_CREDENTIALS       │
│ InvalidTokenException    │ 401      │ INVALID_TOKEN             │
│ MethodArgumentNotValid   │ 400      │ VALIDATION_FAILED         │
│ Exception (catch-all)    │ 500      │ INTERNAL_ERROR            │
├──────────────────────────┼──────────┼───────────────────────────┤
│                                                                 │
│ Standard Error Body:                                            │
│ {                                                               │
│   "status": 401,                                                │
│   "code": "INVALID_CREDENTIALS",                                │
│   "message": "Invalid email or password",                       │
│   "timestamp": "2024-01-15T10:30:00"                           │
│ }                                                               │
│                                                                 │
│ Validation Error Body (400):                                    │
│ {                                                               │
│   "status": 400,                                                │
│   "code": "VALIDATION_FAILED",                                  │
│   "message": "Request validation failed",                       │
│   "fieldErrors": {                                              │
│     "email": "Must be a valid email address",                   │
│     "password": "Password must be between 8 and 64 characters"  │
│   },                                                            │
│   "timestamp": "2024-01-15T10:30:00"                           │
│ }                                                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | DTOs with validation | `@Valid` + Jakarta annotations reject bad input before service layer |
| 2 | Controller design | Thin controllers delegate to services; return ResponseEntity |
| 3 | @RestControllerAdvice | Centralized exception handling across all controllers |
| 4 | Mockito unit tests | `@Mock` dependencies, verify behavior not implementation |
| 5 | Testcontainers | Real PostgreSQL in tests — no H2 compatibility issues |
| 6 | Integration tests | `@SpringBootTest` + `MockMvc` for full HTTP lifecycle |
| 7 | Error consistency | Every error returns same structure: status, code, message |
| 8 | Security in errors | Never reveal whether email exists (same error for all login failures) |

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
