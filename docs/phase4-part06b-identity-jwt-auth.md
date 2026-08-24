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

1. [Overview & Purpose](#1-overview--purpose)
2. [What Is JWT and Why Do We Need It?](#2-what-is-jwt-and-why-do-we-need-it)
3. [JWT Token Structure Explained](#3-jwt-token-structure-explained)
4. [Step-by-Step: JwtService](#4-step-by-step-jwtservice)
5. [Step-by-Step: SecurityConfig](#5-step-by-step-securityconfig)
6. [Step-by-Step: AuthService](#6-step-by-step-authservice)
7. [Token Rotation — How We Detect Stolen Tokens](#7-token-rotation--how-we-detect-stolen-tokens)
8. [The Complete Token Lifecycle](#8-the-complete-token-lifecycle)
9. [Configuration Properties Explained](#9-configuration-properties-explained)
10. [How to Verify Your Work](#10-how-to-verify-your-work)
11. [What You Learned](#11-what-you-learned)

---

## 1. Overview & Purpose

In **Part 6a**, we built the data layer (entities, migrations, repositories). Now we build the **brain** of the Identity Service — the services that:

1. **Hash passwords** securely (so they can't be recovered if the DB is stolen)
2. **Generate JWT tokens** (so other services can verify identity without calling us)
3. **Handle registration** (create new accounts)
4. **Handle login** (verify credentials, issue tokens)
5. **Handle token refresh** (issue new tokens without re-entering password)

### Dual-Token Strategy

PayFlow uses TWO tokens that work together:

| Token | What | Lifetime | Stored Where | Purpose |
|-------|------|----------|-------------|---------|
| **Access Token** (JWT) | Encoded JSON with user info + signature | 15 minutes | Client memory (JavaScript variable) | Authorize API calls |
| **Refresh Token** (opaque) | Random UUID string | 7 days | Client storage + our DB | Get new access tokens |

### Why Two Tokens?

```
WITH ONLY access tokens (long-lived):
  ❌ If stolen, attacker has access for months
  ❌ Can't revoke (JWT is stateless)

WITH ONLY access tokens (short-lived):
  ❌ User must re-login every 15 minutes (terrible UX)

WITH dual tokens:
  ✅ Access token is short-lived (15 min) — limited damage if stolen
  ✅ Refresh token enables long sessions (7 days) — good UX
  ✅ Refresh token is in our DB — we CAN revoke it
  ✅ Token rotation detects theft
```

---

## 2. What Is JWT and Why Do We Need It?

### The Problem Without JWT

```
Traditional session-based auth:

Client → API Gateway → "Who is user abc123?" → Identity Service → DB lookup
                                                                    ↓
Client ← API Gateway ← "It's John, role=MERCHANT" ←────────────────┘

Every single API call requires a round-trip to the Identity Service.
10,000 requests/second × DB lookup each = BOTTLENECK
```

### The Solution With JWT

```
JWT-based auth:

Login:
Client → Identity Service → generates signed JWT → Client stores it

Every subsequent request:
Client → API Gateway → validates JWT signature locally (NO DB call!)
                     → extracts userId, role from token
                     → forwards to downstream service

No round-trip to Identity Service needed!
```

### Real-World Analogy

**Session-based** = Calling the HR department every time someone enters a room to ask "Is this person allowed here?"

**JWT-based** = Giving people a signed ID badge. Security guards can verify the badge is authentic (check the signature) without calling HR.

---

## 3. JWT Token Structure Explained

A JWT has three parts separated by dots: `HEADER.PAYLOAD.SIGNATURE`

```
┌─────────────────────────────────────────────────────────────────────┐
│                      JWT TOKEN ANATOMY                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  eyJhbGciOiJIUz...  .  eyJzdWIiOiJ1c2V...  .  SflKxwRJSMeKKF...   │
│  ├──── HEADER ────┤    ├──── PAYLOAD ────┤    ├── SIGNATURE ──┤    │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ HEADER (base64-encoded):                                     │   │
│  │ {                                                            │   │
│  │   "alg": "HS256",    ← algorithm used for signature         │   │
│  │   "typ": "JWT"       ← token type                           │   │
│  │ }                                                            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ PAYLOAD (base64-encoded) — our custom claims:                │   │
│  │ {                                                            │   │
│  │   "sub": "user-abc123-def456",    ← subject (user ID)       │   │
│  │   "email": "tejaswi@example.com", ← custom claim            │   │
│  │   "role": "MERCHANT",             ← custom claim            │   │
│  │   "iat": 1700000000,             ← issued at (epoch secs)   │   │
│  │   "exp": 1700000900              ← expires at (15 min later)│   │
│  │ }                                                            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ SIGNATURE:                                                   │   │
│  │ HMAC-SHA256(                                                 │   │
│  │   base64(header) + "." + base64(payload),                    │   │
│  │   secret_key     ← only WE have this key                    │   │
│  │ )                                                            │   │
│  │                                                              │   │
│  │ PURPOSE: Proves the token wasn't tampered with.              │   │
│  │ If anyone changes the payload, the signature won't match.    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Important: JWTs Are NOT Encrypted

The payload is only **base64-encoded** (not encrypted). Anyone can decode it:
```
echo "eyJzdWIiOiJ1c2VyLTEyMyJ9" | base64 -d
→ {"sub":"user-123"}
```

The **signature** only guarantees the token wasn't modified — it doesn't hide the contents. That's why we never put sensitive data (passwords, credit cards) in a JWT.

---

## 4. Step-by-Step: JwtService

**File:** `backend/identity-service/src/main/java/com/payflow/identity/service/JwtService.java`

### What It Does
- **Generates** JWT access tokens containing user identity (userId, email, role)
- **Validates** tokens (checks signature + expiry)
- **Extracts** information from tokens (userId, claims)

### Why Each Method Exists

| Method | Used When | By Whom |
|--------|-----------|---------|
| `generateAccessToken()` | User logs in or refreshes | AuthService |
| `extractClaims()` | Need to read token contents | API Gateway filter |
| `extractUserId()` | Need just the user ID | API Gateway header injection |
| `isTokenValid()` | Verifying a token is still good | API Gateway validation |
| `getAccessTokenExpirationSeconds()` | Tell client when token expires | AuthService → response |

### Implementation (Matching Actual Source Code)

```java
package com.payflow.identity.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * Service for generating and validating JWT tokens.
 * 
 * WHAT: Creates signed tokens that encode user identity.
 * WHY: Enables stateless authentication — the API Gateway can verify
 *      a user's identity without calling the Identity Service every time.
 * HOW: Uses HMAC-SHA256 to sign tokens with a shared secret key.
 */
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String jwtSecret;
    // The signing key — must be at least 256 bits (32 bytes) for HMAC-SHA256.
    // Read from application.yml. In production, this comes from environment variables.

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;
    // Token lifetime in MILLISECONDS (900000 = 15 minutes)

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpiration;
    // Not used for JWT generation (refresh tokens are opaque UUIDs),
    // but available for reference.

    /**
     * Generates a JWT access token for the given user.
     * 
     * WHAT GOES INTO THE TOKEN:
     * - sub (subject): The user's ID — used to identify who made the request
     * - email: Included so downstream services know the user's email without a DB call
     * - role: Included so the API Gateway can do role-based routing
     * - iat (issued at): When this token was created
     * - exp (expiration): When this token becomes invalid
     * 
     * WHY THESE CLAIMS?
     * The API Gateway needs userId and role to make routing decisions.
     * Including them in the token avoids additional service calls.
     * 
     * @param userId  The user's UUID (becomes the "sub" claim)
     * @param email   The user's email (custom claim)
     * @param role    The user's role: USER, MERCHANT, or ADMIN (custom claim)
     * @return        A signed JWT string like "eyJhbGci..."
     */
    public String generateAccessToken(String userId, String email, String role) {
        return Jwts.builder()
                .subject(userId)              // Standard claim: who this token is for
                .claims(Map.of(               // Custom claims: extra data we need
                        "email", email,
                        "role", role
                ))
                .issuedAt(new Date())         // Token created NOW
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                // Token expires 15 minutes from NOW
                .signWith(getSigningKey())    // Sign with our secret key
                .compact();                   // Serialize to "xxxxx.yyyyy.zzzzz" string
    }

    /**
     * Extracts ALL claims (data) from a JWT token.
     * 
     * HOW IT WORKS:
     * 1. Takes the token string
     * 2. Verifies the signature matches (using our secret key)
     * 3. Checks that the token hasn't expired
     * 4. Returns the payload (claims) if everything is valid
     * 
     * THROWS: Exception if:
     * - Signature doesn't match (token was tampered with)
     * - Token has expired
     * - Token format is invalid
     */
    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())   // "I expect this was signed with MY key"
                .build()
                .parseSignedClaims(token)      // Verify signature + decode
                .getPayload();                 // Return the claims map
    }

    /**
     * Extracts just the user ID from a token.
     * Convenience method — equivalent to extractClaims(token).getSubject()
     */
    public String extractUserId(String token) {
        return extractClaims(token).getSubject();
    }

    /**
     * Checks if a token is valid (not expired, not tampered).
     * 
     * Returns true ONLY if:
     * 1. The signature is valid (proves we issued it)
     * 2. The expiration date is in the future (not expired)
     * 
     * Returns false for:
     * - Expired tokens
     * - Tokens signed with a different key
     * - Malformed token strings
     * - Any other parsing error
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;  // Any error = invalid token (don't leak details)
        }
    }

    /**
     * Returns token lifetime in SECONDS (for the API response).
     * Client receives: { "expiresIn": 900 } → knows to refresh in 900 seconds
     */
    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpiration / 1000;
    }

    /**
     * Converts the string secret into a cryptographic SecretKey object.
     * 
     * WHY: JJWT requires a SecretKey object, not a raw string.
     * The secret must be at least 256 bits (32 bytes) for HMAC-SHA256.
     * Our dev key in application.yml is 64+ bytes — well above the minimum.
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }
}
```

### JJWT Library Explained

We use [JJWT (Java JWT)](https://github.com/jwtk/jjwt) version 0.12.x. It provides:

| Class | Purpose |
|-------|---------|
| `Jwts.builder()` | Creates new tokens (fluent API) |
| `Jwts.parser()` | Validates and reads existing tokens |
| `Keys.hmacShaKeyFor()` | Creates a signing key from a byte array |
| `Claims` | A map of token data (subject, email, role, etc.) |

### Why HMAC-SHA256 (Symmetric) vs RSA (Asymmetric)?

| Algorithm | Type | Key Setup | Performance | Our Use Case |
|-----------|------|-----------|-------------|-------------|
| **HS256** (HMAC) | Symmetric | One shared secret | **Fast** | ✅ Single issuer + single validator |
| RS256 (RSA) | Asymmetric | Public + Private key pair | Slower | Multiple validators, zero-trust |

**We chose HS256 because:**
- Only one service issues tokens (Identity Service)
- Only one service validates them (API Gateway)
- They can safely share a secret
- It's faster (important at high request volumes)

---

## 5. Step-by-Step: SecurityConfig

**File:** `backend/identity-service/src/main/java/com/payflow/identity/config/SecurityConfig.java`

### What It Does
1. Configures the **password encoder** (BCrypt with cost factor 12)
2. Configures the **security filter chain** (which endpoints need authentication)

### Why This File Exists
Spring Security locks down EVERYTHING by default. Without this config, even `/v1/auth/login` would require authentication — a chicken-and-egg problem (can't log in because you're not logged in).

### Implementation (Matching Actual Source Code)

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
 * Spring Security configuration for identity-service.
 * 
 * KEY DESIGN DECISION:
 * The Identity Service does NOT validate JWTs on incoming requests.
 * It only ISSUES tokens. JWT validation happens at the API Gateway.
 * 
 * Therefore, /v1/auth/** endpoints are open — they ARE the login endpoints.
 */
@Configuration            // Spring: "This class provides bean definitions"
@EnableWebSecurity        // Activates Spring Security's web features
public class SecurityConfig {

    /**
     * PASSWORD ENCODER BEAN
     * 
     * WHAT: BCryptPasswordEncoder with cost factor 12.
     * WHY BCrypt: It's intentionally SLOW, making brute-force attacks impractical.
     * WHY cost 12: Balance between security and user experience.
     * 
     * Cost factor timing:
     * ┌──────────┬──────────────┬──────────────────────────────────┐
     * │ Cost     │ Time/hash    │ Notes                            │
     * ├──────────┼──────────────┼──────────────────────────────────┤
     * │ 10       │ ~100ms       │ Spring default, a bit fast       │
     * │ 12       │ ~400ms       │ Our choice — good balance ✓      │
     * │ 14       │ ~1.6 sec     │ Too slow for login UX            │
     * └──────────┴──────────────┴──────────────────────────────────┘
     * 
     * At cost 12, an attacker brute-forcing passwords can only try
     * ~2.5 passwords per second per CPU core. Completely impractical
     * for complex passwords.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * SECURITY FILTER CHAIN
     * 
     * Defines which endpoints require authentication and which don't.
     * 
     * FLOW:
     * Request arrives → CSRF check (disabled) → Session check (stateless)
     *                → Authorization check → Controller
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                // WHY disable CSRF?
                // CSRF protection is for browser-based forms with cookies.
                // We're a stateless REST API using JWT in Authorization header.
                // No cookies = no CSRF vulnerability.

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // WHY stateless?
                // We use JWT tokens, not server-side sessions.
                // STATELESS tells Spring to NEVER create an HttpSession.
                // This saves memory and makes horizontal scaling trivial
                // (any server instance can handle any request).

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/auth/**").permitAll()
                        // /register, /login, /refresh — must be accessible without a token!
                        // (You can't require a token to GET a token)

                        .requestMatchers("/actuator/**").permitAll()
                        // Health checks for Docker/Kubernetes
                        // Docker HEALTHCHECK hits /actuator/health

                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // API documentation — accessible for developers

                        .anyRequest().authenticated()
                        // Everything else requires authentication
                        // (future admin endpoints, etc.)
                )
                .build();
    }
}
```

### Why Not `permitAll()` for Everything?

The documentation versions shows `anyRequest().permitAll()` because "the gateway handles auth." Our actual source code is more specific:

```
permitAll():  /v1/auth/**, /actuator/**, /swagger-ui/**
authenticated(): everything else
```

This is **defense in depth** — even if someone bypasses the gateway and hits the service directly, unexpected endpoints are still protected.

---

## 6. Step-by-Step: AuthService

**File:** `backend/identity-service/src/main/java/com/payflow/identity/service/AuthService.java`

### What It Does
This is the **core business logic** of the Identity Service. It orchestrates:
- Registration (create account, hash password, generate tokens)
- Login (verify credentials, generate tokens)
- Token refresh (validate refresh token, rotate, issue new pair)
- Profile retrieval (look up user by ID)

### Why Separate from Controller?
| Layer | Responsibility | Can Be Tested Without |
|-------|---------------|----------------------|
| **Controller** | HTTP handling (request/response, status codes) | Nothing — it's the entry point |
| **Service** | Business logic (validation, password hashing, token generation) | HTTP (test with plain Java) |
| **Repository** | Data access (SQL queries) | Business logic |

This separation means you can unit test the business logic without starting a web server.

### Implementation (Matching Actual Source Code)

```java
package com.payflow.identity.service;

import com.payflow.common.exception.DuplicateResourceException;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.common.exception.UnauthorizedException;
import com.payflow.identity.dto.*;
import com.payflow.identity.model.RefreshToken;
import com.payflow.identity.model.Role;
import com.payflow.identity.model.User;
import com.payflow.identity.repository.RefreshTokenRepository;
import com.payflow.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Core authentication business logic.
 * Handles registration, login, and token refresh flows.
 * 
 * SECURITY FEATURES:
 * 1. BCrypt password hashing (cost 12) — passwords can never be recovered
 * 2. Token rotation on refresh — old token revoked, new one issued
 * 3. Vague error messages — "Invalid email or password" (never "email not found")
 */
@Service
@RequiredArgsConstructor  // Lombok: generates constructor for all 'final' fields
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;  // BCrypt (from SecurityConfig)

    // ═══════════════════════════════════════════════════════════════════
    // REGISTRATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a new user account.
     * 
     * FLOW:
     * 1. Check if email already exists → 409 Conflict if yes
     * 2. Determine role (default to USER)
     * 3. Hash the password with BCrypt
     * 4. Save user to database
     * 5. Generate access token + refresh token
     * 6. Return both tokens + user profile
     * 
     * WHY @Transactional?
     * → If token generation fails AFTER user is saved, we don't want
     *   a user in the DB without tokens. @Transactional rolls back everything.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {

        // STEP 1: Check for duplicate email
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("User", "email", request.getEmail());
            // Results in HTTP 409 Conflict
            // Message: "User with email 'x@y.com' already exists"
        }

        // STEP 2: Determine role
        Role role = Role.USER;  // Default
        if (request.getRole() != null) {
            try {
                role = Role.valueOf(request.getRole().toUpperCase());
                // "merchant" → "MERCHANT" → Role.MERCHANT
            } catch (IllegalArgumentException e) {
                role = Role.USER;  // Invalid role string → default to USER
            }
        }

        // STEP 3: Create user with HASHED password
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                // passwordEncoder.encode("MyP@ssw0rd!")
                //   → "$2a$12$LJ3m4vGy7z..." (60-char BCrypt hash)
                // This is ONE-WAY. No one can recover the original password.
                .fullName(request.getFullName())
                .role(role)
                .active(true)
                .build();

        user = userRepository.save(user);
        // Hibernate generates: INSERT INTO users (id, email, password_hash, ...) VALUES (...)
        // The @GeneratedValue fills in the UUID id

        // STEP 4: Generate tokens and return response
        return generateAuthResponse(user);
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOGIN
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Authenticates a user with email + password.
     * 
     * FLOW:
     * 1. Find user by email → 401 if not found
     * 2. Compare password with stored hash → 401 if wrong
     * 3. Check account is active → 401 if disabled
     * 4. Generate tokens and return
     * 
     * SECURITY NOTE: We use the SAME error message "Invalid email or password"
     * for both "email not found" and "wrong password". This prevents attackers
     * from discovering which emails are registered in our system.
     */
    public AuthResponse login(LoginRequest request) {

        // STEP 1: Find user by email
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        // WHY same message for "not found"?
        // If we said "Email not found", an attacker could:
        //   - Try random emails
        //   - "Email not found" → try next
        //   - "Wrong password" → AHA! This email exists!
        // This is called USER ENUMERATION and it's a security risk.

        // STEP 2: Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
            // BCrypt.matches("plaintext", "$2a$12$storedHash") → true/false
            // It re-hashes the plaintext and compares to the stored hash
        }

        // STEP 3: Check account is active
        if (!user.isActive()) {
            throw new UnauthorizedException("Account is disabled");
        }

        // STEP 4: Generate tokens
        return generateAuthResponse(user);
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOKEN REFRESH (with rotation)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Issues new access + refresh tokens using an existing refresh token.
     * Implements TOKEN ROTATION for security.
     * 
     * FLOW:
     * 1. Find the refresh token in DB (must be non-revoked)
     * 2. Check if it's expired
     * 3. REVOKE the old refresh token (single-use enforcement)
     * 4. Look up the user
     * 5. Generate NEW access + refresh token pair
     * 
     * WHY TOKEN ROTATION?
     * See section 7 for detailed explanation.
     * TL;DR: If a refresh token is stolen, we can detect it because
     * the legitimate user will try to use the (now-revoked) token.
     */
    @Transactional
    public AuthResponse refreshToken(RefreshRequest request) {

        // STEP 1: Find token (must not be revoked)
        RefreshToken refreshToken = refreshTokenRepository
                .findByTokenAndRevokedFalse(request.getRefreshToken())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        // If token doesn't exist OR is already revoked → fail

        // STEP 2: Check expiry
        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("Refresh token expired");
            // After 7 days, even valid tokens expire. User must re-login.
        }

        // STEP 3: Revoke old token (TOKEN ROTATION)
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);
        // This token can NEVER be used again.
        // If someone tries to use it later → we know it was stolen.

        // STEP 4: Look up user
        User user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", refreshToken.getUserId()));

        // STEP 5: Generate new token pair
        return generateAuthResponse(user);
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROFILE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the profile of a user given their ID.
     * 
     * Called when: GET /v1/auth/profile with X-User-Id header
     * The X-User-Id comes from the API Gateway (which extracted it from the JWT).
     */
    public UserProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())   // Role.MERCHANT → "MERCHANT"
                .createdAt(user.getCreatedAt())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // PRIVATE HELPER
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Generates the complete auth response with tokens + user profile.
     * Used by register(), login(), and refreshToken() — all three need
     * the same output format.
     */
    private AuthResponse generateAuthResponse(User user) {
        // Generate JWT access token (15 min lifetime)
        String accessToken = jwtService.generateAccessToken(
                user.getId(), user.getEmail(), user.getRole().name());

        // Generate opaque refresh token (random UUID — NOT a JWT)
        String refreshTokenStr = UUID.randomUUID().toString();
        // WHY UUID not JWT?
        // → Refresh tokens are looked up in the DB anyway (for revocation checking)
        // → Making them JWTs adds complexity without benefit
        // → Opaque tokens reveal nothing to the client

        // Persist refresh token in DB
        RefreshToken refreshToken = RefreshToken.builder()
                .token(refreshTokenStr)
                .userId(user.getId())
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        // Build response object
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenStr)
                .expiresIn(jwtService.getAccessTokenExpirationSeconds())
                .user(UserProfileResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .role(user.getRole().name())
                        .createdAt(user.getCreatedAt())
                        .build())
                .build();
    }
}
```

### Key Design Decisions in AuthService

| Decision | Reasoning |
|----------|-----------|
| Same error for wrong email/password | Prevents user enumeration attacks |
| `@Transactional` on register/refresh | Ensures atomicity (all-or-nothing) |
| Refresh token as UUID (not JWT) | We need DB lookups anyway (for revocation); JWT adds no benefit |
| `passwordEncoder.encode()` not `new BCrypt()` | Spring manages the encoder bean; testable with mocks |
| `@RequiredArgsConstructor` | Constructor injection via Lombok; cleaner than field injection |

---

## 7. Token Rotation — How We Detect Stolen Tokens

This is the most important security concept in the token refresh flow.

### The Problem: Stolen Refresh Tokens

```
WITHOUT token rotation:
  T1: User logs in → gets RefreshToken_A
  T2: Attacker steals RefreshToken_A
  T3: User refreshes with RefreshToken_A → gets new AT (RefreshToken_A still valid!)
  T4: Attacker refreshes with RefreshToken_A → ALSO gets a new AT
      → Attacker has permanent access! No way to detect the theft.
```

### The Solution: Token Rotation

```
WITH token rotation:
  T1: User logs in → gets RefreshToken_A
  T2: Attacker steals RefreshToken_A (undetected at this point)
  T3: User refreshes with RefreshToken_A
      → RefreshToken_A is REVOKED
      → User gets RefreshToken_B
  T4: Attacker tries RefreshToken_A
      → "This token is revoked!" (it was already used in T3)
      → THEFT DETECTED! Revoke ALL tokens for this user
      → Both user and attacker lose access
      → User must re-login (safe starting point)
```

### Visual Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                    TOKEN ROTATION TIMELINE                            │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  NORMAL FLOW (no theft):                                             │
│  ──────────────────────────────────────────────────────────          │
│  Login → AT_1 + RT_1                                                 │
│  RT_1 used → AT_2 + RT_2 (RT_1 revoked ✓)                          │
│  RT_2 used → AT_3 + RT_3 (RT_2 revoked ✓)                          │
│  ... chain continues cleanly                                         │
│                                                                      │
│  THEFT SCENARIO:                                                     │
│  ──────────────────────────────────────────────────────────          │
│  Login → AT_1 + RT_1                                                 │
│  Attacker steals RT_1 ← (theft happens here)                        │
│  User uses RT_1 → AT_2 + RT_2 (RT_1 revoked)                       │
│  Attacker uses RT_1 → ❌ REVOKED! → ALL tokens invalidated          │
│                                                                      │
│  RESULT: Attacker detected, both parties forced to re-authenticate   │
│                                                                      │
│  WHY INVALIDATE ALL?                                                 │
│  → We don't know if RT_2 was also compromised                       │
│  → Safest option: force fresh login from all devices                 │
│  → User re-enters password = proves they're the real owner           │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 8. The Complete Token Lifecycle

```
┌───────────────────────────────────────────────────────────────────────────┐
│                     END-TO-END TOKEN LIFECYCLE                             │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌─────────┐          ┌──────────────┐          ┌──────────────┐         │
│  │  Client │          │  API Gateway │          │Identity Svc  │         │
│  │(browser)│          │  (port 8080) │          │ (port 8081)  │         │
│  └────┬────┘          └──────┬───────┘          └──────┬───────┘         │
│       │                      │                         │                  │
│  ══════════════════════ STEP 1: LOGIN ═════════════════════════════════   │
│       │                      │                         │                  │
│       │ POST /v1/auth/login  │                         │                  │
│       │ {"email":"x","pass"} │                         │                  │
│       │─────────────────────▶│  Forward (no auth       │                  │
│       │                      │  needed for /auth/**)   │                  │
│       │                      │────────────────────────▶│                  │
│       │                      │                         │ 1. Find user     │
│       │                      │                         │ 2. Verify pass   │
│       │                      │                         │ 3. Generate JWT  │
│       │                      │                         │ 4. Save RT in DB │
│       │                      │  {"accessToken":"eyJ...",│                  │
│       │                      │   "refreshToken":"uuid",│                  │
│       │                      │   "expiresIn": 900}    │                  │
│       │                      │◀────────────────────────│                  │
│       │  (same response)     │                         │                  │
│       │◀─────────────────────│                         │                  │
│       │                      │                         │                  │
│  ══════════════════ STEP 2: USE ACCESS TOKEN ═════════════════════════   │
│       │                      │                         │                  │
│       │ GET /v1/payments     │                         │                  │
│       │ Authorization: Bearer eyJ...                   │                  │
│       │─────────────────────▶│                         │                  │
│       │                      │ Validate JWT:           │                  │
│       │                      │ ✓ Signature valid       │                  │
│       │                      │ ✓ Not expired           │                  │
│       │                      │ Extract: userId, role   │                  │
│       │                      │                         │                  │
│       │                      │ Forward with headers:   │                  │
│       │                      │ X-User-Id: user-123     │                  │
│       │                      │ X-User-Role: MERCHANT   │                  │
│       │                      │────────────────────────▶│ (to payment svc) │
│       │                      │                         │                  │
│  ══════════════════ STEP 3: TOKEN REFRESH ════════════════════════════   │
│  (after 15 minutes, access token expires)                                │
│       │                      │                         │                  │
│       │ POST /v1/auth/refresh│                         │                  │
│       │ {"refreshToken":"uuid"}                        │                  │
│       │─────────────────────▶│────────────────────────▶│                  │
│       │                      │                         │ 1. Find RT in DB │
│       │                      │                         │ 2. Check valid   │
│       │                      │                         │ 3. REVOKE old RT │
│       │                      │                         │ 4. Issue new pair│
│       │  {"accessToken":"NEW",│                        │                  │
│       │   "refreshToken":"NEW-UUID"}                   │                  │
│       │◀─────────────────────│◀────────────────────────│                  │
│       │                      │                         │                  │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Configuration Properties Explained

```yaml
# From application.yml — the JWT-related settings:

jwt:
  secret: payflow-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256
  # ┌─────────────────────────────────────────────────────────────────┐
  # │ WHAT: The key used to SIGN and VERIFY JWT tokens.               │
  # │ REQUIREMENT: Must be at least 32 bytes (256 bits) for HS256.    │
  # │ PRODUCTION: Set via environment variable JWT_SECRET.            │
  # │ GENERATE: openssl rand -base64 64                               │
  # │ IF COMPROMISED: Attacker can forge any user's token!            │
  # │ ROTATION: Change the key → all existing tokens become invalid.  │
  # └─────────────────────────────────────────────────────────────────┘

  access-token-expiration: 900000
  # ┌─────────────────────────────────────────────────────────────────┐
  # │ 900,000 milliseconds = 15 minutes                               │
  # │ WHY 15 minutes?                                                 │
  # │ • Short enough: If stolen, damage window is small               │
  # │ • Long enough: User doesn't refresh on every click              │
  # │ • Industry standard: Most OAuth2 implementations use 5-30 min   │
  # └─────────────────────────────────────────────────────────────────┘

  refresh-token-expiration: 604800000
  # ┌─────────────────────────────────────────────────────────────────┐
  # │ 604,800,000 milliseconds = 7 days                               │
  # │ WHY 7 days?                                                     │
  # │ • UX: Users don't want to re-login daily                       │
  # │ • Security: Limits exposure if device is lost                   │
  # │ • Balance: Banking apps use 1 day; social media uses 90 days   │
  # └─────────────────────────────────────────────────────────────────┘
```

---

## 10. How to Verify Your Work

### Test JwtService Manually

After implementing JwtService, run the existing unit test:

```bash
cd backend/identity-service
mvn test -Dtest=JwtServiceTest
```

**Expected:** All 6 tests pass:
- ✅ generateAccessToken returns non-null JWT (3 parts separated by dots)
- ✅ extractUserId returns correct subject
- ✅ isTokenValid returns true for fresh token
- ✅ isTokenValid returns false for expired token
- ✅ extractClaims contains email and role
- ✅ isTokenValid returns false for tampered token

### Test AuthService

```bash
mvn test -Dtest=AuthServiceTest
```

**Expected:** All tests pass:
- ✅ Register creates user and returns tokens
- ✅ Register throws DuplicateResourceException for existing email
- ✅ Login succeeds with valid credentials
- ✅ Login throws UnauthorizedException for wrong password
- ✅ RefreshToken generates new tokens for valid token
- ✅ RefreshToken throws UnauthorizedException for expired token

### Test End-to-End (requires running service)

```bash
# Start the service
mvn spring-boot:run

# Register
curl -s -X POST http://localhost:8081/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Test","email":"test@x.com","password":"Pass1234!"}' | jq

# Login
curl -s -X POST http://localhost:8081/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@x.com","password":"Pass1234!"}' | jq
```

---

## 📝 What You Learned

| # | Concept | Key Takeaway |
|---|---------|--------------|
| 1 | **JWT structure** | Three parts: header (algorithm), payload (claims), signature |
| 2 | **JJWT library API** | `Jwts.builder()` creates tokens; `Jwts.parser().verifyWith()` validates |
| 3 | **HMAC-SHA256** | Symmetric signing — fast, one shared secret between issuer and validator |
| 4 | **Token rotation** | Revoke old refresh token on each use → detects stolen tokens |
| 5 | **BCrypt cost factor** | Cost 12 = ~400ms/hash → impractical brute force |
| 6 | **SecurityConfig** | Identity Service permits /auth/** — Gateway handles real auth |
| 7 | **Dual-token strategy** | Short AT (15 min) for security + long RT (7 days) for UX |
| 8 | **@Transactional** | Atomic operations — if any step fails, everything rolls back |
| 9 | **User enumeration prevention** | Same error for "email not found" and "wrong password" |
| 10 | **Stateless sessions** | No HttpSession = any server instance handles any request |

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

1. `AuthController` — REST endpoints (register, login, refresh, profile)
2. DTOs — Request and Response objects with validation
3. `IdentityExceptionHandler` — Centralized error handling
4. Unit tests with Mockito
5. Controller tests with MockMvc
6. curl examples for manual testing
