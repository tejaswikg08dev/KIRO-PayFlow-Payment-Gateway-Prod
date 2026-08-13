# PayFlow Payment Gateway — API Documentation

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Type** | API Reference |
| **Version** | v1.0.0 |
| **Previous** | [Phase 9 — Monitoring](phase9-monitoring-observability.md) |
| **Next** | [Database Guide](database-guide.md) |
| **Base URL** | `http://localhost:8080/v1` (local) / `https://api.payflow.example.com/v1` (prod) |
| **Prerequisites** | Running PayFlow services |

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Identity Service Endpoints](#2-identity-service-endpoints)
3. [Merchant Service Endpoints](#3-merchant-service-endpoints)
4. [Payment Service Endpoints](#4-payment-service-endpoints)
5. [Settlement Service Endpoints](#5-settlement-service-endpoints)
6. [Error Response Format](#6-error-response-format)
7. [Webhook Payload Format](#7-webhook-payload-format)
8. [What You Learned](#what-you-learned)
9. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Authentication

PayFlow supports two authentication methods:

### JWT Bearer Token

Obtained via login. Used for merchant dashboard and API calls.

```bash
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### API Key

Used for server-to-server integration. Passed as header:

```bash
X-API-Key: pk_live_a3f9b2c1d4e5f6a7b8c9d0e1f2a3b4c5
```

### Token Lifecycle

```
Register → Login → Get JWT (24h TTL) → Use in requests → Refresh when expired
```

---

## 2. Identity Service Endpoints

### POST /v1/auth/register

Register a new merchant account.

```bash
curl -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@example.com",
    "password": "SecurePass123!",
    "businessName": "Acme Payments",
    "phone": "+919876543210"
  }'
```

**Response (201 Created):**
```json
{
  "id": "merchant-uuid-001",
  "email": "merchant@example.com",
  "businessName": "Acme Payments",
  "status": "ACTIVE",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

### POST /v1/auth/login

Authenticate and receive JWT token.

```bash
curl -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@example.com",
    "password": "SecurePass123!"
  }'
```

**Response (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
  "expiresIn": 86400,
  "tokenType": "Bearer"
}
```

### POST /v1/auth/refresh

Refresh an expired access token.

```bash
curl -X POST http://localhost:8080/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "eyJhbGciOiJIUzI1NiIs..."}'
```

### POST /v1/auth/logout

Invalidate current token.

```bash
curl -X POST http://localhost:8080/v1/auth/logout \
  -H "Authorization: Bearer eyJhbG..."
```

---

## 3. Merchant Service Endpoints

### GET /v1/merchants/profile

Get current merchant's profile.

```bash
curl http://localhost:8080/v1/merchants/profile \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
{
  "id": "merchant-uuid-001",
  "email": "merchant@example.com",
  "businessName": "Acme Payments",
  "phone": "+919876543210",
  "gstNumber": "29ABCDE1234F1Z5",
  "pan": "ABCDE1234F",
  "websiteUrl": "https://acme.com",
  "status": "ACTIVE",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

### PUT /v1/merchants/profile

Update merchant profile.

```bash
curl -X PUT http://localhost:8080/v1/merchants/profile \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "businessName": "Acme Payments Ltd",
    "websiteUrl": "https://acme-payments.com"
  }'
```

### POST /v1/merchants/api-keys

Generate a new API key.

```bash
curl -X POST http://localhost:8080/v1/merchants/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Production Key"}'
```

**Response (201 Created):**
```json
{
  "id": "key-uuid-001",
  "name": "Production Key",
  "key": "pk_live_a3f9b2c1d4e5f6a7b8c9d0e1f2a3b4c5",
  "maskedKey": "pk_live_****b4c5",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

> ⚠️ The full `key` is shown ONLY in this response. Save it immediately.

### GET /v1/merchants/api-keys

List all API keys (masked).

```bash
curl http://localhost:8080/v1/merchants/api-keys \
  -H "Authorization: Bearer $TOKEN"
```

### DELETE /v1/merchants/api-keys/{keyId}

Revoke an API key.

```bash
curl -X DELETE http://localhost:8080/v1/merchants/api-keys/key-uuid-001 \
  -H "Authorization: Bearer $TOKEN"
```

---

## 4. Payment Service Endpoints

### POST /v1/payments/orders

Create a new payment order.

```bash
curl -X POST http://localhost:8080/v1/payments/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: unique-key-$(date +%s)" \
  -d '{
    "amount": 15000,
    "currency": "INR",
    "description": "Premium Plan Subscription",
    "customerEmail": "buyer@example.com",
    "customerPhone": "+919876543210",
    "metadata": {
      "planId": "premium-monthly",
      "userId": "user-123"
    }
  }'
```

**Response (201 Created):**
```json
{
  "id": "order-uuid-001",
  "orderId": "ORD_abc123def456",
  "amount": 15000,
  "currency": "INR",
  "status": "CREATED",
  "description": "Premium Plan Subscription",
  "createdAt": "2024-01-15T10:30:00Z",
  "expiresAt": "2024-01-15T11:00:00Z"
}
```

### POST /v1/payments/orders/{orderId}/authorize

Authorize payment with a payment method.

```bash
curl -X POST http://localhost:8080/v1/payments/orders/ORD_abc123def456/authorize \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "method": "CARD",
    "card": {
      "number": "4111111111111111",
      "expiryMonth": 12,
      "expiryYear": 2026,
      "cvv": "123",
      "holderName": "John Doe"
    }
  }'
```

**Response (200 OK):**
```json
{
  "id": "order-uuid-001",
  "orderId": "ORD_abc123def456",
  "status": "AUTHORIZED",
  "paymentMethod": "CARD",
  "authorizedAt": "2024-01-15T10:30:05Z",
  "bankReferenceId": "BANK_REF_xyz789"
}
```

### POST /v1/payments/orders/{orderId}/capture

Capture an authorized payment.

```bash
curl -X POST http://localhost:8080/v1/payments/orders/ORD_abc123def456/capture \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount": 15000}'
```

**Response (200 OK):**
```json
{
  "id": "order-uuid-001",
  "orderId": "ORD_abc123def456",
  "status": "CAPTURED",
  "capturedAmount": 15000,
  "capturedAt": "2024-01-15T10:31:00Z"
}
```

### POST /v1/payments/orders/{orderId}/refund

Refund a captured payment (full or partial).

```bash
curl -X POST http://localhost:8080/v1/payments/orders/ORD_abc123def456/refund \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 5000,
    "reason": "Customer requested partial refund"
  }'
```

**Response (200 OK):**
```json
{
  "refundId": "refund-uuid-001",
  "orderId": "ORD_abc123def456",
  "amount": 5000,
  "status": "PROCESSED",
  "reason": "Customer requested partial refund",
  "createdAt": "2024-01-15T11:00:00Z"
}
```

### GET /v1/payments/orders/{orderId}

Get order details.

```bash
curl http://localhost:8080/v1/payments/orders/ORD_abc123def456 \
  -H "Authorization: Bearer $TOKEN"
```

### GET /v1/payments/transactions

List transactions with filters.

```bash
curl "http://localhost:8080/v1/payments/transactions?status=CAPTURED&page=0&size=20&from=2024-01-01&to=2024-01-31" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 5. Settlement Service Endpoints

### GET /v1/settlements

List settlements.

```bash
curl http://localhost:8080/v1/settlements \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
{
  "content": [
    {
      "id": "settlement-uuid-001",
      "amount": 145000,
      "transactionCount": 23,
      "status": "COMPLETED",
      "settledAt": "2024-01-16T00:00:00Z",
      "bankReference": "NEFT_REF_001"
    }
  ],
  "totalElements": 15,
  "totalPages": 2,
  "page": 0
}
```

### GET /v1/settlements/{id}

Get settlement details with included transactions.

```bash
curl http://localhost:8080/v1/settlements/settlement-uuid-001 \
  -H "Authorization: Bearer $TOKEN"
```

---

## 6. Error Response Format

All errors follow a consistent format:

```json
{
  "error": "PAYMENT_DECLINED",
  "message": "The payment was declined by the issuing bank",
  "status": 422,
  "timestamp": "2024-01-15T10:30:00Z",
  "path": "/v1/payments/orders/ORD_abc123/authorize",
  "correlationId": "corr-uuid-001"
}
```

### Error Codes Table

| Error Code | HTTP Status | Description |
|-----------|------------|-------------|
| `VALIDATION_ERROR` | 400 | Invalid request body or parameters |
| `UNAUTHORIZED` | 401 | Missing or invalid authentication |
| `FORBIDDEN` | 403 | Insufficient permissions |
| `ORDER_NOT_FOUND` | 404 | Payment order doesn't exist |
| `IDEMPOTENCY_CONFLICT` | 409 | Duplicate request with different body |
| `INVALID_STATE` | 422 | Order not in valid state for operation |
| `PAYMENT_DECLINED` | 422 | Bank declined the payment |
| `INSUFFICIENT_FUNDS` | 422 | Card has insufficient balance |
| `RATE_LIMITED` | 429 | Too many requests |
| `INTERNAL_ERROR` | 500 | Unexpected server error |
| `SERVICE_UNAVAILABLE` | 503 | Downstream service temporarily down |

---

## 7. Webhook Payload Format

### Event Delivery

PayFlow sends POST requests to your registered webhook URL:

```json
{
  "id": "evt_abc123def456",
  "type": "payment.captured",
  "timestamp": "2024-01-15T10:31:00Z",
  "data": {
    "orderId": "ORD_abc123def456",
    "amount": 15000,
    "currency": "INR",
    "status": "CAPTURED",
    "merchantId": "merchant-uuid-001",
    "paymentMethod": "CARD"
  }
}
```

### Signature Verification

Webhooks include a signature header for verification:

```
X-PayFlow-Signature: sha256=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
```

**Verification algorithm:**
```java
String payload = requestBody;
String secret = "your-webhook-secret";
String expectedSignature = "sha256=" + HmacUtils.hmacSha256Hex(secret, payload);
boolean valid = expectedSignature.equals(receivedSignature);
```

### Supported Events

| Event | Trigger |
|-------|---------|
| `payment.authorized` | Payment successfully authorized |
| `payment.captured` | Payment captured (money collected) |
| `payment.failed` | Payment attempt failed |
| `refund.created` | Refund initiated |
| `refund.processed` | Refund completed |
| `settlement.completed` | Settlement batch completed |

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Auth methods | JWT for dashboard, API Key for server-to-server |
| 2 | Identity API | Register, login, refresh, logout |
| 3 | Merchant API | Profile CRUD, API key management |
| 4 | Payment API | Create → Authorize → Capture → Refund lifecycle |
| 5 | Settlements | Automated batch settlement with transaction details |
| 6 | Error format | Consistent error codes + correlation IDs |
| 7 | Webhooks | Signed payloads, event-driven notifications |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| 401 `UNAUTHORIZED` | Token expired or missing | Re-login or add `Authorization` header |
| 409 `IDEMPOTENCY_CONFLICT` | Same key, different body | Use unique idempotency key per request |
| 422 `INVALID_STATE` | Capturing before authorizing | Follow lifecycle: create → authorize → capture |
| 400 `VALIDATION_ERROR` | Missing required field | Check request body against documentation |
| 429 `RATE_LIMITED` | Too many requests | Implement exponential backoff |
| Webhook signature mismatch | Wrong secret or body modified | Use raw request body (not parsed JSON) for HMAC |

---

<div align="center">

**[← Phase 9: Monitoring](phase9-monitoring-observability.md)** | **[Documentation Index](../README.md)** | **[Database Guide →](database-guide.md)**

</div>
