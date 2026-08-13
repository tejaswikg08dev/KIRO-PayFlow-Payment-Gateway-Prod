# PayFlow Payment Gateway

A production-ready payment gateway platform built with **11 Java Spring Boot microservices**, **ISO 8583 protocol** for bank communication, and **AI-powered fraud detection**.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         FRONTEND LAYER                                │
│   ┌──────────────────┐         ┌──────────────────┐                 │
│   │ Merchant Portal  │         │ Hosted Checkout   │                 │
│   │ (React + TS)     │         │ (React + TS)      │                 │
│   │ Port: 3000       │         │ Port: 3001        │                 │
│   └────────┬─────────┘         └────────┬──────────┘                │
└────────────┼────────────────────────────┼───────────────────────────┘
             │                            │
┌────────────▼────────────────────────────▼───────────────────────────┐
│                      API GATEWAY (Port: 8080)                        │
│        JWT Validation │ Rate Limiting │ Request Routing               │
└────────────┬────────────────────────────────────────────────────────┘
             │
┌────────────▼────────────────────────────────────────────────────────┐
│                     BUSINESS SERVICES LAYER                           │
│                                                                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐  │
│  │  Identity   │ │  Merchant   │ │  Payment    │ │  Routing    │  │
│  │  Service    │ │  Service    │ │  Service    │ │  Service    │  │
│  │  :8081      │ │  :8082      │ │  :8083      │ │  :8084      │  │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘  │
│                                                                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐  │
│  │ Settlement  │ │  Webhook    │ │Notification │ │    Bank     │  │
│  │  Service    │ │  Service    │ │  Service    │ │  Simulator  │  │
│  │  :8085      │ │  :8086      │ │  :8087      │ │  :9000      │  │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
             │                            │
┌────────────▼────────────────────────────▼───────────────────────────┐
│                       DATA & MESSAGING LAYER                          │
│                                                                       │
│  ┌──────────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐  │
│  │ PostgreSQL   │  │  Redis   │  │  Kafka   │  │  DynamoDB     │  │
│  │ (4 databases)│  │  :6379   │  │  :9092   │  │  (LocalStack) │  │
│  └──────────────┘  └──────────┘  └──────────┘  └───────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

## Tech Stack

| Category | Technology | Version |
|----------|-----------|---------|
| Language | Java | 17 (LTS) |
| Framework | Spring Boot | 3.2.5 |
| Cloud | Spring Cloud | 2023.0.x |
| Frontend | React + TypeScript | 18.x + 5.x |
| Database | PostgreSQL | 15 |
| Cache | Redis | 7 |
| Messaging | Apache Kafka | 3.7.x |
| Protocol | ISO 8583 (Netty) | Binary TCP |
| Auth | JWT (jjwt) | 0.12.x |

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.9+
- Node.js 20+
- Docker Desktop

### Start Infrastructure
```bash
cd infra/docker
docker compose up -d
```

### Build Backend
```bash
cd backend
mvn clean install -DskipTests
```

### Run Services (in order)
```bash
mvn spring-boot:run -pl service-registry
mvn spring-boot:run -pl config-server
mvn spring-boot:run -pl api-gateway
mvn spring-boot:run -pl identity-service
mvn spring-boot:run -pl merchant-service
mvn spring-boot:run -pl payment-service
mvn spring-boot:run -pl routing-service
mvn spring-boot:run -pl settlement-service
mvn spring-boot:run -pl webhook-service
mvn spring-boot:run -pl notification-service
mvn spring-boot:run -pl bank-simulator
```

### Start Frontend
```bash
cd frontend/merchant-portal
npm install && npm run dev

cd frontend/hosted-checkout
npm install && npm run dev
```

## Services

| Service | Port | Responsibility |
|---------|------|---------------|
| Service Registry | 8761 | Service discovery (Eureka) |
| Config Server | 8888 | Centralized configuration |
| API Gateway | 8080 | Routing, rate limiting, auth |
| Identity Service | 8081 | Registration, login, JWT |
| Merchant Service | 8082 | Onboarding, API keys |
| Payment Service | 8083 | Orders, payments, refunds |
| Routing Service | 8084 | ISO 8583, fraud, smart routing |
| Settlement Service | 8085 | Daily batch settlements |
| Webhook Service | 8086 | Event delivery with retry |
| Notification Service | 8087 | Email/SMS notifications |
| Bank Simulator | 9000 | Mock bank for testing |

## Documentation

See the `/docs` folder for comprehensive documentation covering system design, architecture, coding guides, and deployment instructions.

## API Routes (via Gateway :8080)

| Path | Service | Example |
|------|---------|---------|
| `/v1/auth/**` | Identity Service | `POST /v1/auth/register` |
| `/v1/merchants/**` | Merchant Service | `GET /v1/merchants/{id}` |
| `/v1/orders/**` | Payment Service | `POST /v1/orders` |
| `/v1/payments/**` | Payment Service | `POST /v1/payments/authorize` |
| `/v1/refunds/**` | Payment Service | `POST /v1/refunds` |
| `/v1/settlements/**` | Settlement Service | `GET /v1/settlements` |

## Eureka Service Discovery

All services register with Eureka using IP-based addressing (`prefer-ip-address: true`) and a standardized instance ID (`service-name:port`).

Dashboard: http://localhost:8761

## License

MIT
