# Phase 6 · Part 3 — Docker Compose for Multi-Container Apps

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 6 — Containerization |
| **Part** | 3 — Docker Compose |
| **Previous** | [Part 2 — Dockerfile Explained](phase6-part2-dockerfile-explained.md) |
| **Next** | [Phase 7 Part 1 — CI/CD Concepts](phase7-part1-cicd-concepts.md) |
| **Time** | ~2.5 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | Docker basics, Dockerfile understanding, YAML |

---

## Table of Contents

1. [What is Docker Compose?](#1-what-is-docker-compose)
2. [docker-compose.yml Anatomy](#2-docker-composeyml-anatomy)
3. [PayFlow Local Infrastructure Compose](#3-payflow-local-infrastructure-compose)
4. [Full-Stack Compose](#4-full-stack-compose)
5. [Networks and Isolation](#5-networks-and-isolation)
6. [Health Checks and depends_on](#6-health-checks-and-depends_on)
7. [Essential Commands](#7-essential-commands)
8. [What You Learned](#what-you-learned)
9. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. What is Docker Compose?

Docker Compose is a tool for defining and running **multi-container** applications. Instead of running 15 separate `docker run` commands, you describe everything in one YAML file.

```
WITHOUT COMPOSE:                      WITH COMPOSE:
$ docker run postgres...              $ docker compose up -d
$ docker run redis...                 ✅ All 15 containers running
$ docker run kafka...
$ docker run zookeeper...
$ docker run payment-service...
$ docker run identity-service...
$ docker run ... (x9 more)
😰 15 commands, easy to mess up
```

**PayFlow needs:**
- 4 infrastructure containers (Postgres, Redis, Kafka, LocalStack)
- 11 application containers (microservices)
- Networks connecting them
- Volumes for data persistence

---

## 2. docker-compose.yml Anatomy

```yaml
# docker-compose.yml structure (version field is obsolete in modern Docker Compose)
services:                         # Each service = one container
  service-name:
    image: image:tag              # Use existing image OR...
    build: ./path                 # ...build from Dockerfile
    ports:
      - "host:container"          # Port mapping
    environment:                  # Environment variables
      - KEY=value
    volumes:                      # Mount points
      - name:/container/path
    networks:                     # Attach to networks
      - network-name
    depends_on:                   # Startup order
      other-service:
        condition: service_healthy
    healthcheck:                  # Health monitoring
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 5s
      retries: 3

networks:                         # Define custom networks
  network-name:
    driver: bridge

volumes:                          # Named volumes (persistent)
  volume-name:
```

---

## 3. PayFlow Local Infrastructure Compose

This compose file runs only the infrastructure — used during local development.

```yaml
# docker-compose.infra.yml
services:
  # ─── PostgreSQL ─────────────────────────────────────
  postgres:
    image: postgres:15-alpine
    container_name: payflow-postgres
    environment:
      POSTGRES_USER: payflow
      POSTGRES_PASSWORD: secret123
      POSTGRES_MULTIPLE_DATABASES: payflow_identity,payflow_payments,payflow_merchants,payflow_settlements
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./scripts/init-multiple-dbs.sh:/docker-entrypoint-initdb.d/init-dbs.sh
    networks:
      - data-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payflow"]
      interval: 10s
      timeout: 5s
      retries: 5

  # ─── Redis ──────────────────────────────────────────
  redis:
    image: redis:7-alpine
    container_name: payflow-redis
    ports:
      - "6379:6379"
    volumes:
      - redisdata:/data
    networks:
      - data-net
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 3

  # ─── Kafka + Zookeeper ──────────────────────────────
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: payflow-zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    networks:
      - data-net

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: payflow-kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,EXTERNAL://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    networks:
      - data-net
    healthcheck:
      test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "localhost:9092"]
      interval: 15s
      timeout: 10s
      retries: 5

  # ─── LocalStack (AWS mock) ──────────────────────────
  localstack:
    image: localstack/localstack:3.0
    container_name: payflow-localstack
    ports:
      - "4566:4566"
    environment:
      SERVICES: sqs,sns,dynamodb,s3
      DEFAULT_REGION: ap-south-1
    volumes:
      - ./scripts/localstack-init.sh:/etc/localstack/init/ready.d/init.sh
    networks:
      - data-net

networks:
  data-net:
    driver: bridge

volumes:
  pgdata:
  redisdata:
```

---

## 4. Full-Stack Compose

This runs everything — infrastructure + all 11 microservices.

```yaml
# docker-compose.yml (full-stack)
services:
  # ─── Infrastructure (same as above, abbreviated) ────
  postgres:
    image: postgres:15-alpine
    # ... (same config)
    networks: [data-net]

  redis:
    image: redis:7-alpine
    networks: [data-net]

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    networks: [data-net]

  # ─── Application Services ──────────────────────────
  api-gateway:
    build: ./backend/api-gateway
    container_name: payflow-gateway
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
    depends_on:
      identity-service:
        condition: service_healthy
    networks: [frontend-net, backend-net]
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      retries: 3

  identity-service:
    build: ./backend/identity-service
    container_name: payflow-identity
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/payflow_identity
      - SPRING_DATA_REDIS_HOST=redis
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
    networks: [backend-net, data-net]
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      retries: 3

  merchant-service:
    build: ./backend/merchant-service
    container_name: payflow-merchant
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/payflow_merchants
    depends_on:
      postgres: { condition: service_healthy }
    networks: [backend-net, data-net]

  payment-service:
    build: ./backend/payment-service
    container_name: payflow-payment
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/payflow_payments
      - SPRING_DATA_REDIS_HOST=redis
      - SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      kafka: { condition: service_healthy }
    networks: [backend-net, data-net]

  routing-engine:
    build: ./backend/routing-engine
    container_name: payflow-routing
    networks: [backend-net, data-net]

  bank-simulator:
    build: ./backend/bank-simulator
    container_name: payflow-bank
    networks: [backend-net]

  settlement-service:
    build: ./backend/settlement-service
    container_name: payflow-settlement
    networks: [backend-net, data-net]

  notification-service:
    build: ./backend/notification-service
    container_name: payflow-notification
    networks: [backend-net, data-net]

  webhook-service:
    build: ./backend/webhook-service
    container_name: payflow-webhook
    networks: [backend-net, data-net]

  analytics-service:
    build: ./backend/analytics-service
    container_name: payflow-analytics
    networks: [backend-net, data-net]

  reconciliation-service:
    build: ./backend/reconciliation-service
    container_name: payflow-reconciliation
    networks: [backend-net, data-net]

networks:
  frontend-net:
    driver: bridge
  backend-net:
    driver: bridge
  data-net:
    driver: bridge

volumes:
  pgdata:
  redisdata:
```

---

## 5. Networks and Isolation

```
┌─────────────────────────────────────────────────────────────────┐
│                        DOCKER HOST                               │
│                                                                  │
│  ┌──────────────── frontend-net ─────────────────────┐          │
│  │  [API Gateway :8080] ◄──── External traffic       │          │
│  └──────────┬────────────────────────────────────────┘          │
│             │                                                    │
│  ┌──────────┴──── backend-net ───────────────────────┐          │
│  │  [Identity]  [Merchant]  [Payment]  [Settlement]  │          │
│  │  [Routing]   [Bank-Sim]  [Webhook]  [Notify]      │          │
│  │  [Analytics] [Reconciliation]                      │          │
│  └──────────┬────────────────────────────────────────┘          │
│             │                                                    │
│  ┌──────────┴──── data-net ──────────────────────────┐          │
│  │  [PostgreSQL :5432]  [Redis :6379]  [Kafka :9092] │          │
│  └───────────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────────────┘
```

**Why network isolation?**

| Network | Purpose | Who can access |
|---------|---------|----------------|
| `frontend-net` | External-facing | Only API Gateway |
| `backend-net` | Service-to-service | All microservices |
| `data-net` | Database access | Services + infrastructure |

- API Gateway is the ONLY entry point from outside
- Databases are NOT accessible from the internet
- Services communicate internally via service names (DNS)

---

## 6. Health Checks and depends_on

### Health Check Configuration

```yaml
healthcheck:
  test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
  interval: 30s       # Check every 30 seconds
  timeout: 5s         # Fail if no response in 5 seconds
  retries: 3          # Mark unhealthy after 3 consecutive failures
  start_period: 40s   # Grace period for startup
```

### depends_on with Conditions

```yaml
payment-service:
  depends_on:
    postgres:
      condition: service_healthy    # Wait until postgres health check passes
    redis:
      condition: service_healthy    # Wait until redis is ready
    kafka:
      condition: service_healthy    # Wait until kafka is ready
```

**Without health conditions:** Services start immediately — payment-service might crash because Postgres isn't ready yet.

**With health conditions:** Docker waits for dependencies to be truly healthy before starting dependent services.

---

## 7. Essential Commands

```bash
# ─── Starting ─────────────────────────────────────────
docker compose up -d                    # Start all services (detached)
docker compose up -d postgres redis     # Start specific services only
docker compose -f docker-compose.infra.yml up -d  # Use specific file

# ─── Monitoring ───────────────────────────────────────
docker compose ps                       # Status of all services
docker compose logs -f                  # Follow ALL logs
docker compose logs -f payment-service  # Follow specific service
docker compose top                      # Running processes

# ─── Stopping ─────────────────────────────────────────
docker compose stop                     # Stop (keep containers)
docker compose down                     # Stop + remove containers
docker compose down -v                  # Stop + remove containers + volumes (⚠️ data loss!)

# ─── Rebuilding ───────────────────────────────────────
docker compose build                    # Rebuild all images
docker compose build payment-service    # Rebuild specific service
docker compose up -d --build            # Rebuild + restart

# ─── Debugging ────────────────────────────────────────
docker compose exec postgres psql -U payflow  # Shell into service
docker compose exec payment-service sh        # Shell into app container
docker compose config                         # Validate compose file
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Docker Compose purpose | Define multi-container apps in a single YAML file |
| 2 | YAML structure | services, networks, volumes as top-level keys |
| 3 | Infrastructure compose | Postgres, Redis, Kafka, LocalStack for local dev |
| 4 | Full-stack compose | All 11 services + infrastructure in one command |
| 5 | Network isolation | Three networks separate frontend, backend, data layers |
| 6 | Health checks | Ensure services are truly ready before dependents start |
| 7 | Commands | `up -d`, `logs -f`, `down`, `build`, `exec` |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `network not found` | Compose file missing network definition | Add network under top-level `networks:` key |
| Service can't connect to postgres | Different networks or wrong hostname | Use service name as hostname (e.g., `postgres:5432`) |
| `port already allocated` | Another container or process on that port | Stop conflicting process or change host port |
| Services start before DB is ready | `depends_on` without health condition | Add `condition: service_healthy` |
| `no space left on device` | Docker images/volumes filling disk | Run `docker system prune -a` |
| Changes not reflected | Old image cached | Run `docker compose up -d --build` |
| Kafka connection refused | Using `localhost` instead of container name | Use `kafka:29092` for inter-container communication |

---

<div align="center">

**[← Part 2: Dockerfile](phase6-part2-dockerfile-explained.md)** | **[Documentation Index](../README.md)** | **[Phase 7 Part 1: CI/CD Concepts →](phase7-part1-cicd-concepts.md)**

</div>
