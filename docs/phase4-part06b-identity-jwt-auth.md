# 🏗️ Phase 4 Part 6b: Identity Service — JWT & Authentication

> **"A token is a promise — signed, sealed, and verifiable without asking twice."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Previous** | [phase4-part06a-identity-entities.md](./phase4-part06a-identity-entities.md) |
| **Next** | [phase4-part06c-identity-controller-tests.md](./phase4-part06c-identity-controller-tests.md) |

---

## 📖 Table of Contents

1. [Overview](#1-overview)
2. [JWT Token Structure](#2-jwt-token-structure)
3. [JwtService Implementation](#3-jwtservice-implementation)
4. [AuthService Implementation](#4-authservice-implementation)
5. [Token Rotation Security](#5-token-rotation-security)
6. [SecurityConfig](#6-securityconfig)
7. [Spring Security Filter Chain](#7-spring-security-filter-chain)
8. [Configuration Properties](#8-configuration-properties)
9. [Token Lifecycle Flow](#9-token-lifecycle-flow)
10. [What You Learned](#10-what-you-learned)

---

## 1. Overview

The Identity Service uses a dual-token strategy for authentication:

| Token Type | Lifetime | Storage | Purpose |
|------------|----------|---------|---------|
| Access Token (JWT) | 15 minutes | Client memory | API authorization |
| Refresh Token (opaque) | 7 days | HttpOnly cookie / DB | Obtain new access tokens |

**Architecture Flow:**

```
┌──────────┐     ┌─────────────────┐     ┌──────────────────┐
│  Client  │────▶│  API Gateway    │────▶│ Identity Service │
│          │◀────│  (validates JWT)│◀────│ (issues tokens)  │
└──────────┘     └─────────────────┘     └──────────────────┘
     │                                          │
     │  1. POST /auth/login                     │
     │  2. Receives: accessToken + refreshToken │
     │  3. Uses accessToken for API calls       │
     │  4. POST /auth/refresh when expired      │
     └──────────────────────────────────────────┘
```

**Dependencies (JJWT 0.12.5):**

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.5</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.5</version>
    <scope>runtime</scope>
</dependency>
```

---

## 2. JWT Token Structure

A JWT consists of three Base64URL-encoded parts separated by dots:

```
┌─────────────────────────────────────────────────────────────────┐
│                    JWT TOKEN STRUCTURE                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOi...  .  SflKxwRJSMeKKF2QT4... │
│  ├─── HEADER ───┤  ├─── PAYLOAD ───┤    ├──── SIGNATURE ────┤  │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  HEADER (Algorithm + Type):                                     │
│  {                                                              │
│    "alg": "HS384",                                              │
│    "typ": "JWT"                                                 │
│  }                                                              │
│                                                                 │
│  PAYLOAD (Claims):                                              │
│  {                                                              │
│    "sub": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",             │
│    "email": "merchant@example.com",                             │
│    "role": "MERCHANT",                                          │
│    "iat": 1700000000,                                           │
│    "exp": 1700000900                                            │
│  }                                                              │
│                                                                 │
│  SIGNATURE:                                                     │
│  HMACSHA384(                                                    │
│    base64UrlEncode(header) + "." + base64UrlEncode(payload),    │
│    secret_key                                                   │
│  )                                                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**PayFlow Custom Claims:**

| Claim | Type | Description |
|-------|------|-------------|
| `sub` | UUID string | User ID (subject) |
| `email` | String | User's email address |
| `role` | String | User role (USER, MERCHANT, ADMIN) |
| `iat` | Long (epoch seconds) | Issued at timestamp |
| `exp` | Long (epoch seconds) | Expiration timestamp |

---

## 3. JwtService Implementation

**File:** `backend/identity-service/src/main/java/com/payflow/identity/service/JwtService.java`

```java
package com.payflow.identity.service;

import com.payflow.identity.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Service for generating and validating JWT access tokens.
 * 
 * Uses HMAC-SHA384 for signing — provides 192-bit security strength
 * which is more than sufficient for authentication tokens.
 * 
 * Key size requirement: minimum 48 bytes (384 bits) for HS384.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;

    public JwtService(
            @Value("${payflow.jwt.secret}") String jwtSecret,
            @Value("${payflow.jwt.access-token-expiration-ms:900000}") long accessTokenExpirationMs
    ) {
        // JJWT 0.12.5 requires key length >= algorithm requirement
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    /**
     * Generates an access token for the given user.
     * 
     * Token contains: userId (sub), email, role, issued-at, expiry
     * Lifetime: 15 minutes (configurable via properties)
     */
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(accessTokenExpirationMs);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS384)
                .compact();
    }

    /**
     * Extracts all claims from a valid JWT token.
     * 
     * @throws ExpiredJwtException if token has expired
     * @throws io.jsonwebtoken.security.SignatureException if signature is invalid
     * @throws io.jsonwebtoken.MalformedJwtException if token format is invalid
     */
    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Validates a token by checking signature and expiration.
     * Returns true only if the token is well-formed, properly signed,
     * and not expired.
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return claims.getExpiration().after(Date.from(Instant.now()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts the user ID (subject) from a token.
     */
    public UUID extractUserId(String token) {
        return UUID.fromString(extractClaims(token).getSubject());
    }

    /**
     * Extracts the role from a token.
     */
    public String extractRole(String token) {
        return extractClaims(token).get("role", String.class);
    }
}
```

**Why HS384 over RS256?**

| Algorithm | Type | Key | Performance | Use Case |
|-----------|------|-----|-------------|----------|
| HS384 | Symmetric | Shared secret | Fast | Single issuer (our case) |
| RS256 | Asymmetric | Public/Private | Slower | Multiple validators, no shared secret |

Since only the Identity Service issues tokens and the API Gateway validates them
(using the same shared secret), HMAC is simpler and faster.

---

## 4. AuthService Implementation

**File:** `backend/identity-service/src/main/java/com/payflow/identity/service/AuthService.java`

```java
package com.payflow.identity.service;

import com.payflow.identity.dto.AuthResponse;
import com.payflow.identity.dto.LoginRequest;
import com.payflow.identity.dto.RegisterRequest;
import com.payflow.identity.dto.UserProfileResponse;
import com.payflow.identity.exception.DuplicateEmailException;
import com.payflow.identity.exception.InvalidCredentialsException;
import com.payflow.identity.exception.InvalidTokenException;
import com.payflow.identity.model.RefreshToken;
import com.payflow.identity.model.Role;
import com.payflow.identity.model.User;
import com.payflow.identity.repository.RefreshTokenRepository;
import com.payflow.identity.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core authentication service handling registration, login, and token refresh.
 * 
 * Security features:
 * - BCrypt password hashing (cost factor 12)
 * - Token rotation on refresh (old token revoked, new token issued)
 * - Reuse detection: if a revoked token is used, all user tokens are invalidated
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final long refreshTokenExpirationDays;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            @org.springframework.beans.factory.annotation.Value(
                "${payflow.jwt.refresh-token-expiration-days:7}"
            ) long refreshTokenExpirationDays
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
    }

    /**
     * Registers a new user account.
     * 
     * Steps:
     * 1. Check email uniqueness
     * 2. Hash password with BCrypt
     * 3. Save user
     * 4. Generate tokens
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check for duplicate email
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException(request.getEmail());
        }

        // Create user with hashed password
        User user = new User(
            request.getEmail(),
            passwordEncoder.encode(request.getPassword()),
            request.getFullName(),
            Role.valueOf(request.getRole().toUpperCase())
        );

        user = userRepository.save(user);

        // Generate token pair
        String accessToken = jwtService.generateAccessToken(user);
        RefreshToken refreshToken = createRefreshToken(user.getId());

        return new AuthResponse(accessToken, refreshToken.getToken(), user.getId());
    }

    /**
     * Authenticates a user with email and password.
     * 
     * Steps:
     * 1. Find user by email
     * 2. Verify password against BCrypt hash
     * 3. Check account is active
     * 4. Generate token pair
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new InvalidCredentialsException());

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        if (!user.isActive()) {
            throw new InvalidCredentialsException("Account is deactivated");
        }

        String accessToken = jwtService.generateAccessToken(user);
        RefreshToken refreshToken = createRefreshToken(user.getId());

        return new AuthResponse(accessToken, refreshToken.getToken(), user.getId());
    }

    /**
     * Refreshes an access token using a valid refresh token.
     * Implements TOKEN ROTATION: old refresh token is revoked, new one issued.
     * 
     * Security: If a revoked token is reused (potential theft), ALL tokens
     * for that user are invalidated immediately.
     */
    @Transactional
    public AuthResponse refreshToken(String refreshTokenValue) {
        RefreshToken storedToken = refreshTokenRepository.findByToken(refreshTokenValue)
            .orElseThrow(() -> new InvalidTokenException("Refresh token not found"));

        // SECURITY: Reuse detection
        if (storedToken.isRevoked()) {
            // Token was already used — possible theft! Revoke ALL tokens for this user
            refreshTokenRepository.revokeAllByUserId(storedToken.getUserId());
            throw new InvalidTokenException("Token reuse detected — all sessions invalidated");
        }

        // Check expiration
        if (!storedToken.isValid()) {
            throw new InvalidTokenException("Refresh token has expired");
        }

        // Rotate: revoke old token
        storedToken.revoke();
        refreshTokenRepository.save(storedToken);

        // Issue new token pair
        User user = userRepository.findById(storedToken.getUserId())
            .orElseThrow(() -> new InvalidTokenException("User not found"));

        String newAccessToken = jwtService.generateAccessToken(user);
        RefreshToken newRefreshToken = createRefreshToken(user.getId());

        return new AuthResponse(newAccessToken, newRefreshToken.getToken(), user.getId());
    }

    /**
     * Returns the profile of the currently authenticated user.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new InvalidTokenException("User not found"));

        return new UserProfileResponse(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getRole().name(),
            user.getCreatedAt()
        );
    }

    // ─── Private Helpers ─────────────────────────────────────────

    private RefreshToken createRefreshToken(UUID userId) {
        String tokenValue = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(refreshTokenExpirationDays);

        RefreshToken refreshToken = new RefreshToken(tokenValue, userId, expiresAt);
        return refreshTokenRepository.save(refreshToken);
    }
}
```

---

## 5. Token Rotation Security

Token rotation prevents refresh token theft from granting permanent access:

```
┌──────────────────────────────────────────────────────────────────────┐
│                    TOKEN ROTATION FLOW                                │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Normal Flow:                                                        │
│  ┌────────┐                     ┌─────────────────┐                  │
│  │ Client │─── refresh(RT_1) ──▶│ Identity Service│                  │
│  │        │◀── AT_2 + RT_2 ─────│ (revokes RT_1)  │                  │
│  └────────┘                     └─────────────────┘                  │
│                                                                      │
│  Theft Detection:                                                    │
│  ┌────────┐                     ┌─────────────────┐                  │
│  │Attacker│─── refresh(RT_1) ──▶│ Identity Service│                  │
│  │        │◀── ERROR ───────────│ RT_1 is revoked!│                  │
│  └────────┘                     │ REVOKE ALL user │                  │
│                                 │ tokens (RT_2...)│                  │
│                                 └─────────────────┘                  │
│                                                                      │
│  Timeline:                                                           │
│  ─────────────────────────────────────────────────────────────────   │
│  T1: User logs in         → gets AT_1, RT_1                         │
│  T2: Attacker steals RT_1 → (undetected)                            │
│  T3: User refreshes RT_1  → gets AT_2, RT_2 (RT_1 revoked)         │
│  T4: Attacker uses RT_1   → DETECTED! All tokens revoked            │
│  T5: User must re-login   → fresh token chain                       │
│  ─────────────────────────────────────────────────────────────────   │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

**Key Security Properties:**

| Property | Implementation |
|----------|---------------|
| Single-use tokens | Each refresh token is revoked after use |
| Reuse detection | Using a revoked token invalidates ALL sessions |
| Short access lifetime | 15 min limits damage window |
| Opaque refresh tokens | UUID-based, not JWT (can't be decoded client-side) |

---

## 6. SecurityConfig

**File:** `backend/identity-service/src/main/java/com/payflow/identity/config/SecurityConfig.java`

```java
package com.payflow.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the Identity Service.
 * 
 * Design: The Identity Service itself does NOT validate JWTs on incoming requests.
 * It only ISSUES tokens. JWT validation happens at the API Gateway level.
 * 
 * This service permits all requests because it's accessed via the gateway
 * which handles authentication. Internal endpoints are not exposed externally.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * BCrypt password encoder with cost factor 12.
     * 
     * Cost factor analysis:
     * - 10 (default): ~100ms per hash
     * - 12 (our choice): ~400ms per hash — good balance
     * - 14: ~1600ms per hash — too slow for UX
     * 
     * At cost 12, an attacker can only attempt ~2.5 hashes/second/core,
     * making brute force impractical.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Security filter chain — permits all requests to this service.
     * Authentication is handled at the API Gateway layer.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())  // Stateless API, no CSRF needed
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> 
                auth.anyRequest().permitAll()  // Gateway handles auth
            )
            .build();
    }
}
```

**Why `permitAll()` on the Identity Service?**

```
┌──────────────────────────────────────────────────────────────┐
│              REQUEST FLOW — WHO VALIDATES WHAT?               │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Client ──▶ API Gateway ──▶ Identity Service                 │
│             │                │                               │
│             │ Validates:     │ Does NOT validate:            │
│             │ • JWT sig      │ • JWT (it issues them)        │
│             │ • Expiry       │                               │
│             │ • Role-based   │ Permits all:                  │
│             │   routing      │ • /auth/register              │
│             │                │ • /auth/login                 │
│             │                │ • /auth/refresh               │
│             │                │ • /auth/profile (userId from  │
│             │                │   gateway header)             │
│             │                │                               │
└──────────────────────────────────────────────────────────────┘
```

---

## 7. Spring Security Filter Chain

The filter chain order for the overall PayFlow system:

```
┌─────────────────────────────────────────────────────────────────┐
│              SPRING SECURITY FILTER CHAIN                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Incoming Request                                               │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────┐                                │
│  │ 1. CorsFilter               │  Allow cross-origin requests  │
│  └──────────────┬──────────────┘                                │
│                 ▼                                                │
│  ┌─────────────────────────────┐                                │
│  │ 2. SecurityContextFilter    │  Initialize security context   │
│  └──────────────┬──────────────┘                                │
│                 ▼                                                │
│  ┌─────────────────────────────┐                                │
│  │ 3. CsrfFilter (DISABLED)   │  Stateless API — no CSRF      │
│  └──────────────┬──────────────┘                                │
│                 ▼                                                │
│  ┌─────────────────────────────┐                                │
│  │ 4. AuthorizationFilter      │  permitAll() — always passes  │
│  └──────────────┬──────────────┘                                │
│                 ▼                                                │
│  ┌─────────────────────────────┐                                │
│  │ 5. Controller Dispatch      │  Route to AuthController       │
│  └─────────────────────────────┘                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 8. Configuration Properties

**File:** `backend/identity-service/src/main/resources/application.yml`

```yaml
payflow:
  jwt:
    # HMAC-SHA384 requires minimum 48 bytes (384 bits)
    # Generate with: openssl rand -base64 64
    secret: ${JWT_SECRET:your-super-secret-key-that-is-at-least-48-bytes-long-for-hs384-algorithm}
    
    # Access token lifetime: 15 minutes (900,000 ms)
    access-token-expiration-ms: ${JWT_ACCESS_EXPIRATION:900000}
    
    # Refresh token lifetime: 7 days
    refresh-token-expiration-days: ${JWT_REFRESH_EXPIRATION_DAYS:7}

spring:
  security:
    # Disable default Spring Security user generation
    user:
      name: disabled
      password: disabled
```

**Security Configuration Properties Table:**

| Property | Default | Production | Description |
|----------|---------|------------|-------------|
| `jwt.secret` | Dev placeholder | 64-byte random | HMAC signing key |
| `access-token-expiration-ms` | 900000 (15 min) | 900000 | Short-lived for security |
| `refresh-token-expiration-days` | 7 | 7 | Balance UX vs security |

---

## 9. Token Lifecycle Flow

```
┌───────────────────────────────────────────────────────────────────────────┐
│                     COMPLETE TOKEN LIFECYCLE                               │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌─────────┐          ┌──────────────┐          ┌──────────────┐         │
│  │  Client │          │   Gateway    │          │  Identity Svc│         │
│  └────┬────┘          └──────┬───────┘          └──────┬───────┘         │
│       │                      │                         │                  │
│       │  1. POST /auth/login │                         │                  │
│       │─────────────────────▶│                         │                  │
│       │                      │  Forward to identity    │                  │
│       │                      │────────────────────────▶│                  │
│       │                      │                         │                  │
│       │                      │                         │ Validate creds   │
│       │                      │                         │ Generate AT+RT   │
│       │                      │                         │ Store RT in DB   │
│       │                      │                         │                  │
│       │                      │  {accessToken, refresh} │                  │
│       │                      │◀────────────────────────│                  │
│       │  {accessToken, refresh}                        │                  │
│       │◀─────────────────────│                         │                  │
│       │                      │                         │                  │
│       │  2. GET /api/orders  │                         │                  │
│       │  Authorization: Bearer AT                      │                  │
│       │─────────────────────▶│                         │                  │
│       │                      │ Validate JWT signature  │                  │
│       │                      │ Check expiry            │                  │
│       │                      │ Extract userId, role    │                  │
│       │                      │ Forward with headers    │                  │
│       │                      │────────────────────────▶│ (to payment svc) │
│       │                      │                         │                  │
│       │  3. AT expired (15min)                         │                  │
│       │  POST /auth/refresh  │                         │                  │
│       │─────────────────────▶│────────────────────────▶│                  │
│       │                      │                         │ Validate RT      │
│       │                      │                         │ Revoke old RT    │
│       │                      │                         │ Issue new AT+RT  │
│       │  {newAccessToken, newRefresh}                  │                  │
│       │◀─────────────────────│◀────────────────────────│                  │
│       │                      │                         │                  │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | JWT structure | Three parts: header (algorithm), payload (claims), signature |
| 2 | JJWT 0.12.5 API | `Jwts.builder()` to create, `Jwts.parser().verifyWith()` to validate |
| 3 | HS384 vs RS256 | Symmetric (HS) for single issuer, Asymmetric (RS) for distributed |
| 4 | Token rotation | Revoke old refresh token, issue new one — detects theft |
| 5 | Reuse detection | Using a revoked token = compromise; invalidate all sessions |
| 6 | BCrypt cost factor | Cost 12 = ~400ms/hash, impractical for brute force |
| 7 | SecurityConfig | Identity Service permits all — Gateway handles auth |
| 8 | Dual-token strategy | Short-lived AT (15min) + long-lived RT (7 days) |

---

## 📚 Document Index

| Document | Title |
|----------|-------|
| [Phase 4 Part 6a](./phase4-part06a-identity-entities.md) | Identity Service — Entities & Migrations |
| **Phase 4 Part 6b** | **Identity Service — JWT & Authentication** (You are here) |
| [Phase 4 Part 6c](./phase4-part06c-identity-controller-tests.md) | Identity Service — Controller & Tests |
| [Phase 4 Part 7a](./phase4-part07a-merchant-entities.md) | Merchant Service — Entities |
| [Phase 4 Part 7b](./phase4-part07b-merchant-services.md) | Merchant Service — Services & Controller |

---

## 🚀 Next Steps

In **[Phase 4 Part 6c](./phase4-part06c-identity-controller-tests.md)**, we will implement:

1. `AuthController` — REST endpoints for register, login, refresh, profile
2. `GlobalExceptionHandler` — Centralized error handling with proper HTTP status codes
3. Request/Response DTOs with validation annotations
4. Unit tests with Mockito and integration tests with `@SpringBootTest`
5. curl examples for manual testing
