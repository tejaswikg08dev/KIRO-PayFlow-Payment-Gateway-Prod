# PayFlow Payment Gateway — Developer Setup Guide

---

## Prerequisites

| Tool | Version | Check Command | Download |
|------|---------|---------------|----------|
| Java JDK | 17+ | `java -version` | [Adoptium](https://adoptium.net/) |
| Maven | 3.9+ | `mvn -version` | [Maven](https://maven.apache.org/download.cgi) |
| Node.js | 20+ | `node --version` | [Node.js](https://nodejs.org/) |
| npm | 10+ | `npm --version` | Comes with Node.js |
| Docker Desktop | 4.x+ | `docker --version` | [Docker](https://www.docker.com/products/docker-desktop/) |
| Git | 2.x+ | `git --version` | [Git](https://git-scm.com/) |

### Docker Settings (Important)

Open Docker Desktop → Settings → Resources:
- **Memory**: 8 GB minimum (recommended 10+ GB for all services)
- **CPUs**: 4+ cores
- **Disk**: 20+ GB

---

## Environment Variables (.env)

### Step 1: Create .env file

```bash
cd infra/docker
cp .env.example .env
```

### Step 2: Review and update values

The `.env` file at `infra/docker/.env`:

```env
# ============================================
# PayFlow Payment Gateway - Environment Variables
# ============================================

# --- Database ---
POSTGRES_USER=payflow
POSTGRES_PASSWORD=payflow123
DB_HOST=localhost
DB_PORT=5432

# --- Redis ---
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# --- Kafka ---
KAFKA_BROKERS=localhost:9092

# --- JWT ---
JWT_SECRET=payflow-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256
JWT_EXPIRATION=3600000
JWT_REFRESH_EXPIRATION=86400000

# --- AWS / LocalStack ---
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
LOCALSTACK_ENDPOINT=http://localhost:4566

# --- Service Ports ---
SERVICE_REGISTRY_PORT=8761
CONFIG_SERVER_PORT=8888
API_GATEWAY_PORT=8080
IDENTITY_SERVICE_PORT=8081
MERCHANT_SERVICE_PORT=8082
PAYMENT_SERVICE_PORT=8083
ROUTING_SERVICE_PORT=8084
SETTLEMENT_SERVICE_PORT=8085
WEBHOOK_SERVICE_PORT=8086
NOTIFICATION_SERVICE_PORT=8087
BANK_SIMULATOR_PORT=9000

# --- Logging ---
LOG_LEVEL=INFO
```

> **Note:** For local development, the default values work out of the box. No changes needed unless you have port conflicts.

### Is .env Required?

| Setup Option | .env Needed? |
|---|---|
| Full Docker (`docker-compose.full.yml`) | Yes — copy `.env.example` to `.env` |
| Infrastructure Docker + Local services | Optional — services use hardcoded defaults in `application.yml` |
| IntelliJ IDEA | No — services read from their own `application.yml` |

---

## Option 1: Full Docker Setup (Quickest Start)

Runs **everything** in Docker — infrastructure, all 11 microservices, and both frontends.

### Steps

```bash
# 1. Clone the repo
git clone <repo-url>
cd payflow-payment-gateway

# 2. Create environment file
cd infra/docker
cp .env.example .env

# 3. Build and start all containers
docker compose -f docker-compose.full.yml up -d --build
```

### Wait for Startup

Services start in dependency order. Full startup takes ~2-3 minutes.

```bash
# Watch logs
docker compose -f docker-compose.full.yml logs -f

# Check all containers are running
docker compose -f docker-compose.full.yml ps
```

### Verify Everything is Running

| What to Check | URL | Expected |
|---|---|---|
| Eureka Dashboard | http://localhost:8761 | Shows 9 services as UP |
| API Gateway Health | http://localhost:8080/actuator/health | `{"status":"UP"}` |
| Merchant Portal | http://localhost:3000 | React app loads |
| Hosted Checkout | http://localhost:3001 | Info page loads |
| Kafka UI | http://localhost:8090 | Kafka dashboard |
| PostgreSQL | `psql -h localhost -U payflow -d payflow` | Connects (password: `payflow123`) |

### Test Registration API

```bash
curl -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Test@12345",
    "fullName": "Test User"
  }'
```

Expected: `201 Created` with JWT token in response.

### Stop

```bash
docker compose -f docker-compose.full.yml down
```

### Reset (wipe all data)

```bash
docker compose -f docker-compose.full.yml down -v
```

---

## Option 2: Infrastructure in Docker + Services Locally

Best for **active development** — you get fast code reloads while infrastructure runs in Docker.

### Step 1: Start Infrastructure

```bash
cd infra/docker
docker compose up -d
```

This starts:
- ✅ PostgreSQL (port 5432) — with 4 databases auto-created
- ✅ Redis (port 6379)
- ✅ Kafka + Zookeeper (port 9092)
- ✅ LocalStack (port 4566) — DynamoDB, SNS, SQS initialized
- ✅ Kafka UI (port 8090)

Verify infrastructure is healthy:

```bash
docker compose ps
# All should show "healthy" or "running"
```

### Step 2: Build the Backend

```bash
cd backend
mvn clean install -DskipTests
```

Expected output:
```
[INFO] PayFlow Payment Gateway ................ SUCCESS
[INFO] PayFlow Common Library ................. SUCCESS
[INFO] PayFlow Service Registry ............... SUCCESS
[INFO] PayFlow Config Server .................. SUCCESS
[INFO] PayFlow API Gateway .................... SUCCESS
[INFO] PayFlow Identity Service ............... SUCCESS
[INFO] PayFlow Merchant Service ............... SUCCESS
[INFO] PayFlow Payment Service ................ SUCCESS
[INFO] routing-service ........................ SUCCESS
[INFO] PayFlow Settlement Service ............. SUCCESS
[INFO] PayFlow Webhook Service ................ SUCCESS
[INFO] PayFlow Notification Service ........... SUCCESS
[INFO] PayFlow Bank Simulator ................. SUCCESS
[INFO] BUILD SUCCESS
```

### Step 3: Start Backend Services (ORDER MATTERS)

Open **separate terminal windows** for each service. All commands run from `backend/` directory.

**Phase 1 — Platform services (start first, wait for each to be ready):**

```bash
# Terminal 1: Service Registry (Eureka)
cd backend
mvn spring-boot:run -pl service-registry
# Wait until you see: "Started ServiceRegistryApplication"
# Verify: http://localhost:8761
```

```bash
# Terminal 2: Config Server
cd backend
mvn spring-boot:run -pl config-server
# Wait until you see: "Started ConfigServerApplication"
# Verify: http://localhost:8888/actuator/health
```

```bash
# Terminal 3: API Gateway
cd backend
mvn spring-boot:run -pl api-gateway
# Wait until you see: "Started ApiGatewayApplication"
# Verify: http://localhost:8080/actuator/health
```

**Phase 2 — Business services (start after platform services are UP):**

```bash
# Terminal 4: Identity Service
cd backend
mvn spring-boot:run -pl identity-service

# Terminal 5: Merchant Service
cd backend
mvn spring-boot:run -pl merchant-service

# Terminal 6: Payment Service
cd backend
mvn spring-boot:run -pl payment-service

# Terminal 7: Routing Service
cd backend
mvn spring-boot:run -pl routing-service

# Terminal 8: Settlement Service
cd backend
mvn spring-boot:run -pl settlement-service

# Terminal 9: Webhook Service
cd backend
mvn spring-boot:run -pl webhook-service

# Terminal 10: Notification Service
cd backend
mvn spring-boot:run -pl notification-service

# Terminal 11: Bank Simulator
cd backend
mvn spring-boot:run -pl bank-simulator
```

### Step 4: Verify All Services in Eureka

Open http://localhost:8761 — you should see:

```
API-GATEWAY           UP(1) - api-gateway:8080
CONFIG-SERVER         UP(1) - config-server:8888
IDENTITY-SERVICE      UP(1) - identity-service:8081
MERCHANT-SERVICE      UP(1) - merchant-service:8082
NOTIFICATION-SERVICE  UP(1) - notification-service:8087
PAYMENT-SERVICE       UP(1) - payment-service:8083
ROUTING-SERVICE       UP(1) - routing-service:8084
SETTLEMENT-SERVICE    UP(1) - settlement-service:8085
WEBHOOK-SERVICE       UP(1) - webhook-service:8086
```

### Step 5: Start Frontends

```bash
# Terminal 12: Merchant Portal
cd frontend/merchant-portal
npm install
npm run dev
# Opens at http://localhost:3000
```

```bash
# Terminal 13: Hosted Checkout
cd frontend/hosted-checkout
npm install
npm run dev
# Opens at http://localhost:3001
```

---

## Option 3: IntelliJ IDEA (Best for Debugging)

### Setup

1. **File → Open** → select `backend/pom.xml` → "Open as Project"
2. Wait for IntelliJ to index and download all Maven dependencies (~5 min first time)
3. Verify: **Project Structure → SDKs** → Java 17 is configured
4. Start infrastructure: `cd infra/docker && docker compose up -d`

### Run Services

1. Navigate to each service's main class (e.g., `ServiceRegistryApplication.java`)
2. Right-click → **Run**
3. Start in order:
   - `ServiceRegistryApplication` → wait for startup
   - `ConfigServerApplication` → wait for startup
   - `ApiGatewayApplication` → wait for startup
   - Then all others (any order)

### Run Configuration Tips

- Create a **Compound** run configuration to start all services at once
- Set **Working Directory** to `$MODULE_DIR$` for each configuration
- Add `SPRING_PROFILES_ACTIVE=default` in environment variables

---

## Service Ports Reference

### Backend Services

| Service | Port | Main Class | Health Check |
|---------|------|-----------|--------------|
| Service Registry | 8761 | `ServiceRegistryApplication` | http://localhost:8761 |
| Config Server | 8888 | `ConfigServerApplication` | http://localhost:8888/actuator/health |
| API Gateway | 8080 | `ApiGatewayApplication` | http://localhost:8080/actuator/health |
| Identity Service | 8081 | `IdentityServiceApplication` | http://localhost:8081/actuator/health |
| Merchant Service | 8082 | `MerchantServiceApplication` | http://localhost:8082/actuator/health |
| Payment Service | 8083 | `PaymentServiceApplication` | http://localhost:8083/actuator/health |
| Routing Service | 8084 | `RoutingServiceApplication` | http://localhost:8084/actuator/health |
| Settlement Service | 8085 | `SettlementServiceApplication` | http://localhost:8085/actuator/health |
| Webhook Service | 8086 | `WebhookServiceApplication` | http://localhost:8086/actuator/health |
| Notification Service | 8087 | `NotificationServiceApplication` | http://localhost:8087/actuator/health |
| Bank Simulator | 9000 | `BankSimulatorApplication` | http://localhost:9000/actuator/health |

### Frontend

| App | Port | URL |
|-----|------|-----|
| Merchant Portal | 3000 | http://localhost:3000 |
| Hosted Checkout | 3001 | http://localhost:3001 |

### Infrastructure

| Service | Port | Purpose |
|---------|------|---------|
| PostgreSQL | 5432 | Relational data (4 databases) |
| Redis | 6379 | Caching, idempotency keys, rate limiting |
| Kafka | 9092 | Event streaming between services |
| Zookeeper | 2181 | Kafka coordination |
| LocalStack | 4566 | AWS emulation (DynamoDB, SNS, SQS) |
| Kafka UI | 8090 | Kafka topic browser |

---

## Database Details

### Databases Created Automatically

| Database | Used By | Flyway Migrations |
|----------|---------|-------------------|
| `payflow_identity` | Identity Service | Users, roles, refresh tokens |
| `payflow_merchant` | Merchant Service | Merchants, API keys, webhooks, fees |
| `payflow_payment` | Payment Service | Orders, payments, payment methods, refunds |
| `payflow_settlement` | Settlement Service | Batches, settlement records, payouts |

### Connection Details

```
Host: localhost
Port: 5432
Username: payflow
Password: payflow123
```

### Connect via CLI

```bash
# Connect to identity database
psql -h localhost -U payflow -d payflow_identity

# Connect to payment database
psql -h localhost -U payflow -d payflow_payment
```

### Flyway Migrations

Migrations run automatically on service startup. Files are located at:
```
backend/<service>/src/main/resources/db/migration/V1__*.sql
```

---

## API Routes (via Gateway)

All external requests go through API Gateway at `http://localhost:8080`:

| Method | Path | Service | Description |
|--------|------|---------|-------------|
| POST | `/v1/auth/register` | Identity | Register new user |
| POST | `/v1/auth/login` | Identity | Login, get JWT |
| POST | `/v1/auth/refresh` | Identity | Refresh access token |
| GET | `/v1/auth/profile` | Identity | Get user profile |
| POST | `/v1/merchants` | Merchant | Register merchant |
| GET | `/v1/merchants/{id}` | Merchant | Get merchant |
| POST | `/v1/merchants/{id}/api-keys` | Merchant | Generate API key |
| POST | `/v1/merchants/{id}/webhooks` | Merchant | Configure webhook |
| POST | `/v1/orders` | Payment | Create payment order |
| POST | `/v1/payments/authorize` | Payment | Authorize payment |
| POST | `/v1/payments/capture` | Payment | Capture payment |
| GET | `/v1/payments/{id}` | Payment | Get payment status |
| POST | `/v1/refunds` | Payment | Refund payment |
| POST | `/v1/settlements/trigger` | Settlement | Trigger settlement batch |
| GET | `/v1/settlements` | Settlement | List settlements |
| GET | `/v1/settlements/payouts` | Settlement | List payouts |

---

## Testing the Full Flow

### 1. Register a User

```bash
curl -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@test.com",
    "password": "SecurePass123",
    "fullName": "Test Merchant"
  }'
```

### 2. Login

```bash
curl -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@test.com",
    "password": "SecurePass123"
  }'
```

Save the `accessToken` from the response.

### 3. Access Protected Endpoint

```bash
curl http://localhost:8080/v1/merchants \
  -H "Authorization: Bearer <your-access-token>"
```

---

## Eureka Service Discovery

All services register with Eureka using consistent configuration:

```yaml
eureka:
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

This ensures:
- All services are reachable via IP (no hostname resolution issues)
- Dashboard shows clean `service-name:port` format
- Gateway can route to any service reliably

---

## LocalStack (AWS Emulation)

LocalStack initializes automatically via `infra/docker/init-localstack.sh`:

| AWS Service | Resources Created |
|-------------|-------------------|
| DynamoDB | `webhook_delivery_records`, `routing_metrics`, `event_logs` |
| SNS | `payment-events`, `settlement-events`, `webhook-events`, `notification-events`, `merchant-events` |
| SQS | `payment-notifications`, `webhook-delivery`, `settlement-processing`, `email-notifications`, `sms-notifications` + DLQs |

### Verify LocalStack

```bash
# Check health
curl http://localhost:4566/_localstack/health

# List DynamoDB tables
aws --endpoint-url=http://localhost:4566 dynamodb list-tables --region us-east-1

# List SNS topics
aws --endpoint-url=http://localhost:4566 sns list-topics --region us-east-1

# List SQS queues
aws --endpoint-url=http://localhost:4566 sqs list-queues --region us-east-1
```

---

## Troubleshooting

### Build Fails: Maven SSL/PKIX Error

```
PKIX path building failed: unable to find valid certification path
```

**Cause:** Corporate proxy/firewall intercepting HTTPS.
**Fix:** Import your corporate CA certificate into Java's truststore:

```bash
keytool -importcert -file corporate-ca.cer \
  -keystore "$JAVA_HOME/lib/security/cacerts" \
  -alias corporate-ca -storepass changeit
```

### Build Fails: Module Not Found

```
Could not find the selected project in the reactor: service-registry
```

**Cause:** Running `mvn -pl service-registry` from wrong directory.
**Fix:** Always run from the `backend/` directory where parent `pom.xml` is.

### Service Can't Connect to Eureka

**Cause:** Service Registry not started yet.
**Fix:** Always start `service-registry` first and wait for it to be fully UP at http://localhost:8761 before starting other services.

### Service Can't Connect to Database

**Cause:** PostgreSQL not ready or database doesn't exist.
**Fix:**
```bash
# Check PostgreSQL is running
docker compose ps postgres

# Check databases exist
docker exec payflow-postgres psql -U payflow -c "\l"
```

### Eureka Shows Mixed Hostnames

**Cause:** Services registered before config update was applied.
**Fix:** Rebuild all services (`mvn clean install`) and restart them.

### Hosted Checkout Shows Blank Page

**Cause:** Visiting root `/` without an order ID.
**Fix:** The checkout page needs a valid order ID: `http://localhost:3001/checkout/{orderId}`. Visiting `/` shows an informational landing page.

### Kafka Topics Not Created

**Cause:** Kafka `auto.create.topics.enable=true` is set, so topics are created on first publish. No manual creation needed.

### Zookeeper Marked Unhealthy

**Cause:** Zookeeper's four-letter commands (like `ruok`) are disabled by default in newer versions.
**Fix:** The compose file includes `KAFKA_OPTS: "-Dzookeeper.4lw.commands.whitelist=ruok,stat,srvr"` to whitelist them. If you still see this, restart the zookeeper container.

### Port Already in Use

**Cause:** Previous instance still running.
**Fix:**
```bash
# Find what's using the port (Windows)
netstat -ano | findstr :8080

# Kill the process
taskkill /PID <pid> /F

# Or stop all Docker containers
docker compose down
```

### Docker Out of Memory

**Cause:** Not enough RAM allocated to Docker.
**Fix:** Docker Desktop → Settings → Resources → Memory → Set to 8GB+.

### Frontend npm install Fails

**Cause:** Node.js version too old or network issues.
**Fix:**
```bash
# Check Node version (need 20+)
node --version

# Clear npm cache and retry
npm cache clean --force
rm -rf node_modules package-lock.json
npm install
```

---

## Quick Reference Commands

```bash
# Build backend
cd backend && mvn clean install -DskipTests

# Build backend with tests
cd backend && mvn clean install

# Run single service
cd backend && mvn spring-boot:run -pl identity-service

# Start infrastructure
cd infra/docker && docker compose up -d

# Stop infrastructure
cd infra/docker && docker compose down

# View infrastructure logs
cd infra/docker && docker compose logs -f

# Start full stack (Docker)
cd infra/docker && docker compose -f docker-compose.full.yml up -d --build

# Frontend dev server
cd frontend/merchant-portal && npm run dev
cd frontend/hosted-checkout && npm run dev

# Frontend production build
cd frontend/merchant-portal && npm run build
cd frontend/hosted-checkout && npm run build

# Check Eureka
curl http://localhost:8761/eureka/apps

# Test API Gateway
curl http://localhost:8080/actuator/health
```


---

## Docker Clean Rebuild (Fresh Start)

If you encounter Flyway migration conflicts, stale schema issues, or need to start completely fresh:

```bash
cd infra/docker

# Stop everything and DELETE all data volumes
docker compose -f docker-compose.full.yml down -v

# Rebuild and start
docker compose -f docker-compose.full.yml up -d --build
```

> ⚠️ **WARNING:** `down -v` deletes all database volumes. All data (users, merchants, payments, settlements) will be lost. Only use this during development.

### Verify After Clean Start

```bash
# Check all containers are running
docker compose -f docker-compose.full.yml ps

# Watch startup logs
docker compose -f docker-compose.full.yml logs -f

# Check specific service logs
docker compose -f docker-compose.full.yml logs -f identity-service
docker compose -f docker-compose.full.yml logs -f api-gateway
```

### Expected Final State

All services should show `healthy` or `running`:

```
payflow-postgres           healthy
payflow-redis              healthy
payflow-kafka              healthy
payflow-localstack         healthy
payflow-service-registry   healthy
payflow-config-server      healthy
payflow-api-gateway        healthy
payflow-identity-service   healthy
payflow-merchant-service   healthy
payflow-payment-service    healthy
payflow-routing-service    healthy
payflow-settlement-service healthy
payflow-webhook-service    healthy
payflow-notification-service healthy
payflow-bank-simulator     healthy
payflow-merchant-portal    running
payflow-hosted-checkout    running
```

---

## Docker Build Architecture

All backend services use `backend/` as the Docker build context:

```
docker-compose.full.yml
    │
    ├── context: ../../backend
    │   └── dockerfile: service-registry/Dockerfile
    │   └── dockerfile: config-server/Dockerfile
    │   └── dockerfile: api-gateway/Dockerfile
    │   └── dockerfile: identity-service/Dockerfile
    │   └── dockerfile: merchant-service/Dockerfile
    │   └── dockerfile: payment-service/Dockerfile
    │   └── dockerfile: routing-service/Dockerfile
    │   └── dockerfile: settlement-service/Dockerfile
    │   └── dockerfile: webhook-service/Dockerfile
    │   └── dockerfile: notification-service/Dockerfile
    │   └── dockerfile: bank-simulator/Dockerfile
    │
    ├── context: ../../frontend/merchant-portal
    │   └── dockerfile: Dockerfile
    │
    └── context: ../../frontend/hosted-checkout
        └── dockerfile: Dockerfile
```

This ensures each service Dockerfile can access:
- `pom.xml` (parent POM)
- `common-lib/` (shared library)
- `<service>/` (the service being built)

Without `../` paths in COPY commands (which Docker doesn't allow).
