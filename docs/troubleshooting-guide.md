# PayFlow Payment Gateway — Troubleshooting Guide

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Type** | Troubleshooting Reference |
| **Version** | v1.0.0 |
| **Previous** | [Database Guide](database-guide.md) |
| **Next** | [Project Progress Report](PROJECT-PROGRESS-REPORT.md) |
| **Prerequisites** | Running PayFlow environment, Docker, basic debugging |

---

## Table of Contents

1. [Infrastructure Errors](#1-infrastructure-errors)
2. [Service Startup Errors](#2-service-startup-errors)
3. [Authentication Errors](#3-authentication-errors)
4. [Payment Errors](#4-payment-errors)
5. [Docker Errors](#5-docker-errors)
6. [Frontend Errors](#6-frontend-errors)
7. [Health Check Endpoints](#7-health-check-endpoints)
8. [Test Cards for Bank Simulator](#8-test-cards-for-bank-simulator)
9. [What You Learned](#what-you-learned)
10. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Infrastructure Errors

### PostgreSQL Connection Refused

**Symptom:** `Connection refused to localhost:5432`

```bash
# Check if PostgreSQL is running
docker ps | grep postgres

# If not running, start it
docker compose up -d postgres

# Check logs for startup errors
docker logs payflow-postgres

# Verify from inside network
docker exec -it payflow-postgres pg_isready -U payflow
```

**Common causes & fixes:**

| Cause | Fix |
|-------|-----|
| Container not started | `docker compose up -d postgres` |
| Wrong port mapping | Check `docker ps` for correct port |
| Database doesn't exist | Connect and `CREATE DATABASE payflow_payments;` |
| Wrong credentials | Verify `POSTGRES_USER` and `POSTGRES_PASSWORD` in compose |

### Redis Connection Refused

**Symptom:** `Unable to connect to Redis on localhost:6379`

```bash
# Check Redis
docker ps | grep redis
docker logs payflow-redis

# Test connection
docker exec -it payflow-redis redis-cli ping
# Expected: PONG

# Check memory
docker exec -it payflow-redis redis-cli info memory
```

### Kafka Connection Issues

**Symptom:** `Connection to node -1 could not be established`

```bash
# Check Kafka + Zookeeper
docker ps | grep -E "kafka|zookeeper"

# Kafka logs
docker logs payflow-kafka --tail=50

# List topics (verify Kafka is working)
docker exec -it payflow-kafka kafka-topics --list --bootstrap-server localhost:9092

# Create topic manually if auto-create is disabled
docker exec -it payflow-kafka kafka-topics --create \
  --topic payment-events \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1
```

**Common Kafka issues:**

| Symptom | Cause | Fix |
|---------|-------|-----|
| `LEADER_NOT_AVAILABLE` | Kafka still starting | Wait 30 seconds, retry |
| `UNKNOWN_TOPIC` | Topic not created | Enable `auto.create.topics` or create manually |
| Connection refused from service | Using `localhost` instead of `kafka` | Use `kafka:29092` for internal Docker networking |

---

## 2. Service Startup Errors

### Config Not Loading

**Symptom:** `Failed to configure a DataSource: 'url' attribute is not specified`

```bash
# Check if environment variables are set
docker exec payflow-payment env | grep SPRING

# Verify .env file exists and has correct values
cat .env | grep DATASOURCE

# Check active profile
docker logs payflow-payment | grep "active profiles"
# Should show: The following profiles are active: docker (or prod)
```

### Flyway Migration Failures

**Symptom:** `Migration checksum mismatch` or `migration failed`

```bash
# Check Flyway status
docker exec -it payflow-postgres psql -U payflow -d payflow_payments \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_on DESC LIMIT 5;"

# Fix: repair Flyway (marks failed migration as resolved)
# In application.yml temporarily:
# spring.flyway.repair-on-migrate: true

# Or force clean (DEV ONLY! Drops all tables!)
# spring.flyway.clean-disabled: false
# spring.flyway.clean-on-validation-error: true
```

### Port Conflicts

**Symptom:** `Port 8080 already in use`

```bash
# Find what's using the port
# Windows
netstat -ano | findstr :8080
taskkill /PID <pid> /F

# Linux/Mac
lsof -i :8080
kill -9 <pid>

# Or change the port in compose
ports:
  - "8081:8080"  # Map to different host port
```

---

## 3. Authentication Errors

### Token Expired

**Symptom:** `{"error":"UNAUTHORIZED","message":"Token has expired"}`

```bash
# Get a new token
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"your@email.com","password":"password"}'

# Or use refresh token
curl -X POST http://localhost:8080/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"your-refresh-token"}'
```

### JWT Secret Mismatch

**Symptom:** `SignatureException: JWT signature does not match`

```bash
# Verify all services use the same JWT secret
docker exec payflow-identity env | grep JWT_SECRET
docker exec payflow-payment env | grep JWT_SECRET
docker exec payflow-gateway env | grep JWT_SECRET

# They MUST all be identical!
# Fix: ensure .env has one JWT_SECRET used by all services
```

### API Key Not Working

**Symptom:** `{"error":"UNAUTHORIZED","message":"Invalid API key"}`

```bash
# Verify key format (should start with pk_live_ or pk_test_)
# Verify key hasn't been revoked
curl http://localhost:8080/api/v1/merchants/api-keys \
  -H "Authorization: Bearer $TOKEN"

# Check if key lookup is working
docker logs payflow-merchant | grep "API key"
```

---

## 4. Payment Errors

### Idempotency Conflict

**Symptom:** `{"error":"IDEMPOTENCY_CONFLICT","status":409}`

This means you sent the same `X-Idempotency-Key` with a different request body.

```bash
# Fix: Use a unique key for each unique request
curl -X POST http://localhost:8080/api/v1/payments/orders \
  -H "X-Idempotency-Key: unique-$(uuidgen)" \
  ...
```

### Invalid State Transition

**Symptom:** `{"error":"INVALID_STATE","message":"Cannot capture order in CREATED state"}`

```
Valid State Transitions:
CREATED → AUTHORIZED → CAPTURED → SETTLED
CREATED → FAILED
AUTHORIZED → FAILED
CAPTURED → REFUNDED (full or partial)
```

```bash
# Check current order status
curl http://localhost:8080/api/v1/payments/orders/ORD_xxx \
  -H "Authorization: Bearer $TOKEN" | jq '.status'

# Must authorize BEFORE capture
curl -X POST http://localhost:8080/api/v1/payments/orders/ORD_xxx/authorize ...
# Then capture
curl -X POST http://localhost:8080/api/v1/payments/orders/ORD_xxx/capture ...
```

### Bank Declined

**Symptom:** `{"error":"PAYMENT_DECLINED","message":"Insufficient funds"}`

```bash
# Using bank simulator, specific card numbers trigger specific responses:
# See "Test Cards" section below

# Check bank simulator logs for details
docker logs payflow-bank --tail=20
```

---

## 5. Docker Errors

### No Space Left on Device

```bash
# Check Docker disk usage
docker system df

# Clean up unused resources
docker system prune -a          # Remove all unused images
docker volume prune             # Remove unused volumes
docker builder prune            # Remove build cache

# Nuclear option (removes EVERYTHING not running)
docker system prune -a --volumes
```

### Network Not Found

**Symptom:** `network payflow_backend-net not found`

```bash
# List networks
docker network ls

# Create missing network
docker network create payflow_backend-net

# Or restart compose (creates networks automatically)
docker compose down
docker compose up -d
```

### Health Check Failed

**Symptom:** Container status shows `(unhealthy)`

```bash
# Check health check command
docker inspect payflow-payment --format='{{.State.Health}}'

# Run health check manually
docker exec payflow-payment wget --spider -q http://localhost:8080/actuator/health

# Common fix: increase start_period for slow-starting services
healthcheck:
  test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
  interval: 30s
  timeout: 5s
  retries: 5
  start_period: 60s   # Give service time to start
```

### Container Keeps Restarting

```bash
# Check exit code and logs
docker inspect payflow-payment --format='{{.State.ExitCode}}'
docker logs payflow-payment --tail=50

# Common exit codes:
# 0  = normal exit
# 1  = application error (check logs)
# 137 = OOM killed (need more memory)
# 143 = SIGTERM (graceful stop)
```

---

## 6. Frontend Errors

### CORS Errors

**Symptom:** `Access to fetch at 'http://localhost:8080' blocked by CORS policy`

```bash
# Verify API Gateway CORS config
docker exec payflow-gateway cat /app/application.yml | grep -A5 cors

# Expected configuration in API Gateway:
# spring.cloud.gateway.globalcors:
#   cors-configurations:
#     '[/**]':
#       allowedOrigins: "http://localhost:5173"
#       allowedMethods: "*"
#       allowedHeaders: "*"
```

### API Connection Failed

**Symptom:** `ERR_CONNECTION_REFUSED` or `Network Error`

```bash
# Check if backend is running
curl http://localhost:8080/actuator/health

# Verify frontend API URL
cat frontend/.env
# VITE_API_BASE_URL=http://localhost:8080

# If using Docker, use correct internal URL
# Frontend (browser) → needs localhost:8080
# Frontend (SSR/container) → needs api-gateway:8080
```

### Build Failures

**Symptom:** `npm run build` fails

```bash
# Check Node version
node --version  # Should be 20.x

# Clean install
rm -rf node_modules package-lock.json
npm install

# Check for TypeScript errors
npx tsc --noEmit

# Check for lint errors
npm run lint

# Build with verbose output
npm run build -- --debug
```

---

## 7. Health Check Endpoints

| Service | Port | Health URL |
|---------|------|-----------|
| API Gateway | 8080 | `http://localhost:8080/actuator/health` |
| Identity Service | 8081 | `http://localhost:8081/actuator/health` |
| Payment Service | 8082 | `http://localhost:8082/actuator/health` |
| Merchant Service | 8083 | `http://localhost:8083/actuator/health` |
| Routing Engine | 8084 | `http://localhost:8084/actuator/health` |
| Bank Simulator | 8085 | `http://localhost:8085/actuator/health` |
| Settlement Service | 8086 | `http://localhost:8086/actuator/health` |
| Notification Service | 8087 | `http://localhost:8087/actuator/health` |
| Webhook Service | 8088 | `http://localhost:8088/actuator/health` |
| Analytics Service | 8089 | `http://localhost:8089/actuator/health` |
| Reconciliation Service | 8090 | `http://localhost:8090/actuator/health` |

### Quick Health Check Script

```bash
#!/bin/bash
echo "=== PayFlow Health Check ==="
for port in 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$port/actuator/health 2>/dev/null)
  if [ "$STATUS" = "200" ]; then
    echo "✅ Port $port: UP"
  else
    echo "❌ Port $port: DOWN (HTTP $STATUS)"
  fi
done
```

---

## 8. Test Cards for Bank Simulator

The bank simulator responds based on card numbers:

| Card Number | Response | Use Case |
|-------------|----------|----------|
| `4111 1111 1111 1111` | ✅ Approved | Happy path testing |
| `4000 0000 0000 0002` | ❌ Declined | Insufficient funds |
| `4000 0000 0000 0069` | ❌ Expired card | Invalid card testing |
| `4000 0000 0000 0119` | ❌ Processing error | Timeout simulation |
| `4000 0000 0000 3220` | ⏳ 3D Secure required | OTP flow testing |
| `5200 0000 0000 0007` | ✅ Approved (Mastercard) | Multi-network test |
| `6011 0000 0000 0004` | ✅ Approved (Discover) | Alternative network |

### Test UPI VPAs

| VPA | Response |
|-----|----------|
| `success@upi` | ✅ Payment approved |
| `failure@upi` | ❌ Payment declined |
| `timeout@upi` | ⏳ Request timeout |

### Test Net Banking

| Bank Code | Response |
|-----------|----------|
| `SBI` | ✅ Payment approved |
| `HDFC` | ✅ Payment approved |
| `FAIL_BANK` | ❌ Bank declined |

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Infrastructure debugging | Check container status + logs first |
| 2 | Connection issues | Verify host, port, and network (Docker DNS vs localhost) |
| 3 | Auth troubleshooting | Same JWT secret across all services is critical |
| 4 | Payment state machine | Follow CREATED → AUTHORIZED → CAPTURED flow |
| 5 | Docker cleanup | `docker system prune` reclaims disk space |
| 6 | CORS | Must configure allowed origins on API Gateway |
| 7 | Test cards | Specific numbers trigger specific bank responses |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| All services unhealthy | Database not ready | Start infra first: `docker compose up -d postgres redis kafka` |
| `ClassNotFoundException` | Bad Docker image build | Rebuild: `docker compose build --no-cache service-name` |
| `OutOfMemoryError` | JVM heap too small | Set `JAVA_OPTS=-Xmx512m` in environment |
| Intermittent failures | Race condition on startup | Add proper `depends_on` with health conditions |
| `Connection pool exhausted` | Too many DB connections | Reduce pool size or add connection timeout |
| 504 Gateway Timeout | Service processing too long | Check downstream service health, increase timeout |

---

<div align="center">

**[← Database Guide](database-guide.md)** | **[Documentation Index](../README.md)** | **[Project Progress Report →](PROJECT-PROGRESS-REPORT.md)**

</div>
