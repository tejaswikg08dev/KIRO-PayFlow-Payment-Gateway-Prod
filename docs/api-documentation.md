# PayFlow API Documentation

## Base URL
```
http://localhost:8080
```

## Authentication
All protected endpoints require a JWT token in the Authorization header:
```
Authorization: Bearer <access_token>
```

Merchant API endpoints require an API key:
```
X-API-Key: pk_<key>
```

---

## Identity Service — `/v1/auth`

### Register User
```
POST /v1/auth/register
Content-Type: application/json

{
  "fullName": "John Doe",
  "email": "john@example.com",
  "password": "securePassword123",
  "role": "MERCHANT"
}

Response (201):
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "550e8400-e29b...",
    "expiresIn": 900,
    "user": {
      "id": "user-abc123",
      "email": "john@example.com",
      "fullName": "John Doe",
      "role": "MERCHANT"
    }
  },
  "timestamp": "2026-08-12T10:30:00Z"
}
```

### Login
```
POST /v1/auth/login
Content-Type: application/json

{
  "email": "john@example.com",
  "password": "securePassword123"
}

Response (200): Same as register
```

### Refresh Token
```
POST /v1/auth/refresh
Content-Type: application/json

{
  "refreshToken": "550e8400-e29b..."
}
```

### Get Profile
```
GET /v1/auth/profile
Authorization: Bearer <token>
```

---

## Merchant Service — `/v1/merchants`

### Register Merchant
```
POST /v1/merchants
Authorization: Bearer <token>
Content-Type: application/json

{
  "name": "My Online Store",
  "email": "store@example.com",
  "businessType": "E_COMMERCE",
  "websiteUrl": "https://mystore.com"
}
```

### Generate API Key
```
POST /v1/merchants/{merchantId}/api-keys
Authorization: Bearer <token>

Response (201):
{
  "success": true,
  "data": {
    "id": "key-123",
    "prefix": "abcd1234",
    "rawKey": "pk_abcd1234xxxxxxx...",
    "active": true
  }
}
```

### Configure Webhook
```
POST /v1/merchants/{merchantId}/webhooks
Authorization: Bearer <token>
Content-Type: application/json

{
  "url": "https://mystore.com/webhooks/payflow",
  "events": ["payment.authorized", "payment.captured", "payment.refunded"]
}
```

---

## Payment Service — `/v1/orders`, `/v1/payments`, `/v1/refunds`

### Create Order
```
POST /v1/orders
X-API-Key: pk_<key>
Content-Type: application/json

{
  "merchantId": "mer-abc123",
  "amount": 15000,
  "currency": "INR",
  "customerEmail": "customer@example.com",
  "description": "Order #12345",
  "receiptNumber": "rcpt_001"
}

Response (201):
{
  "success": true,
  "data": {
    "id": "order_abc123def456",
    "merchantId": "mer-abc123",
    "amount": 15000,
    "currency": "INR",
    "status": "CREATED",
    "expiresAt": "2026-08-12T11:00:00Z"
  }
}
```

### Authorize Payment
```
POST /v1/payments/authorize
X-API-Key: pk_<key>
Idempotency-Key: unique-request-id
Content-Type: application/json

{
  "orderId": "order_abc123def456",
  "paymentMethod": "CARD",
  "cardNumber": "4111111111111111",
  "cardExpiryMonth": "12",
  "cardExpiryYear": "2026",
  "cardCvv": "123"
}

Response (201):
{
  "success": true,
  "data": {
    "id": "pay_xyz789abc012",
    "orderId": "order_abc123def456",
    "amount": 15000,
    "currency": "INR",
    "status": "AUTHORIZED",
    "authorizationCode": "A12345",
    "paymentMethod": "CARD"
  }
}
```

### Capture Payment
```
POST /v1/payments/capture
X-API-Key: pk_<key>
Content-Type: application/json

{
  "paymentId": "pay_xyz789abc012",
  "amount": 15000
}

Response (200): Payment with status "CAPTURED"
```

### Void Payment
```
POST /v1/payments/{paymentId}/void
X-API-Key: pk_<key>

Response (200): Payment with status "VOIDED"
```

### Create Refund
```
POST /v1/refunds
X-API-Key: pk_<key>
Content-Type: application/json

{
  "paymentId": "pay_xyz789abc012",
  "amount": 5000,
  "reason": "Customer returned item"
}

Response (201):
{
  "success": true,
  "data": {
    "id": "rfnd_qrs345tuv678",
    "paymentId": "pay_xyz789abc012",
    "amount": 5000,
    "reason": "Customer returned item",
    "status": "PROCESSED"
  }
}
```

---

## Settlement Service — `/v1/settlements`

### Trigger Settlement
```
POST /v1/settlements/trigger
Authorization: Bearer <admin_token>

Response (200):
{
  "success": true,
  "data": {
    "batchId": "stl_abc123",
    "settlementDate": "2026-08-12",
    "status": "PROCESSING"
  }
}
```

### List Settlements
```
GET /v1/settlements?page=0&size=20
Authorization: Bearer <token>
```

---

## Error Responses

All errors follow this format:
```json
{
  "success": false,
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "Payment not found with id: pay_invalid",
    "details": null
  },
  "timestamp": "2026-08-12T10:30:00Z",
  "path": "/v1/payments/pay_invalid"
}
```

### Error Codes
| Code | HTTP Status | Description |
|------|-------------|-------------|
| VALIDATION_ERROR | 400 | Request body validation failed |
| UNAUTHORIZED | 401 | Invalid or missing authentication |
| FORBIDDEN | 403 | Insufficient permissions |
| RESOURCE_NOT_FOUND | 404 | Entity not found |
| DUPLICATE_RESOURCE | 409 | Resource already exists |
| IDEMPOTENCY_CONFLICT | 409 | Duplicate request in progress |
| RATE_LIMIT_EXCEEDED | 429 | Too many requests |
| PAYMENT_DECLINED | 402 | Bank declined the payment |
| INTERNAL_ERROR | 500 | Unexpected server error |
