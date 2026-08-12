# Phase 4: Project Setup and Coding

## Overview

Phase 4 is the implementation phase — broken into 15 parts that build on each other. Each part produces working, testable code. Follow the parts in order since later services depend on earlier infrastructure.

## Implementation Parts Index

| Part | Title | Key Deliverables |
|------|-------|-----------------|
| Part 1 | Infrastructure Setup | Parent POM, common-lib, service-registry, config-server |
| Part 2 | API Gateway | Routes, filters, rate limiting, CORS |
| Part 3 | Identity Service | Registration, login, JWT, RBAC |
| Part 4 | Merchant Service | Onboarding, API keys, webhook config |
| Part 5 | Payment Service | Payment engine, state machine, idempotency |
| Part 6 | Routing Service | ISO 8583, fraud check, bank routing |
| Part 7 | Bank Simulator | Mock responses, configurable latency |
| Part 8 | Settlement Service | Spring Batch, daily batches |
| Part 9 | Webhook Service | Event delivery, HMAC signing, retry |
| Part 10 | Notification Service | Email/SMS via templates |
| Part 11 | Docker | Dockerfiles, docker-compose, networking |
| Part 12a | Dashboard Setup | React + Vite + Tailwind + Router |
| Part 12b | Dashboard Auth | Login, register, JWT context |
| Part 12c | Dashboard Features | Transactions, analytics, charts |
| Part 12d | Dashboard Settings | API keys, webhooks, profile |
| Part 13 | Hosted Checkout | Card/UPI/Net Banking payment forms |

## Build Commands

### Build entire backend
```bash
cd backend
mvn clean install -DskipTests
```

### Build specific service
```bash
cd backend/payment-service
mvn clean package
```

### Run with dev profile
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Frontend build
```bash
cd frontend/merchant-dashboard
npm install
npm run build
```

## Service Startup Order

Services must start in this order due to dependencies:

1. **Config Server** (port 8888) — all services fetch config
2. **Service Registry** (port 8761) — all services register
3. **API Gateway** (port 8080) — entry point
4. **Identity Service** (port 8081) — auth for other services
5. **Merchant Service** (port 8082)
6. **Payment Service** (port 8083)
7. **Routing Service** (port 8084)
8. **Bank Simulator** (port 8085)
9. **Settlement Service** (port 8086)
10. **Webhook Service** (port 8087)
11. **Notification Service** (port 8088)

## Verification Steps

After each part, verify with these checks:

### Infrastructure Verification
```bash
# Eureka dashboard
curl http://localhost:8761

# Config server health
curl http://localhost:8888/actuator/health

# Gateway routes
curl http://localhost:8080/actuator/gateway/routes
```

### Service Health Checks
```bash
# Check any service
curl http://localhost:{port}/actuator/health

# Expected response
{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}
```

### End-to-End Payment Flow
```bash
# 1. Register merchant
curl -X POST http://localhost:8080/api/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{"businessName":"TestShop","email":"test@shop.com"}'

# 2. Get API key from response

# 3. Initiate payment
curl -X POST http://localhost:8080/api/v1/payments \
  -H "X-API-Key: {key}" \
  -H "X-Idempotency-Key: unique-123" \
  -H "Content-Type: application/json" \
  -d '{"amount":100.00,"currency":"INR","method":"CARD"}'

# 4. Check payment status
curl http://localhost:8080/api/v1/payments/{paymentId} \
  -H "X-API-Key: {key}"
```

## Common Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| Service can't connect to Config Server | Config server not running | Start config-server first |
| Eureka registration failing | Wrong eureka URL | Check `eureka.client.serviceUrl` |
| 401 on API calls | Missing/expired JWT | Re-authenticate via /auth/login |
| Port already in use | Previous instance running | Kill process on that port |
| DB connection refused | PostgreSQL not running | Start Docker or local PG |
