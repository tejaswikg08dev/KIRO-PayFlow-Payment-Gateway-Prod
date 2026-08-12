# Phase 6: Docker Containerization

## Overview

Containerization strategy for PayFlow using Docker multi-stage builds and Docker Compose orchestration. Covers image optimization, layer caching, health checks, and production-ready container patterns.

## Docker Concepts for PayFlow

| Concept | Application |
|---------|-------------|
| Multi-stage Build | Separate build (Maven) from runtime (JRE) → smaller images |
| Layer Caching | Copy pom.xml before source code → cache dependencies |
| Non-root User | Security: run as `payflow` user inside container |
| Health Checks | Docker-level health monitoring for orchestration |
| Network Isolation | Bridge network for inter-service communication |
| Volume Mounts | Persistent data for PostgreSQL, Redis |
| Environment Variables | Externalize configuration per environment |

## Image Size Optimization

```
BEFORE (naive approach):
  eclipse-temurin:17 + Maven + Source + Dependencies + JDK
  → ~800MB

AFTER (multi-stage):
  Stage 1 (builder): Maven build → produces JAR
  Stage 2 (runtime): JRE Alpine + JAR only
  → ~180MB
```

### Multi-Stage Build Pattern

```dockerfile
# ═══════════════════════════════════════════
# STAGE 1: Build
# ═══════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Layer 1: Copy POM (cached if unchanged)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Layer 2: Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# ═══════════════════════════════════════════
# STAGE 2: Runtime
# ═══════════════════════════════════════════
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Security: non-root user
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder --chown=app:app /build/target/*.jar app.jar
USER app

# Container tuning
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

## Docker Compose Orchestration

```yaml
version: '3.8'

services:
  # ═══ INFRASTRUCTURE ═══
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: payflow
      POSTGRES_PASSWORD: ${DB_PASSWORD:-payflow123}
    ports: ["5432:5432"]
    volumes:
      - pg_data:/var/lib/postgresql/data
      - ./init-scripts:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payflow"]
      interval: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    command: redis-server --maxmemory 128mb --maxmemory-policy allkeys-lru
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
    ports: ["9092:9092"]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,HOST:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  # ═══ SERVICES ═══
  api-gateway:
    build: ./backend/api-gateway
    ports: ["8080:8080"]
    depends_on:
      redis: { condition: service_healthy }
    environment:
      REDIS_HOST: redis
      JWT_SECRET: ${JWT_SECRET}

  payment-service:
    build: ./backend/payment-service
    ports: ["8082:8082"]
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      kafka: { condition: service_started }
    environment:
      DB_HOST: postgres
      DB_NAME: payflow_payment
      REDIS_HOST: redis
      KAFKA_SERVERS: kafka:29092

volumes:
  pg_data:
```

## Container Resource Limits

```yaml
services:
  payment-service:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M
        reservations:
          cpus: '0.5'
          memory: 256M
```

## Docker Best Practices Applied

| Practice | Implementation |
|----------|---------------|
| `.dockerignore` | Exclude `target/`, `.git/`, `node_modules/` |
| Minimal base image | Alpine variants (smaller, fewer vulnerabilities) |
| Layer ordering | Less-changing layers first (deps before source) |
| Single process | One service per container |
| Graceful shutdown | JVM handles SIGTERM properly |
| No secrets in image | Pass via environment variables at runtime |
| Pinned versions | `postgres:15-alpine` not `postgres:latest` |
| Health checks | Every service exposes `/actuator/health` |

## Useful Docker Commands

```bash
# Build all services
docker compose build

# Start stack (detached)
docker compose up -d

# View logs
docker compose logs -f payment-service

# Scale a service
docker compose up -d --scale payment-service=3

# Rebuild one service
docker compose up -d --build payment-service

# Clean everything
docker compose down -v --rmi all

# Check resource usage
docker stats
```
