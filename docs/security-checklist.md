# Security Checklist

## Overview

Security hardening steps for PayFlow covering authentication, encryption, rate limiting, PCI-DSS principles, and common attack prevention.

## Security Layers

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: TRANSPORT         TLS 1.3, HTTPS everywhere        │
├─────────────────────────────────────────────────────────────┤
│ Layer 2: AUTHENTICATION    JWT tokens, API keys             │
├─────────────────────────────────────────────────────────────┤
│ Layer 3: AUTHORIZATION     Role-based access control        │
├─────────────────────────────────────────────────────────────┤
│ Layer 4: INPUT VALIDATION  Schema validation, sanitization  │
├─────────────────────────────────────────────────────────────┤
│ Layer 5: RATE LIMITING     Token bucket, per-client limits  │
├─────────────────────────────────────────────────────────────┤
│ Layer 6: DATA PROTECTION   Encryption at rest, masking      │
├─────────────────────────────────────────────────────────────┤
│ Layer 7: AUDIT LOGGING     All actions logged, tamper-proof  │
└─────────────────────────────────────────────────────────────┘
```

## Checklist

### Authentication & Session Management

```
[x] Passwords hashed with BCrypt (strength 12)
[x] JWT tokens with short expiry (15 min access, 7 day refresh)
[x] Refresh token rotation (old token revoked on use)
[x] All tokens revoked on logout
[x] API keys stored as SHA-256 hashes (not plaintext)
[x] API key shown only once at creation
[x] No sensitive data in JWT payload
[x] Timing-safe comparison for token validation
[ ] Account lockout after 5 failed login attempts
[ ] Password complexity requirements enforced
```

### API Security

```
[x] All endpoints require authentication (except public paths)
[x] Rate limiting on all endpoints (100 req/sec per client)
[x] Stricter rate limit on auth endpoints (5 req/min)
[x] Input validation with Bean Validation (@Valid)
[x] Request size limits (max 1MB body)
[x] CORS configured with explicit whitelist
[x] No sensitive data in URL parameters
[x] Pagination enforced (max 100 items per page)
[x] API versioning (/api/v1/) for breaking changes
[ ] Request throttling by IP for unauthenticated endpoints
```

### Data Protection

```
[x] Card numbers never stored (passed through only)
[x] Card details masked in logs (show last 4 only)
[x] Database encryption at rest (RDS default)
[x] Secrets in environment variables (not in code)
[x] .env files in .gitignore
[x] Webhook secrets stored hashed
[x] PAN masked in all non-essential flows
[ ] Field-level encryption for PII
[ ] Database connection over TLS
```

### HMAC Webhook Signatures

```java
// Signing (PayFlow → Merchant)
String signature = HMAC-SHA256(timestamp + "." + payload, webhookSecret);
Header: X-PayFlow-Signature: t=<timestamp>,v1=<signature>

// Verification (Merchant's code)
1. Extract timestamp from header
2. Check timestamp within 5-minute tolerance (prevent replay)
3. Reconstruct signing string: timestamp + "." + rawBody
4. Compute HMAC with stored secret
5. Timing-safe comparison of computed vs received signature
```

### Rate Limiting Implementation

```
Strategy: Token Bucket (Redis-backed)
- 100 tokens per second (refill rate)
- 200 burst capacity
- Per API key (authenticated) or per IP (unauthenticated)

Response when exceeded:
HTTP 429 Too Many Requests
Headers:
  X-RateLimit-Limit: 100
  X-RateLimit-Remaining: 0
  X-RateLimit-Reset: 1705312345 (Unix timestamp)
  Retry-After: 1
```

### PCI-DSS Principles Applied

| Requirement | Implementation |
|------------|----------------|
| Req 1: Firewall | Security groups restrict access |
| Req 2: No defaults | All passwords changed, ports restricted |
| Req 3: Protect stored data | No card data stored; hashing for keys |
| Req 4: Encrypt transmission | TLS 1.3 for all communication |
| Req 6: Secure systems | Dependencies scanned, updated |
| Req 7: Restrict access | Least privilege IAM, RBAC |
| Req 8: Identify users | Unique IDs, strong auth |
| Req 10: Track access | Audit logging, CloudWatch |
| Req 11: Test security | Automated scanning in CI |
| Req 12: Security policy | This checklist + documentation |

### Common Attack Prevention

| Attack | Prevention |
|--------|-----------|
| SQL Injection | JPA parameterized queries, no raw SQL |
| XSS | React auto-escapes, CSP headers |
| CSRF | Stateless API (JWT), no cookies for auth |
| Replay Attack | Idempotency keys, timestamp validation |
| Brute Force | Rate limiting, account lockout |
| Man-in-the-Middle | TLS everywhere, certificate pinning |
| Privilege Escalation | RBAC, validate ownership on all queries |
| Mass Assignment | Explicit DTOs, no entity binding |
| Information Disclosure | Generic error messages, no stack traces |
| Dependency Vulnerability | Dependabot, OWASP dependency check |

### Encryption Standards

| Data | Algorithm | Key Size | Notes |
|------|-----------|----------|-------|
| Passwords | BCrypt | N/A (12 rounds) | One-way hash |
| JWT | HMAC-SHA512 | 512-bit key | Symmetric signing |
| API Keys | SHA-256 | 256-bit | One-way hash |
| Webhooks | HMAC-SHA256 | 256-bit | Message authentication |
| Data at Rest | AES-256 | 256-bit | RDS default encryption |
| Data in Transit | TLS 1.3 | 256-bit | HTTPS |

### Security Headers

```
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 1; mode=block
Content-Security-Policy: default-src 'self'
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
```
