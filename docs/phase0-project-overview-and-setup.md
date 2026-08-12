# Phase 0: Project Overview and Setup

## What is PayFlow?

PayFlow is a production-grade payment gateway built with microservices architecture. It processes card payments, UPI transactions, and net banking — handling everything from merchant onboarding to settlement payouts.

Think of it as building a simplified version of Razorpay or Stripe from scratch.

## Motivation

- Learn microservices architecture with a real-world, complex domain
- Understand payment processing: authorization, capture, settlement
- Practice distributed systems concepts: event streaming, idempotency, circuit breakers
- Build a portfolio project that demonstrates system design expertise

## Tech Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Backend | Java 17 + Spring Boot 3.x | Microservices framework |
| API Gateway | Spring Cloud Gateway | Routing, rate limiting, auth |
| Database | PostgreSQL 15 | Primary data store (per-service) |
| Cache | Redis 7 | Idempotency, rate limiting, sessions |
| Messaging | Apache Kafka | Async event streaming |
| NoSQL | DynamoDB | Webhook delivery tracking |
| Protocol | ISO 8583 via Netty | Bank communication |
| Frontend | React 18 + TypeScript | Merchant dashboard |
| Styling | Tailwind CSS | Utility-first CSS |
| Build | Maven (multi-module) | Dependency management |
| Containers | Docker + Docker Compose | Local orchestration |
| CI/CD | GitHub Actions | Automated pipelines |
| Cloud | AWS (Free Tier) | Production deployment |
| Monitoring | Spring Actuator + CloudWatch | Observability |

## Environment Setup Checklist

```
[ ] Java 17 (Amazon Corretto or OpenJDK)
[ ] Apache Maven 3.9+
[ ] Docker Desktop 4.x + Docker Compose v2
[ ] Node.js 18 LTS + npm 9
[ ] PostgreSQL 15 (or use Docker)
[ ] Redis 7 (or use Docker)
[ ] Git 2.x
[ ] IDE: IntelliJ IDEA (recommended) or VS Code
[ ] Postman or Insomnia (API testing)
[ ] AWS CLI v2 (for deployment phase)
```

### Java 17 Setup

```bash
# Verify installation
java --version   # Should show 17.x
mvn --version    # Should show 3.9.x
```

### Docker Verification

```bash
docker --version          # 24.x+
docker compose version    # v2.x
```

### Node.js Setup

```bash
node --version   # 18.x
npm --version    # 9.x
```

## Project Folder Structure

```
payflow-payment-gateway/
├── backend/
│   ├── api-gateway/            # Spring Cloud Gateway
│   ├── identity-service/       # Authentication & JWT
│   ├── merchant-service/       # Merchant onboarding
│   ├── payment-service/        # Payment orchestration
│   ├── routing-service/        # ISO 8583 + fraud check
│   ├── bank-simulator/         # Mock bank (Netty TCP)
│   ├── settlement-service/     # Batch settlement
│   ├── webhook-service/        # Webhook delivery
│   └── notification-service/   # Email/SMS notifications
├── frontend/
│   ├── merchant-dashboard/     # React merchant portal
│   └── hosted-checkout/        # Payment page (cards/UPI)
├── docker/
│   ├── docker-compose.yml      # Full stack orchestration
│   └── init-scripts/           # DB initialization
├── docs/                       # This documentation
└── .github/workflows/          # CI/CD pipelines
```

## Service Overview

| Service | Real-World Analogy | What It Does |
|---------|-------------------|--------------|
| API Gateway | Security guard at building entrance | Routes requests, validates JWT/API keys, rate limits |
| Identity Service | ID card issuer | User registration, login, JWT tokens |
| Merchant Service | Bank relationship manager | Onboards merchants, manages API keys, fee config |
| Payment Service | Cashier at checkout counter | Creates orders, manages payment state machine |
| Routing Service | Postal sorting office | Routes to correct bank, fraud checks, ISO 8583 |
| Bank Simulator | The actual bank vault | Simulates card network responses (approve/decline) |
| Settlement Service | Accountant doing end-of-day | Calculates fees, batches payouts to merchants |
| Webhook Service | Delivery notification system | Notifies merchants of payment events |
| Notification Service | SMS/Email sender | Sends receipts and alerts to end users |

## How a Payment Works End-to-End

```
┌──────────────────────────────────────────────────────────────────┐
│                    PAYMENT FLOW (Happy Path)                       │
└──────────────────────────────────────────────────────────────────┘

1. Customer clicks "Pay ₹500" on merchant's website
2. Merchant's frontend calls PayFlow API with API key
3. API Gateway validates API key → routes to Payment Service
4. Payment Service creates ORDER (status: CREATED)
5. Customer is redirected to Hosted Checkout page
6. Customer enters card details → submits
7. Payment Service updates status → PROCESSING
8. Routing Service runs fraud checks (velocity, amount limits)
9. Routing Service builds ISO 8583 message
10. ISO 8583 sent over TCP to Bank Simulator (or real acquirer)
11. Bank responds: APPROVED / DECLINED
12. Payment Service updates status → AUTHORIZED or FAILED
13. Capture request → status: CAPTURED
14. Kafka event: "payment.captured" published
15. Webhook Service sends POST to merchant's callback URL
16. Notification Service sends receipt email to customer
17. Settlement Service (daily batch) calculates:
    - Transaction: ₹500
    - MDR (2%): ₹10
    - GST on MDR (18%): ₹1.80
    - Merchant receives: ₹488.20
18. Payout initiated to merchant's bank account
```

## Payment State Machine

```
  CREATED → PROCESSING → AUTHORIZED → CAPTURED → SETTLED
                │              │           │
                ▼              ▼           ▼
             FAILED        VOIDED      REFUNDED
                                          │
                                          ▼
                                    PARTIALLY_REFUNDED
```

## Quick Start (After Setup)

```bash
# Clone the repository
git clone https://github.com/your-username/payflow-payment-gateway.git
cd payflow-payment-gateway

# Start infrastructure (Postgres, Redis, Kafka, Zookeeper)
docker compose -f docker/docker-compose.yml up -d

# Build all backend services
cd backend && mvn clean install -DskipTests

# Run services individually or use docker compose for all
docker compose -f docker/docker-compose-services.yml up -d

# Start frontend
cd frontend/merchant-dashboard && npm install && npm run dev
```

## What You'll Learn

| Concept | Where It's Applied |
|---------|-------------------|
| Microservices | Service decomposition, bounded contexts |
| Event-Driven Architecture | Kafka for async communication |
| API Design | RESTful APIs, versioning, pagination |
| Security | JWT, API keys, HMAC signatures, rate limiting |
| Database per Service | PostgreSQL isolation per microservice |
| Distributed Transactions | Saga pattern via events |
| Idempotency | Redis-backed idempotency keys |
| Circuit Breaker | Resilience4j for fault tolerance |
| Batch Processing | Spring Batch for settlements |
| Protocol Implementation | ISO 8583 over TCP with Netty |
| Containerization | Docker multi-stage builds |
| CI/CD | GitHub Actions pipelines |
| Cloud Deployment | AWS free tier (EC2, RDS, S3, CloudFront) |
| Monitoring | Actuator metrics, CloudWatch |
