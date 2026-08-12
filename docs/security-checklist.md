# Security Checklist — PayFlow Payment Gateway

| Field | Details |
|-------|---------|
| **Project** | PayFlow Payment Gateway |
| **Document Type** | Security Reference & Checklist |
| **Compliance Target** | PCI-DSS Level 1 Principles |
| **Last Updated** | 2024 |
| **Audience** | Developers, Security Reviewers, Interviewers |

---

## Table of Contents
- [Security Layers Overview](#security-layers)
- [Authentication Security](#authentication)
- [API Key Security](#api-key)
- [Rate Limiting](#rate-limiting)
- [Webhook HMAC Signatures](#webhook-hmac)
- [Data Security & PCI-DSS](#data-security)
- [Input Validation](#input-validation)
- [CORS Configuration](#cors)
- [Secrets Management](#secrets)
- [Idempotency](#idempotency)
- [Circuit Breaker](#circuit-breaker)
- [SQL Injection Prevention](#sql-injection)
- [Error Handling](#error-handling)
- [Security Checklist Table](#checklist)

---

## Security Layers Overview

PayFlow implements defense-in-depth with 7 security layers:

```
Layer 7: API Gateway        → Rate limiting, JWT validation, CORS
Layer 6: Authentication     → JWT tokens, API key verification
Layer 5: Authorization      → Role-based access (ADMIN, MERCHANT)
Layer 4: Input Validation   → Jakarta Bean Validation on all DTOs
Layer 3: Business Logic     → Idempotency, circuit breaker, fraud checks
Layer 2: Data Protection    → PAN masking, encrypted storage, no raw secrets
Layer 1: Infrastructure     → TLS everywhere, network isolation, DB credentials rotation
```

Every request must pass through ALL layers before reaching business logic.

---

## Authentication Security

### JWT Token Strategy

| Property | Value | Rationale |
|----------|-------|-----------|
| Algorithm | HS256 (HMAC-SHA256) | Symmetric — single service signs/verifies |
| Access Token TTL | 15 minutes | Short-lived limits damage from stolen tokens |
| Refresh Token TTL | 7 days | Longer-lived but rotated on each use |
| Secret Key Length | 256 bits minimum | Matches HMAC-SHA256 key size requirement |
| Token Storage | HttpOnly cookie (frontend) | Prevents XSS from reading tokens |

**Refresh Token Rotation:**
```
1. Client sends expired access token + valid refresh token
2. Server validates refresh token against DB
3. Server issues NEW access token + NEW refresh token
4. Server invalidates OLD refresh token (one-time use)
5. If old refresh token is reused → revoke ALL tokens for user (breach detected)
```

**PayFlow implementation:** `JwtTokenProvider.java` in `identity-service` handles signing, validation, and claims extraction.

---

## API Key Security

Merchants authenticate API calls using API keys for server-to-server communication.

**Key principles:**
- Generate keys using `SecureRandom` (256-bit entropy)
- Store only the SHA-256 hash in the database — never the raw key
- Show the raw key exactly ONCE at creation time
- Prefix keys with `pk_live_` or `pk_test_` for environment identification
- Support key rotation: merchants can have multiple active keys

```java
// Generation (shown once to merchant)
String rawKey = "pk_live_" + Base64.encode(SecureRandom.getBytes(32));

// Storage (DB only stores hash)
String hashedKey = SHA256.hash(rawKey);
apiKeyRepository.save(new ApiKey(merchantId, hashedKey, "active"));

// Verification (on each request)
String incomingKey = request.getHeader("X-API-Key");
String incomingHash = SHA256.hash(incomingKey);
ApiKey stored = apiKeyRepository.findByHash(incomingHash); // constant-time compare
```

---

## Rate Limiting

**Strategy:** Redis sliding window algorithm at the API Gateway level.

| Scope | Limit | Window | Purpose |
|-------|-------|--------|---------|
| Per IP | 100 requests | 1 minute | Prevent brute force |
| Per Merchant (API Key) | 1000 requests | 1 minute | Prevent abuse |
| Per Endpoint (auth) | 5 attempts | 5 minutes | Prevent credential stuffing |
| Global | 10,000 requests | 1 minute | Protect infrastructure |

**Implementation:** `RateLimitFilter.java` in `api-gateway` uses Redis `ZADD` with score = timestamp.

```java
// Sliding window check
long windowStart = Instant.now().minusSeconds(60).toEpochMilli();
redis.zremrangeByScore(key, 0, windowStart);    // Remove expired entries
long count = redis.zcard(key);                   // Count remaining
if (count >= limit) return TOO_MANY_REQUESTS;    // 429
redis.zadd(key, now, requestId);                 // Add current request
redis.expire(key, 61);                           // Auto-cleanup
```

**Response headers:** `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

---

## Webhook HMAC Signatures

Merchants must verify that webhook payloads genuinely came from PayFlow.

**Signing process:**
```
1. Construct signing string: timestamp + "." + rawBody
2. Compute HMAC-SHA256 using merchant's webhook secret
3. Send signature in header: X-PayFlow-Signature: t=<timestamp>,v1=<signature>
```

**Merchant verification (their side):**
```java
String payload = timestamp + "." + requestBody;
String expected = HMAC_SHA256(webhookSecret, payload);
String received = extractV1FromHeader(signatureHeader);

// Constant-time comparison prevents timing attacks
if (!MessageDigest.isEqual(expected.getBytes(), received.getBytes())) {
    throw new SecurityException("Invalid webhook signature");
}

// Reject if timestamp is older than 5 minutes (prevents replay)
if (Instant.now().minus(5, MINUTES).isAfter(Instant.ofEpochSecond(timestamp))) {
    throw new SecurityException("Webhook timestamp too old");
}
```

---

## Data Security & PCI-DSS

PayFlow follows PCI-DSS principles for card data protection:

| Rule | Implementation |
|------|---------------|
| Never store full PAN after authorization | Only store first 6 + last 4 digits |
| Never store CVV/CVC | Not persisted anywhere, memory only during auth |
| Mask PAN in all logs | `LogMaskingFilter` replaces middle digits with `*` |
| Encrypt PAN in transit | TLS 1.2+ between all services |
| Tokenize for repeat payments | Replace PAN with opaque token after first use |
| Restrict access to card data | Only `routing-service` handles raw PAN |

**Log masking example:**
```
Before: Processing payment for card 4532015112830366
After:  Processing payment for card 453201******0366
```

---

## Input Validation

All request DTOs use Jakarta Bean Validation annotations:

```java
public class PaymentRequest {
    @NotBlank(message = "Card number is required")
    @Pattern(regexp = "\\d{13,19}", message = "Invalid card number format")
    private String cardNumber;

    @NotNull @Positive
    @DecimalMax(value = "999999.99", message = "Amount exceeds maximum")
    private BigDecimal amount;

    @NotBlank @Size(min = 3, max = 3)
    private String currency;

    @NotBlank @Pattern(regexp = "\\d{2}/\\d{2}")
    private String expiryDate;
}
```

**Additional server-side checks:** Luhn algorithm validation, expiry date not in past, currency in supported list.

---

## CORS Configuration

```yaml
# api-gateway application.yml
spring:
  cloud:
    gateway:
      globalcors:
        corsConfigurations:
          '[/**]':
            allowedOrigins:
              - "https://dashboard.payflow.com"
              - "http://localhost:3000"  # Development only
            allowedMethods: GET, POST, PUT, DELETE, OPTIONS
            allowedHeaders: Authorization, Content-Type, X-API-Key, X-Idempotency-Key
            exposedHeaders: X-RateLimit-Remaining, X-Request-Id
            allowCredentials: true
            maxAge: 3600
```

**Rules:** Never use `allowedOrigins: "*"` with `allowCredentials: true`. Whitelist specific frontend origins only.

---

## Secrets Management

| Secret | Storage | Access |
|--------|---------|--------|
| JWT signing key | Environment variable `JWT_SECRET` | identity-service only |
| Database passwords | Environment variable / AWS Secrets Manager | Per-service isolation |
| API key salt | Environment variable `API_KEY_SALT` | merchant-service only |
| Webhook signing secrets | Database (encrypted column) | webhook-service only |
| Redis password | Environment variable `REDIS_PASSWORD` | api-gateway only |

**Rules:**
- Never hardcode secrets in source code or config files checked into git
- Use `.env` files locally (added to `.gitignore`)
- Use AWS Secrets Manager or Parameter Store in production
- Rotate secrets on a schedule (90 days for passwords, 30 days for API keys)

---

## Idempotency

Prevents duplicate charges from network retries or client bugs.

```
Client sends: POST /v1/payments/authorize
Header: X-Idempotency-Key: idem_abc123

First call  → Process payment → Store result with key "idem_abc123" → Return 200
Second call → Find cached result for "idem_abc123"                  → Return cached 200
```

**Storage:** Redis with 24-hour TTL. Key = `idempotency:{merchantId}:{idempotencyKey}`.
**Security benefit:** Prevents replay attacks — even if an attacker captures a request, replaying it returns the same result without creating a new charge.

---

## Circuit Breaker

Prevents cascading failures and resource exhaustion when downstream services are unhealthy.

| Parameter | Value | Purpose |
|-----------|-------|---------|
| Failure threshold | 5 consecutive failures | Trip the circuit |
| Wait duration | 30 seconds | Time in OPEN state before retry |
| Permitted calls in half-open | 3 | Test if service recovered |
| Slow call threshold | 2 seconds | Treat slow calls as failures |

**Security benefit:** Prevents resource exhaustion attacks where an attacker deliberately triggers slow/failing downstream calls to consume thread pools.

---

## SQL Injection Prevention

PayFlow uses Spring Data JPA with parameterized queries exclusively:

```java
// SAFE — parameterized query (PayFlow uses this)
@Query("SELECT p FROM Payment p WHERE p.merchantId = :merchantId AND p.status = :status")
List<Payment> findByMerchantAndStatus(@Param("merchantId") String merchantId,
                                       @Param("status") PaymentStatus status);

// DANGEROUS — string concatenation (never do this)
// "SELECT * FROM payments WHERE merchant_id = '" + merchantId + "'"
```

**Rule:** No native SQL queries with string concatenation. All queries go through JPA repositories or use `@Query` with named parameters.

---

## Error Handling

Never expose internal details to clients:

```java
// What the client sees (safe):
{
  "error": "PAYMENT_DECLINED",
  "message": "The payment was declined by the issuing bank",
  "requestId": "req_abc123"
}

// What gets logged internally (detailed):
// [ERROR] Payment declined: merchantId=m_123, amount=100.00,
//         responseCode=51, bankRef=BNK789, trace=STN000001
```

**Rules:**
- Never return stack traces in API responses
- Never return database errors or SQL in responses
- Use generic error codes with safe messages
- Log full details server-side with correlation IDs
- Return `requestId` so support can trace issues

---

## Security Checklist

| # | Category | Check | Status |
|---|----------|-------|--------|
| 1 | Auth | JWT tokens have 15-min expiry | ✅ |
| 2 | Auth | Refresh tokens are single-use (rotated) | ✅ |
| 3 | Auth | API keys stored as SHA-256 hashes only | ✅ |
| 4 | Network | Rate limiting on all public endpoints | ✅ |
| 5 | Network | CORS whitelist (no wildcard origins) | ✅ |
| 6 | Network | TLS 1.2+ for all inter-service communication | ✅ |
| 7 | Data | PAN masked in all logs | ✅ |
| 8 | Data | CVV never persisted (memory only) | ✅ |
| 9 | Data | Full PAN not stored after authorization | ✅ |
| 10 | Input | Jakarta Bean Validation on all DTOs | ✅ |
| 11 | Input | Luhn check on card numbers | ✅ |
| 12 | Webhooks | HMAC-SHA256 signature on all outbound webhooks | ✅ |
| 13 | Webhooks | Timestamp validation (5-min window) | ✅ |
| 14 | Resilience | Idempotency keys prevent duplicate charges | ✅ |
| 15 | Resilience | Circuit breaker prevents resource exhaustion | ✅ |
| 16 | DB | Parameterized queries only (no SQL concatenation) | ✅ |
| 17 | Errors | No stack traces in API responses | ✅ |
| 18 | Secrets | No hardcoded secrets in source code | ✅ |
| 19 | Secrets | All secrets via environment variables | ✅ |
| 20 | Infra | Database credentials per-service isolation | ✅ |

---

*This checklist should be reviewed before every release and during security audits. For implementation details, see the corresponding Phase 4 documentation.*
