# Phase 4 Part 11: Docker & Docker Compose

## Overview

This document covers containerization of all PayFlow services using multi-stage Docker builds and orchestration with Docker Compose for local development.

## Multi-Stage Dockerfile (Backend Services)

```dockerfile
# ============================================
# Stage 1: Build with Maven
# ============================================
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy parent POM first (for dependency caching)
COPY pom.xml .
COPY payment-service/pom.xml payment-service/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -pl payment-service -am

# Copy source code
COPY payment-service/src payment-service/src

# Build the application
RUN mvn package -pl payment-service -am -DskipTests \
    -Dmaven.javadoc.skip=true

# ============================================
# Stage 2: Runtime (minimal JRE image)
# ============================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S payflow && adduser -S payflow -G payflow

# Copy JAR from builder stage
COPY --from=builder /app/payment-service/target/*.jar app.jar

# Set ownership
RUN chown payflow:payflow app.jar

USER payflow

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -q --spider http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

## Individual Service Dockerfiles

### API Gateway Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
COPY --chown=payflow:payflow target/*.jar app.jar
USER payflow
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### Frontend Dockerfile (React)

```dockerfile
# Stage 1: Build
FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: Serve with Nginx
FROM nginx:1.25-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -q --spider http://localhost:80/ || exit 1
CMD ["nginx", "-g", "daemon off;"]
```

## Docker Compose - Infrastructure

```yaml
# docker/docker-compose.yml
version: '3.8'

services:
  # ============ DATABASES ============
  postgres-identity:
    image: postgres:15-alpine
    container_name: payflow-postgres-identity
    environment:
      POSTGRES_DB: payflow_identity
      POSTGRES_USER: payflow
      POSTGRES_PASSWORD: payflow123
    ports:
      - "5433:5432"
    volumes:
      - postgres_identity_data:/var/lib/postgresql/data
      - ./init-scripts/identity-init.sql:/docker-entrypoint-initdb.d/init.sql
    networks:
      - payflow-network
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payflow -d payflow_identity"]
      interval: 10s
      timeout: 5s
      retries: 5

  postgres-merchant:
    image: postgres:15-alpine
    container_name: payflow-postgres-merchant
    environment:
      POSTGRES_DB: payflow_merchant
      POSTGRES_USER: payflow
      POSTGRES_PASSWORD: payflow123
    ports:
      - "5434:5432"
    volumes:
      - postgres_merchant_data:/var/lib/postgresql/data
    networks:
      - payflow-network
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payflow -d payflow_merchant"]
      interval: 10s
      timeout: 5s
      retries: 5

  postgres-payment:
    image: postgres:15-alpine
    container_name: payflow-postgres-payment
    environment:
      POSTGRES_DB: payflow_payment
      POSTGRES_USER: payflow
      POSTGRES_PASSWORD: payflow123
    ports:
      - "5435:5432"
    volumes:
      - postgres_payment_data:/var/lib/postgresql/data
    networks:
      - payflow-network
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payflow -d payflow_payment"]
      interval: 10s
      timeout: 5s
      retries: 5

  postgres-settlement:
    image: postgres:15-alpine
    container_name: payflow-postgres-settlement
    environment:
      POSTGRES_DB: payflow_settlement
      POSTGRES_USER: payflow
      POSTGRES_PASSWORD: payflow123
    ports:
      - "5436:5432"
    volumes:
      - postgres_settlement_data:/var/lib/postgresql/data
    networks:
      - payflow-network

  # ============ REDIS ============
  redis:
    image: redis:7-alpine
    container_name: payflow-redis
    ports:
      - "6379:6379"
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
    volumes:
      - redis_data:/data
    networks:
      - payflow-network
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # ============ KAFKA ============
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: payflow-zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    networks:
      - payflow-network

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
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    networks:
      - payflow-network
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 30s
      timeout: 10s
      retries: 5

  # ============ DYNAMODB LOCAL ============
  dynamodb:
    image: amazon/dynamodb-local:latest
    container_name: payflow-dynamodb
    ports:
      - "8000:8000"
    command: "-jar DynamoDBLocal.jar -sharedDb"
    networks:
      - payflow-network

networks:
  payflow-network:
    driver: bridge

volumes:
  postgres_identity_data:
  postgres_merchant_data:
  postgres_payment_data:
  postgres_settlement_data:
  redis_data:
```

## Docker Compose - Services

```yaml
# docker/docker-compose-services.yml
version: '3.8'

services:
  api-gateway:
    build:
      context: ../backend/api-gateway
      dockerfile: Dockerfile
    container_name: payflow-gateway
    ports:
      - "8080:8080"
    environment:
      - REDIS_HOST=redis
      - JWT_SECRET=${JWT_SECRET}
    depends_on:
      redis:
        condition: service_healthy
    networks:
      - payflow-network

  identity-service:
    build:
      context: ../backend/identity-service
      dockerfile: Dockerfile
    container_name: payflow-identity
    ports:
      - "8081:8081"
    environment:
      - DB_HOST=postgres-identity
      - DB_USERNAME=payflow
      - DB_PASSWORD=payflow123
      - JWT_SECRET=${JWT_SECRET}
    depends_on:
      postgres-identity:
        condition: service_healthy
    networks:
      - payflow-network

  payment-service:
    build:
      context: ../backend/payment-service
      dockerfile: Dockerfile
    container_name: payflow-payment
    ports:
      - "8082:8082"
    environment:
      - DB_HOST=postgres-payment
      - REDIS_HOST=redis
      - KAFKA_SERVERS=kafka:29092
      - ROUTING_HOST=routing-service
      - MERCHANT_HOST=merchant-service
    depends_on:
      postgres-payment:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    networks:
      - payflow-network

  bank-simulator:
    build:
      context: ../backend/bank-simulator
      dockerfile: Dockerfile
    container_name: payflow-bank
    ports:
      - "8086:8086"
      - "9090:9090"
    networks:
      - payflow-network

networks:
  payflow-network:
    external: true
    name: docker_payflow-network
```

## Init Scripts

```sql
-- docker/init-scripts/create-topics.sh
#!/bin/bash
kafka-topics --create --topic payment.created --partitions 6 --replication-factor 1 --bootstrap-server kafka:29092
kafka-topics --create --topic payment.authorized --partitions 6 --replication-factor 1 --bootstrap-server kafka:29092
kafka-topics --create --topic payment.captured --partitions 6 --replication-factor 1 --bootstrap-server kafka:29092
kafka-topics --create --topic payment.failed --partitions 6 --replication-factor 1 --bootstrap-server kafka:29092
kafka-topics --create --topic refund.initiated --partitions 3 --replication-factor 1 --bootstrap-server kafka:29092
kafka-topics --create --topic refund.completed --partitions 3 --replication-factor 1 --bootstrap-server kafka:29092
kafka-topics --create --topic settlement.completed --partitions 3 --replication-factor 1 --bootstrap-server kafka:29092
```

## Networking Diagram

```
┌────────────────── payflow-network (bridge) ──────────────────┐
│                                                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Gateway  │  │ Identity │  │ Payment  │  │ Merchant │    │
│  │ :8080    │  │ :8081    │  │ :8082    │  │ :8083    │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│                                                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Routing  │  │ Bank Sim │  │ Settle   │  │ Webhook  │    │
│  │ :8084    │  │ :9090    │  │ :8085    │  │ :8087    │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│                                                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ PG-ID    │  │ PG-Merch │  │ PG-Pay   │  │ Redis    │    │
│  │ :5432    │  │ :5432    │  │ :5432    │  │ :6379    │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│                                                                │
│  ┌──────────┐  ┌──────────┐                                  │
│  │ Kafka    │  │Zookeeper │                                  │
│  │ :29092   │  │ :2181    │                                  │
│  └──────────┘  └──────────┘                                  │
└────────────────────────────────────────────────────────────────┘
```

## Useful Commands

```bash
# Start all infrastructure
docker compose -f docker/docker-compose.yml up -d

# Build and start all services
docker compose -f docker/docker-compose-services.yml up --build -d

# View logs for specific service
docker logs -f payflow-payment

# Check service health
docker compose ps

# Stop everything
docker compose -f docker/docker-compose.yml down
docker compose -f docker/docker-compose-services.yml down

# Reset all data
docker compose down -v  # removes volumes too

# Rebuild single service
docker compose -f docker/docker-compose-services.yml up --build payment-service
```
