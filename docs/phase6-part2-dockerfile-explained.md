# Phase 6 · Part 2 — Dockerfile Explained (Multi-Stage Builds)

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 6 — Containerization |
| **Part** | 2 — Dockerfile Deep Dive |
| **Previous** | [Part 1 — Docker Concepts](phase6-part1-docker-concepts.md) |
| **Next** | [Part 3 — Docker Compose](phase6-part3-docker-compose.md) |
| **Time** | ~2 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | Docker basics, Maven build process |

---

## Table of Contents

1. [What is a Dockerfile?](#1-what-is-a-dockerfile)
2. [Multi-Stage Build Concept](#2-multi-stage-build-concept)
3. [PayFlow Dockerfile Line-by-Line](#3-payflow-dockerfile-line-by-line)
4. [Layer Caching Strategy](#4-layer-caching-strategy)
5. [Build, Run, and Verify](#5-build-run-and-verify)
6. [.dockerignore File](#6-dockerignore-file)
7. [What You Learned](#what-you-learned)
8. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. What is a Dockerfile?

A Dockerfile is a text file containing instructions to build a Docker image. Think of it as a recipe — each line is a step.

```
Dockerfile Instructions Flow:
┌──────────────────────────────────────────┐
│ FROM    → Base image (starting point)    │
│ COPY    → Add files from your machine    │
│ RUN     → Execute commands (install deps)│
│ EXPOSE  → Document which port app uses   │
│ CMD     → Default command when started   │
└──────────────────────────────────────────┘
```

### Common Instructions

| Instruction | Purpose | Example |
|-------------|---------|---------|
| `FROM` | Set base image | `FROM eclipse-temurin:17-jre-alpine` |
| `WORKDIR` | Set working directory | `WORKDIR /app` |
| `COPY` | Copy files into image | `COPY target/*.jar app.jar` |
| `RUN` | Execute command during build | `RUN apk add --no-cache curl` |
| `ENV` | Set environment variable | `ENV JAVA_OPTS="-Xmx512m"` |
| `EXPOSE` | Document port | `EXPOSE 8080` |
| `ENTRYPOINT` | Main command (not overridable) | `ENTRYPOINT ["java", "-jar"]` |
| `CMD` | Default args (overridable) | `CMD ["app.jar"]` |
| `ARG` | Build-time variable | `ARG JAR_FILE=*.jar` |

---

## 2. Multi-Stage Build Concept

### Why Multi-Stage? (Smaller Images!)

```
SINGLE-STAGE (BAD):                 MULTI-STAGE (GOOD):
+---------------------------+       +---------------------------+
| JDK 17 (300MB)           |       | Stage 1: BUILD            |
| Maven (200MB)            |       | JDK 17 + Maven            |
| .m2 cache (500MB)        |       | Compile → produce JAR     |
| Source code (50MB)       |       | (discarded after build)   |
| Compiled JAR (80MB)      |       +---------------------------+
+---------------------------+       | Stage 2: RUN              |
| TOTAL: ~1.1 GB ❌        |       | JRE 17 Alpine (150MB)     |
+---------------------------+       | Only the JAR file (80MB)  |
                                    +---------------------------+
                                    | TOTAL: ~230 MB ✅         |
                                    +---------------------------+
```

**Multi-stage builds use multiple `FROM` statements.** Only the final stage becomes the image. Previous stages are build tools that get thrown away.

---

## 3. PayFlow Dockerfile Line-by-Line

This is the actual Dockerfile pattern used by PayFlow services. The build context is `backend/` (the entire multi-module project):

```dockerfile
# ╔══════════════════════════════════════════════════════════════════╗
# ║  STAGE 1: BUILD — Compile the application with Maven            ║
# ╚══════════════════════════════════════════════════════════════════╝

# Use Maven image which includes JDK 17
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# ─── Copy ALL modules ──────────────────────────────────────────────
# Maven's parent pom declares all modules in its reactor.
# All must be present for Maven to resolve the project structure.
COPY pom.xml .
COPY common-lib ./common-lib
COPY service-registry ./service-registry
COPY config-server ./config-server
COPY api-gateway ./api-gateway
COPY identity-service ./identity-service
COPY merchant-service ./merchant-service
COPY payment-service ./payment-service
COPY routing-service ./routing-service
COPY settlement-service ./settlement-service
COPY webhook-service ./webhook-service
COPY notification-service ./notification-service
COPY bank-simulator ./bank-simulator

# Build ONLY the target service and its dependencies
# -pl = project list, -am = also-make, -B = batch mode
RUN mvn clean package -pl payment-service -am -DskipTests -B

# ╔══════════════════════════════════════════════════════════════════╗
# ║  STAGE 2: RUN — Create minimal runtime image                    ║
# ╚══════════════════════════════════════════════════════════════════╝

# Use JRE only (not full JDK) — no compiler needed at runtime
FROM eclipse-temurin:17-jre-alpine

# Create a non-root user for security
RUN addgroup -S payflow && adduser -S payflow -G payflow
WORKDIR /app
USER payflow

# Copy ONLY the built JAR from the build stage
COPY --from=builder /app/payment-service/target/*.jar app.jar

# Document that this container listens on port 8083
EXPOSE 8083

# Health check — Docker can verify container is healthy
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8083/actuator/health || exit 1

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 4. Layer Caching Strategy

Docker caches each layer. If a layer hasn't changed, Docker reuses the cached version.

```
BUILD PROCESS (first time):          BUILD PROCESS (code change only):
┌─────────────────────────┐          ┌─────────────────────────┐
│ FROM temurin:17-jdk     │ ←cached  │ FROM temurin:17-jdk     │ ←CACHED ✓
├─────────────────────────┤          ├─────────────────────────┤
│ COPY pom.xml            │ ←built   │ COPY pom.xml            │ ←CACHED ✓
├─────────────────────────┤          ├─────────────────────────┤
│ RUN mvn dependency:...  │ ←built   │ RUN mvn dependency:...  │ ←CACHED ✓
├─────────────────────────┤          ├─────────────────────────┤
│ COPY src ./src          │ ←built   │ COPY src ./src          │ ←CHANGED!
├─────────────────────────┤          ├─────────────────────────┤
│ RUN mvn package         │ ←built   │ RUN mvn package         │ ←REBUILT
└─────────────────────────┘          └─────────────────────────┘
Total: 5 min                         Total: 30 sec (deps cached!)
```

**Key principle:** Put things that change LESS at the TOP. Put things that change MORE at the BOTTOM.

| Layer Order | Changes | Cache Benefit |
|-------------|---------|---------------|
| 1. Base image | Rarely | Almost always cached |
| 2. pom.xml | Weekly | Dependencies only redownload when POM changes |
| 3. Source code | Every commit | Only this and below rebuild |
| 4. Build command | Every commit | Always rebuilds with new source |

---

## 5. Build, Run, and Verify

### Build the Image

```bash
# Navigate to the service directory
cd backend/payment-service

# Build the Docker image
docker build -t payflow/payment-service:1.0.0 .

# Verify it was created
docker images | grep payflow
# payflow/payment-service  1.0.0  abc123  2 min ago  234MB
```

### Run the Container

```bash
# Run with required environment variables
docker run -d \
  --name payment-service \
  -p 8082:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/payflow_payments \
  -e SPRING_DATASOURCE_USERNAME=payflow \
  -e SPRING_DATASOURCE_PASSWORD=secret123 \
  -e SPRING_PROFILES_ACTIVE=docker \
  payflow/payment-service:1.0.0
```

### Verify It's Healthy

```bash
# Check container status
docker ps
# CONTAINER ID  IMAGE                         STATUS                   PORTS
# f1e2d3c4b5a6  payflow/payment-service:1.0.0 Up 30s (healthy)        0.0.0.0:8082->8080/tcp

# Check logs
docker logs -f payment-service

# Test health endpoint
curl http://localhost:8082/actuator/health
# {"status":"UP"}
```

---

## 6. .dockerignore File

Like `.gitignore`, `.dockerignore` tells Docker which files NOT to copy into the build context.

```
# .dockerignore

# IDE files
.idea/
*.iml
.vscode/

# Build output (we build inside Docker)
target/

# Git
.git/
.gitignore

# Docker files (don't copy ourselves)
Dockerfile
docker-compose*.yml
.dockerignore

# Documentation
docs/
*.md

# OS files
.DS_Store
Thumbs.db

# Environment files (secrets!)
.env
*.env.local
```

**Why this matters:**
- Smaller build context = faster `docker build`
- Don't leak secrets (`.env` files) into images
- Avoid unnecessary cache invalidation

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Dockerfile purpose | Recipe to build a Docker image step by step |
| 2 | Multi-stage builds | Build in one stage, run in another → smaller images |
| 3 | Layer caching | Order instructions by change frequency for fast rebuilds |
| 4 | Security | Non-root user, no secrets in images |
| 5 | Health checks | Docker monitors container health via HEALTHCHECK |
| 6 | .dockerignore | Exclude unnecessary files from build context |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `COPY failed: file not found` | Incorrect path or file in .dockerignore | Check paths relative to Dockerfile location |
| Build takes forever (downloads deps every time) | pom.xml and source copied together | Split: copy pom.xml first, then `dependency:go-offline`, then source |
| Image is 1GB+ | Using JDK instead of JRE in final stage | Use `eclipse-temurin:17-jre-alpine` for runtime |
| `Permission denied` at runtime | Running as root, file owned by root | `chown` files to non-root user before `USER` switch |
| Health check fails | App not ready within timeout | Increase `--interval` and `--start-period` |
| `mvn: command not found` | Base image doesn't include Maven | Use `maven:3.9-eclipse-temurin-17-alpine` as build base or install Maven |

---

<div align="center">

**[← Part 1: Docker Concepts](phase6-part1-docker-concepts.md)** | **[Documentation Index](../README.md)** | **[Part 3: Docker Compose →](phase6-part3-docker-compose.md)**

</div>
