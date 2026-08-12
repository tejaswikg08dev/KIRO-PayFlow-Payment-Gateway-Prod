# PayFlow Troubleshooting Guide

## Common Errors & Fixes

### Infrastructure

| Error | Cause | Fix |
|-------|-------|-----|
| `Connection refused: localhost:5432` | PostgreSQL not running | `cd infra/docker && docker compose up postgres -d` |
| `Connection refused: localhost:6379` | Redis not running | `cd infra/docker && docker compose up redis -d` |
| `Connection refused: localhost:9092` | Kafka not running | `cd infra/docker && docker compose up kafka -d` |
| `Connection refused: localhost:8761` | Eureka not running | Start service-registry first |
| `Config server not available` | Config server not running | Start config-server after service-registry |

### Service Startup

| Error | Cause | Fix |
|-------|-------|-----|
| `Table not found` | Flyway hasn't run | Check `spring.flyway.enabled=true` in application.yml |
| `Could not resolve placeholder` | Config not loaded | Ensure config-server is running and service name matches |
| `No instances available for service` | Eureka registration pending | Wait 30s for heartbeat, or check Eureka dashboard |
| `Port already in use` | Another instance running | Kill existing process or change port |

### Authentication

| Error | Cause | Fix |
|-------|-------|-----|
| `401 Unauthorized` | Token expired/invalid | Login again to get new token |
| `Token signature mismatch` | jwt.secret differs between services | Use same secret in all service configs |
| `Refresh token expired` | 7-day TTL exceeded | User must login again |

### Payments

| Error | Cause | Fix |
|-------|-------|-----|
| `IDEMPOTENCY_CONFLICT` | Duplicate request in progress | Wait and retry, or use different idempotency key |
| `INVALID_STATE_TRANSITION` | Wrong payment state for operation | Check current state before calling capture/void/refund |
| `PAYMENT_DECLINED` | Bank rejected the payment | Check card details, try different card (4111... always approves) |
| `Circuit breaker is OPEN` | Bank simulator down | Start bank-simulator, wait for half-open state |

### Docker

| Error | Cause | Fix |
|-------|-------|-----|
| `No space left on device` | Docker disk full | `docker system prune -a` |
| `network not found` | Networks not created | `docker compose up` creates them automatically |
| `depends_on condition failed` | Dependency not healthy | Check health of dependent service |

## Health Check Endpoints

All services expose: `GET /actuator/health`

```bash
# Check all services
curl http://localhost:8761/actuator/health  # Eureka
curl http://localhost:8888/actuator/health  # Config
curl http://localhost:8080/actuator/health  # Gateway
curl http://localhost:8081/actuator/health  # Identity
curl http://localhost:8082/actuator/health  # Merchant
curl http://localhost:8083/actuator/health  # Payment
curl http://localhost:8084/actuator/health  # Routing
curl http://localhost:8085/actuator/health  # Settlement
curl http://localhost:8086/actuator/health  # Webhook
curl http://localhost:8087/actuator/health  # Notification
curl http://localhost:9000/actuator/health  # Bank Simulator
```

## Test Cards (Bank Simulator)

| Card Number | Result |
|-------------|--------|
| 4111 1111 1111 1111 | Always APPROVED |
| 4000 0000 0000 0000 | Always DECLINED |
| 5500 0000 0000 0004 | Random (85% approve) |
| Any card, amount > ₹1,00,000 | DECLINED |
| Any card, amount ending in 13 | DECLINED |
