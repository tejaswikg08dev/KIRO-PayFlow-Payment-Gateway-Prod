# Phase 4 · Part 14 — Docker & Containerization

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Infrastructure & Deployment |
| **Part** | 14 — Docker & Containerization |
| **Previous** | [Part 13 — Notification Service](./phase4-part13-notification-service.md) |
| **Next** | [Part 15A — Frontend Setup](./phase4-part15a-frontend-setup.md) |
| **Time** | ~3 hours |
| **Difficulty** | ★★★☆☆ (Intermediate) |
| **Prerequisites** | Docker basics, Linux commands, YAML |
| **What You'll Build** | Complete containerized environment with all services and infrastructure |
| **Git Commit** | `feat(infra): add Docker multi-stage builds and full docker-compose` |

---

## Table of Contents

1. [Multi-Stage Dockerfile](#1-multi-stage-dockerfile)
2. [docker-compose.yml Line-by-Line](#2-docker-composeyml)
3. [docker-compose.full.yml](#3-docker-composefullyml)
4. [init-db.sql](#4-init-dbsql)
5. [init-localstack.sh](#5-init-localstacksh)
6. [.env.example](#6-envexample)
7. [Networks — Isolation Explained](#7-networks)
8. [Docker Commands Reference](#8-docker-commands)
9. [Verifying Service Health](#9-verifying-health)
10. [What You Learned](#10-what-you-learned)
11. [Common Errors & Fixes](#11-common-errors--fixes)
12. [Git Commit](#12-git-commit)

---

## What You'll Learn

- How multi-stage Docker builds reduce image size by 80%+
- How docker-compose orchestrates multiple services with proper startup order
- How network isolation prevents unauthorized cross-service communication
- How health checks ensure services are truly ready (not just running)
- How init scripts set up databases and AWS resources automatically
- How to debug containerized services effectively

---

## 1. Multi-Stage Dockerfile

Every Java service uses the same Dockerfile pattern with two stages.

```dockerfile
# ════════════════════════════════════════════════════════════════
# Stage 1: BUILD (heavy — downloads dependencies, compiles code)
# ════════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jdk-alpine AS builder

# WHY: Set working directory inside the container
WORKDIR /app

# WHY: Copy pom.xml FIRST (before source code)
# Docker caches layers. If pom.xml doesn't change, dependencies
# are cached and not re-downloaded on every code change.
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# WHY: Download dependencies in a separate layer (cached!)
# -DskipTests: Don't run tests during build (CI handles that)
# -Dmaven.main.skip: Don't compile yet, just download deps
RUN chmod +x mvnw && ./mvnw dependency:go-offline -DskipTests

# NOW copy source code (this layer changes frequently)
COPY src ./src

# WHY: Build the JAR (skip tests — already ran in CI)
RUN ./mvnw package -DskipTests -Dspring-boot.build-image.skip=true

# ════════════════════════════════════════════════════════════════
# Stage 2: RUNTIME (slim — only JRE + our JAR)
# ════════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine

# WHY: Non-root user for security
# If container is compromised, attacker doesn't get root access
RUN addgroup -S payflow && adduser -S payflow -G payflow

WORKDIR /app

# WHY: Copy ONLY the built JAR from stage 1
# This means the final image doesn't have Maven, source code, or build tools
COPY --from=builder /app/target/*.jar app.jar

# WHY: Change ownership to non-root user
RUN chown payflow:payflow app.jar
USER payflow

# WHY: Expose port (documentation — docker-compose still needs ports mapping)
EXPOSE 8080

# WHY: Health check — Docker can detect if JVM crashed
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# WHY: Use exec form (not shell form) — signals are forwarded correctly
# java -XX:+UseContainerSupport: Respect container memory limits
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
```

### Size Comparison

```
┌──────────────────────────────────────────────────────────────┐
│ Without multi-stage:                                         │
│   eclipse-temurin:21-jdk-alpine (base)     ~350 MB          │
│   + Maven cache                            ~200 MB          │
│   + Source code                            ~10 MB           │
│   + Compiled JAR                           ~50 MB           │
│   ═══════════════════════════════════════════════            │
│   TOTAL:                                   ~610 MB ❌       │
│                                                              │
│ With multi-stage:                                            │
│   eclipse-temurin:21-jre-alpine (base)     ~150 MB          │
│   + Compiled JAR only                      ~50 MB           │
│   ═══════════════════════════════════════════════            │
│   TOTAL:                                   ~200 MB ✅       │
│                                                              │
│   Savings: 67% smaller image! 🎉                            │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. docker-compose.yml

The core services needed for development.

```yaml
# docker-compose.yml — Development environment
services:
  # ═══════════════ INFRASTRUCTURE ═══════════════

  postgres:
    image: postgres:16-alpine
    # WHY alpine: Smaller image (70MB vs 380MB for full postgres)
    container_name: payflow-postgres
    environment:
      POSTGRES_USER: ${DB_USER:-payflow}
      POSTGRES_PASSWORD: ${DB_PASSWORD:-payflow123}
      POSTGRES_DB: payflow_main
    ports:
      - "5432:5432"
    volumes:
      # WHY named volume: Data persists across container restarts
      - postgres-data:/var/lib/postgresql/data
      # WHY init script: Creates all databases on first start
      - ./infra/init-db.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      # WHY pg_isready: Checks if PostgreSQL is accepting connections
      test: ["CMD-SHELL", "pg_isready -U payflow"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - data-net

  redis:
    image: redis:7-alpine
    container_name: payflow-redis
    ports:
      - "6379:6379"
    # WHY --maxmemory: Prevent Redis from using all available RAM
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - data-net

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: payflow-kafka
    # WHY no separate Zookeeper: KRaft mode (Kafka 3.3+) eliminates Zookeeper dependency
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,HOST://localhost:9092
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093,HOST://0.0.0.0:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"
    ports:
      - "9092:9092"    # Host access
      - "29092:29092"  # Inter-container access
    volumes:
      - kafka-data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "localhost:9092"]
      interval: 15s
      timeout: 10s
      retries: 5
      start_period: 30s
    networks:
      - backend-net

  localstack:
    image: localstack/localstack:3.0
    container_name: payflow-localstack
    ports:
      - "4566:4566"
    environment:
      - SERVICES=ses,sns,dynamodb
      - DEFAULT_REGION=ap-south-1
      - DEBUG=0
    volumes:
      - ./infra/init-localstack.sh:/etc/localstack/init/ready.d/init.sh
      - localstack-data:/var/lib/localstack
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4566/_localstack/health"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - backend-net

# ═══════════════ VOLUMES ═══════════════
volumes:
  postgres-data:
    # WHY named volumes: Persist data across docker-compose down/up cycles
    driver: local
  redis-data:
    driver: local
  kafka-data:
    driver: local
  localstack-data:
    driver: local

# ═══════════════ NETWORKS ═══════════════
networks:
  frontend-net:
    driver: bridge
  backend-net:
    driver: bridge
  data-net:
    driver: bridge
```

---

## 3. docker-compose.full.yml

All services including application microservices.

```yaml
# docker-compose.full.yml — Complete system (infrastructure + services)
# Usage: docker-compose -f docker-compose.yml -f docker-compose.full.yml up

services:
  # ═══════════════ APPLICATION SERVICES ═══════════════

  api-gateway:
    build:
      context: ./backend
      dockerfile: api-gateway/Dockerfile
    container_name: payflow-api-gateway
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - REDIS_HOST=redis
      - REDIS_PORT=6379
    depends_on:
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      start_period: 40s
      retries: 3
    networks:
      - frontend-net
      - backend-net

  merchant-service:
    build:
      context: ./backend
      dockerfile: merchant-service/Dockerfile
    container_name: payflow-merchant-service
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - DB_URL=jdbc:postgresql://postgres:5432/payflow_merchant
      - DB_USER=${DB_USER:-payflow}
      - DB_PASSWORD=${DB_PASSWORD:-payflow123}
      - REDIS_HOST=redis
      - KAFKA_BOOTSTRAP_SERVERS=kafka:29092
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8081/actuator/health"]
      interval: 30s
      timeout: 10s
      start_period: 40s
    networks:
      - backend-net
      - data-net

  payment-service:
    build:
      context: ./backend
      dockerfile: payment-service/Dockerfile
    container_name: payflow-payment-service
    ports:
      - "8082:8082"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - DB_URL=jdbc:postgresql://postgres:5432/payflow_payment
      - DB_USER=${DB_USER:-payflow}
      - DB_PASSWORD=${DB_PASSWORD:-payflow123}
      - REDIS_HOST=redis
      - KAFKA_BOOTSTRAP_SERVERS=kafka:29092
      - ROUTING_SERVICE_URL=http://routing-service:8083
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    networks:
      - backend-net
      - data-net

  routing-service:
    build:
      context: ./backend
      dockerfile: routing-service/Dockerfile
    container_name: payflow-routing-service
    ports:
      - "8083:8083"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - REDIS_HOST=redis
      - KAFKA_BOOTSTRAP_SERVERS=kafka:29092
      - BANK_HOST=bank-simulator
      - BANK_PORT=9090
    depends_on:
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
      bank-simulator:
        condition: service_healthy
    networks:
      - backend-net
      - data-net

  bank-simulator:
    build:
      context: ./backend
      dockerfile: bank-simulator/Dockerfile
    container_name: payflow-bank-simulator
    ports:
      - "8085:8085"   # HTTP management port
      - "9090:9090"   # TCP ISO 8583 port
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SIMULATOR_SUCCESS_RATE_PERCENT=85
      - SIMULATOR_MIN_LATENCY_MS=200
      - SIMULATOR_MAX_LATENCY_MS=1500
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8085/actuator/health"]
      interval: 30s
      timeout: 10s
      start_period: 20s
    networks:
      - backend-net

  settlement-service:
    build:
      context: ./backend
      dockerfile: settlement-service/Dockerfile
    container_name: payflow-settlement-service
    ports:
      - "8086:8086"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - DB_URL=jdbc:postgresql://postgres:5432/payflow_settlement
      - DB_USER=${DB_USER:-payflow}
      - DB_PASSWORD=${DB_PASSWORD:-payflow123}
      - PAYMENT_SERVICE_URL=http://payment-service:8082
    depends_on:
      postgres:
        condition: service_healthy
      payment-service:
        condition: service_healthy
    networks:
      - backend-net
      - data-net

  webhook-service:
    build:
      context: ./backend
      dockerfile: webhook-service/Dockerfile
    container_name: payflow-webhook-service
    ports:
      - "8087:8087"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - KAFKA_BOOTSTRAP_SERVERS=kafka:29092
      - AWS_DYNAMODB_ENDPOINT=http://localstack:4566
      - AWS_REGION=ap-south-1
    depends_on:
      kafka:
        condition: service_healthy
      localstack:
        condition: service_healthy
    networks:
      - backend-net

  notification-service:
    build:
      context: ./backend
      dockerfile: notification-service/Dockerfile
    container_name: payflow-notification-service
    ports:
      - "8088:8088"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - KAFKA_BOOTSTRAP_SERVERS=kafka:29092
      - AWS_SES_ENDPOINT=http://localstack:4566
      - AWS_SNS_ENDPOINT=http://localstack:4566
      - AWS_REGION=ap-south-1
    depends_on:
      kafka:
        condition: service_healthy
      localstack:
        condition: service_healthy
    networks:
      - backend-net

  # ═══════════════ FRONTEND ═══════════════

  merchant-portal:
    build:
      context: ./frontend/merchant-portal
      dockerfile: Dockerfile
    container_name: payflow-merchant-portal
    ports:
      - "3000:80"
    environment:
      - VITE_API_URL=http://localhost:8080
    depends_on:
      api-gateway:
        condition: service_healthy
    networks:
      - frontend-net
```

---

## 4. init-db.sql

```sql
-- init-db.sql — Creates all databases on first PostgreSQL start
-- WHY: Each microservice has its own database (database-per-service pattern)
-- This ensures data isolation and allows independent schema evolution.

-- Database for Merchant Service
CREATE DATABASE payflow_merchant;

-- Database for Payment Service (transactions, refunds)
CREATE DATABASE payflow_payment;

-- Database for Settlement Service (batches, payouts)
CREATE DATABASE payflow_settlement;

-- Database for Ledger Service (double-entry accounting)
CREATE DATABASE payflow_ledger;

-- WHY: Grant all privileges to the payflow user on each database
-- In production, each service would have its OWN database user with limited permissions
GRANT ALL PRIVILEGES ON DATABASE payflow_merchant TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_payment TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_settlement TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_ledger TO payflow;

-- WHY: Log to confirm initialization
\echo 'PayFlow databases created successfully ✅'
```

---

## 5. init-localstack.sh

```bash
#!/bin/bash
# init-localstack.sh — Initialize LocalStack AWS resources
# This runs automatically when LocalStack container starts

set -e  # WHY: Exit immediately on error (don't continue with broken setup)

echo "🚀 Initializing LocalStack for PayFlow..."

# ═══════════════ DynamoDB Tables ═══════════════

# WHY: Webhook delivery tracking table
awslocal dynamodb create-table \
    --table-name webhook_deliveries \
    --attribute-definitions \
        AttributeName=merchantId,AttributeType=S \
        AttributeName=deliveryId,AttributeType=S \
    --key-schema \
        AttributeName=merchantId,KeyType=HASH \
        AttributeName=deliveryId,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --region ap-south-1

echo "  ✅ DynamoDB table: webhook_deliveries"

# WHY: Idempotency keys table (for distributed deduplication)
awslocal dynamodb create-table \
    --table-name idempotency_keys \
    --attribute-definitions \
        AttributeName=idempotencyKey,AttributeType=S \
    --key-schema \
        AttributeName=idempotencyKey,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region ap-south-1

echo "  ✅ DynamoDB table: idempotency_keys"

# ═══════════════ SES (Email) ═══════════════

# WHY: Verify sender email (required even in LocalStack for API compatibility)
awslocal ses verify-email-identity \
    --email-address noreply@payflow.com \
    --region ap-south-1

echo "  ✅ SES verified: noreply@payflow.com"

# ═══════════════ SNS (SMS) ═══════════════

# WHY: Create SNS topic for payment alerts
awslocal sns create-topic \
    --name payment-alerts \
    --region ap-south-1

echo "  ✅ SNS topic: payment-alerts"

echo ""
echo "🎉 LocalStack initialization complete!"
echo "   DynamoDB: 2 tables"
echo "   SES: 1 verified email"
echo "   SNS: 1 topic"
```

---

## 6. .env.example

```bash
# .env.example — Copy to .env and fill in values
# Usage: cp .env.example .env

# ═══════════════ DATABASE ═══════════════
DB_USER=payflow
DB_PASSWORD=payflow123
# WHY: Change this in production! Never use default passwords.

# ═══════════════ KAFKA ═══════════════
KAFKA_BOOTSTRAP_SERVERS=kafka:29092
# WHY kafka:29092: Internal Docker network address
# From host machine: use localhost:9092

# ═══════════════ REDIS ═══════════════
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=
# WHY empty password: Acceptable for local dev. Set in production!

# ═══════════════ AWS (LocalStack) ═══════════════
AWS_REGION=ap-south-1
AWS_ACCESS_KEY=test
AWS_SECRET_KEY=test
# WHY "test": LocalStack accepts any credentials locally

# ═══════════════ JWT ═══════════════
JWT_SECRET=my-super-secret-key-change-in-production-at-least-256-bits
JWT_EXPIRATION_MS=3600000
# WHY 3600000: 1 hour in milliseconds

# ═══════════════ API GATEWAY ═══════════════
RATE_LIMIT_REQUESTS_PER_SECOND=10
RATE_LIMIT_BURST=20

# ═══════════════ BANK SIMULATOR ═══════════════
SIMULATOR_SUCCESS_RATE=85
SIMULATOR_MIN_LATENCY_MS=200
SIMULATOR_MAX_LATENCY_MS=2000

# ═══════════════ SETTLEMENT ═══════════════
SETTLEMENT_MDR_PERCENT=2.0
SETTLEMENT_CRON=0 30 0 * * *
# WHY: Runs at 00:30 daily (midnight + 30 min buffer)

# ═══════════════ FRONTEND ═══════════════
VITE_API_URL=http://localhost:8080
# WHY: Frontend talks to API Gateway on port 8080
```

---

## 7. Networks

```
┌───────────────────────────────────────────────────────────────────────────┐
│                    DOCKER NETWORK ISOLATION                                │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  WHY separate networks:                                                    │
│  - frontend-net: Only API Gateway exposed to internet/frontend             │
│  - backend-net: Services communicate internally (not from outside)         │
│  - data-net: Only services that NEED database access can reach it         │
│                                                                            │
│  This mimics production network architecture (VPCs, subnets, security groups)│
│                                                                            │
│  ┌─────────────────── frontend-net ───────────────────────┐               │
│  │                                                         │               │
│  │  ┌───────────────┐        ┌─────────────────┐         │               │
│  │  │ Merchant       │        │  API Gateway    │         │               │
│  │  │ Portal (:3000) │───────→│  (:8080)        │         │               │
│  │  └───────────────┘        └────────┬────────┘         │               │
│  │                                     │                   │               │
│  └─────────────────────────────────────┼───────────────────┘               │
│                                        │                                    │
│  ┌─────────────────── backend-net ─────┼───────────────────┐               │
│  │                                     │                    │               │
│  │  ┌───────────────┐  ┌──────────────┴──┐  ┌──────────┐ │               │
│  │  │ Payment       │  │ Merchant         │  │ Routing  │ │               │
│  │  │ Service       │  │ Service          │  │ Service  │ │               │
│  │  │ (:8082)       │  │ (:8081)          │  │ (:8083)  │ │               │
│  │  └───────┬───────┘  └─────────┬───────┘  └────┬─────┘ │               │
│  │          │                     │                │        │               │
│  │  ┌───────┴───────┐  ┌─────────┴────┐   ┌─────┴──────┐│               │
│  │  │ Settlement    │  │ Webhook      │   │ Bank       ││               │
│  │  │ Service       │  │ Service      │   │ Simulator  ││               │
│  │  │ (:8086)       │  │ (:8087)      │   │ (:9090)    ││               │
│  │  └───────┬───────┘  └──────────────┘   └────────────┘│               │
│  │          │                                             │               │
│  │  ┌───────┴─────────────┐  ┌───────────────┐          │               │
│  │  │ Notification         │  │ Kafka         │          │               │
│  │  │ Service (:8088)      │  │ (:29092)      │          │               │
│  │  └─────────────────────┘  └───────────────┘          │               │
│  │                                                        │               │
│  └────────────────────────────┬───────────────────────────┘               │
│                               │                                            │
│  ┌─────────────────── data-net┼───────────────────────────┐               │
│  │                            │                            │               │
│  │  ┌────────────────┐  ┌────┴──────────┐  ┌──────────┐ │               │
│  │  │ PostgreSQL     │  │ Redis         │  │ LocalStack│ │               │
│  │  │ (:5432)        │  │ (:6379)       │  │ (:4566)  │ │               │
│  │  └────────────────┘  └───────────────┘  └──────────┘ │               │
│  │                                                        │               │
│  └────────────────────────────────────────────────────────┘               │
│                                                                            │
│  KEY INSIGHT:                                                              │
│  • Merchant Portal can ONLY reach API Gateway (not databases!)            │
│  • Bank Simulator is ONLY reachable from Routing Service                  │
│  • PostgreSQL is ONLY reachable from services on data-net                 │
│                                                                            │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Docker Commands

### Essential Commands

| Command | Purpose | When to Use |
|---------|---------|-------------|
| `docker-compose up -d` | Start infrastructure only | Beginning of development |
| `docker-compose -f docker-compose.yml -f docker-compose.full.yml up -d` | Start everything | Full system testing |
| `docker-compose down` | Stop all containers | End of work |
| `docker-compose down -v` | Stop + delete volumes (data) | Fresh start |
| `docker-compose logs -f kafka` | Stream logs for specific service | Debugging |
| `docker-compose ps` | List running containers + ports | Check status |
| `docker-compose exec postgres psql -U payflow` | Open database shell | Manual queries |
| `docker-compose restart payment-service` | Restart one service | After code changes |
| `docker-compose build --no-cache payment-service` | Rebuild without cache | When Dockerfile changes |

### Build and Run Workflow

```bash
# 1. First time setup: start infrastructure
docker-compose up -d
# Starts: postgres, redis, kafka, localstack

# 2. Wait for all services to be healthy
docker-compose ps
# All should show "healthy"

# 3. Build and start all application services
docker-compose -f docker-compose.yml -f docker-compose.full.yml up -d --build

# 4. Watch logs during startup
docker-compose logs -f --tail=50

# 5. Check all services are healthy
docker-compose ps

# 6. When done developing
docker-compose down

# 7. Nuclear option — remove everything including data
docker-compose down -v --rmi local
```

### Useful Debugging Commands

```bash
# Enter a running container's shell
docker-compose exec payment-service sh

# Check what's on a network
docker network inspect payflow-payment-gateway_backend-net

# Check container resource usage
docker stats

# View detailed container info
docker inspect payflow-payment-service

# Copy file from container to host
docker cp payflow-payment-service:/app/app.jar ./debug-app.jar

# Check if a port is being used
docker-compose exec payment-service wget -qO- http://postgres:5432 || echo "Can't reach"
```

---

## 9. Verifying Health

### Health Check Script

```bash
#!/bin/bash
# verify-health.sh — Check all services are running and healthy

echo "🔍 Checking PayFlow service health..."
echo ""

services=(
    "API Gateway|http://localhost:8080/actuator/health"
    "Merchant Service|http://localhost:8081/actuator/health"
    "Payment Service|http://localhost:8082/actuator/health"
    "Routing Service|http://localhost:8083/actuator/health"
    "Bank Simulator|http://localhost:8085/actuator/health"
    "Settlement Service|http://localhost:8086/actuator/health"
    "Webhook Service|http://localhost:8087/actuator/health"
    "Notification Service|http://localhost:8088/actuator/health"
)

all_healthy=true

for service in "${services[@]}"; do
    IFS='|' read -r name url <<< "$service"
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null)
    if [ "$status" == "200" ]; then
        echo "  ✅ $name — healthy"
    else
        echo "  ❌ $name — unhealthy (HTTP $status)"
        all_healthy=false
    fi
done

echo ""

# Infrastructure checks
echo "🏗️ Infrastructure:"
docker-compose exec -T postgres pg_isready -U payflow > /dev/null 2>&1 && echo "  ✅ PostgreSQL" || echo "  ❌ PostgreSQL"
docker-compose exec -T redis redis-cli ping > /dev/null 2>&1 && echo "  ✅ Redis" || echo "  ❌ Redis"
curl -s http://localhost:4566/_localstack/health > /dev/null 2>&1 && echo "  ✅ LocalStack" || echo "  ❌ LocalStack"

echo ""
if [ "$all_healthy" = true ]; then
    echo "🎉 All services are healthy!"
else
    echo "⚠️ Some services are unhealthy. Check logs: docker-compose logs -f <service>"
fi
```

---

## 10. What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Multi-stage Dockerfile | Build in one stage, run in another (67% smaller images) |
| 2 | Layer caching | Copy pom.xml before source = dependencies cached |
| 3 | Non-root user | Security: compromised container ≠ root access |
| 4 | docker-compose depends_on | `condition: service_healthy` ensures startup order |
| 5 | Health checks | `HEALTHCHECK` in Dockerfile + docker-compose verify readiness |
| 6 | Named volumes | Data persists across container restarts |
| 7 | Network isolation | 3 networks separate frontend, backend, and data tiers |
| 8 | Environment variables | `.env` file + `${VAR:-default}` pattern |
| 9 | Init scripts | SQL and shell scripts run on first container start |
| 10 | Docker debugging | `exec`, `logs`, `inspect`, `stats` for troubleshooting |

---

## 11. Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `port 5432 already in use` | Local PostgreSQL running | Stop local PG: `brew services stop postgresql` |
| `depends_on: service is unhealthy` | Dependency didn't start in time | Increase `start_period` in health check |
| `kafka: connection refused` | Kafka not ready yet | Wait longer or increase `start_period` |
| `OOM killed` | Container exceeded memory limit | Add `deploy.resources.limits.memory` |
| `Permission denied` on volume | Linux file ownership mismatch | Use `user: "${UID}:${GID}"` or fix volume permissions |
| Build fails at `mvnw dependency:go-offline` | Network issue during build | Check internet, retry with `--no-cache` |
| `Cannot connect to the Docker daemon` | Docker Desktop not running | Start Docker Desktop |
| Service starts but unhealthy | Application error during startup | Check logs: `docker-compose logs <service>` |
| Init scripts not running | Volume already has data | `docker-compose down -v` then `up` again |
| Frontend can't reach API Gateway | Different Docker networks | Ensure both on `frontend-net` |

---

## 12. Git Commit

```bash
# Stage Docker and infrastructure files
git add Dockerfile
git add docker-compose.yml
git add docker-compose.full.yml
git add infra/
git add .env.example

# Commit
git commit -m "feat(infra): add Docker multi-stage builds and full docker-compose

- Multi-stage Dockerfile: build (JDK) + runtime (JRE) = 67% smaller images
- docker-compose.yml: infrastructure services (postgres, redis, kafka, localstack)
- docker-compose.full.yml: all application services with health checks
- init-db.sql: creates 4 databases (merchant, payment, settlement, ledger)
- init-localstack.sh: DynamoDB tables + SES identity + SNS topic
- .env.example: documented environment variables
- 3 Docker networks: frontend-net, backend-net, data-net (isolation)
- verify-health.sh: script to check all services are running
- Non-root container user for security"

# Push
git push origin feature/phase4-docker
```

---

## Document Index

| # | Document | Status |
|---|----------|--------|
| 01 | Project Overview & Architecture | ✅ |
| 02 | Development Environment Setup | ✅ |
| 03 | Merchant Service (CRUD + Auth) | ✅ |
| 04 | Payment Service (Core Processing) | ✅ |
| 05 | API Gateway (Routing + Security) | ✅ |
| 06 | Kafka Event Streaming | ✅ |
| 07 | Redis Caching & Idempotency | ✅ |
| 08 | Ledger Service (Double-Entry) | ✅ |
| 09a | ISO 8583 Message Parsing | ✅ |
| 09b | Netty TCP Client | ✅ |
| 09c | Fraud Detection & Smart Routing | ✅ |
| 10 | Bank Simulator | ✅ |
| 11 | Settlement Service | ✅ |
| 12 | Webhook Service | ✅ |
| 13 | Notification Service | ✅ |
| **14** | **Docker & Containerization** | **📍 Current** |
| 15a | Frontend Setup | 🔜 Next |
| 15b | Frontend Features | ⬜ |

---

## Next Steps

In **Part 15A**, we'll set up the **Merchant Portal frontend**:
- Vite + React 18 + TypeScript project
- Tailwind CSS for styling
- React Router v6 for navigation
- TanStack Query for server state management
- JWT authentication context

---

[← Previous: Part 13 — Notification Service](./phase4-part13-notification-service.md) | [Next: Part 15A — Frontend Setup →](./phase4-part15a-frontend-setup.md)
