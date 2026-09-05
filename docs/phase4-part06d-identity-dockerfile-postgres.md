# 🏗️ Phase 4 Part 6d: Identity Service — Dockerfile, PostgreSQL & Prerequisites

> **"Code that can't run is just a wish. This doc makes it real — from database to Docker."**

---

## 📋 Document Metadata

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Implementation |
| **Part** | 6d — Dockerfile, PostgreSQL Setup & Prerequisites |
| **Previous** | [Part 6c — Controller & Tests](./phase4-part06c-identity-controller-tests.md) |
| **Next** | [Part 6e — Connections & Flows](./phase4-part06e-identity-connections-and-flows.md) |

---

## 📖 Table of Contents

1. [What This Part Covers](#1-what-this-part-covers)
2. [Prerequisites — Everything You Need Before Running](#2-prerequisites--everything-you-need-before-running)
3. [PostgreSQL Setup — Option A: Docker Compose (Recommended)](#3-postgresql-setup--option-a-docker-compose-recommended)
4. [PostgreSQL Setup — Option B: Local Install](#4-postgresql-setup--option-b-local-install)
5. [How init-db.sql Creates All Databases](#5-how-init-dbsql-creates-all-databases)
6. [Step-by-Step: Dockerfile](#6-step-by-step-dockerfile)
7. [Building and Running with Docker](#7-building-and-running-with-docker)
8. [Running Without Docker (Local Development)](#8-running-without-docker-local-development)
9. [Startup Verification Checklist](#9-startup-verification-checklist)
10. [Troubleshooting Common Errors](#10-troubleshooting-common-errors)
11. [What You Learned](#11-what-you-learned)

---

## 1. What This Part Covers

In Parts 6a-6c, you wrote all the code. But code alone doesn't run — you need:

| Need | What | This Part Covers |
|---|---|---|
| **Database** | PostgreSQL with `payflow_identity` database | How to set up PostgreSQL (Docker or local) |
| **Infrastructure** | Eureka and Config Server (optional for local dev) | What's required vs optional |
| **Containerization** | Docker image for deployment | Dockerfile line-by-line |
| **Verification** | Proof it works | Startup checklist + troubleshooting |

---

## 2. Prerequisites — Everything You Need Before Running

### Required

| Prerequisite | Why | How to Check |
|---|---|---|
| **Java 17** | The application is built with Java 17 | `java -version` → should show 17.x |
| **Maven 3.9+** | Builds the project | `mvn -version` → should show 3.9.x |
| **PostgreSQL 15+** | Stores users and refresh tokens | See Section 3 or 4 below |
| **common-lib built** | Identity Service depends on it | `cd backend && mvn install -pl common-lib -am -DskipTests` |

### Optional (for local development)

| Prerequisite | Why | What If Missing |
|---|---|---|
| **Service Registry (Eureka)** | Service registration | App logs a warning but starts fine |
| **Config Server** | Centralized config | `optional:configserver:` in yml → uses local application.yml |
| **Docker** | Containerization | You can run with `mvn spring-boot:run` instead |
| **Redis** | Rate limiting at API Gateway | Identity Service doesn't use Redis directly |

### Build Order

```
1. Build common-lib FIRST (Identity Service depends on it):
   cd backend
   mvn install -pl common-lib -am -DskipTests

2. Then build identity-service:
   cd identity-service
   mvn clean package -DskipTests

3. Or build everything:
   cd backend
   mvn clean install -DskipTests
```

---

## 3. PostgreSQL Setup — Option A: Docker Compose (Recommended)

Your project already has a `docker-compose.yml` at `infra/docker/` that starts PostgreSQL with all databases pre-created.

### Step 1: Install Docker Desktop

Download from: https://www.docker.com/products/docker-desktop/

After install, verify:
```powershell
docker --version
# Docker version 24.x.x
docker-compose --version
# Docker Compose version v2.x.x
```

### Step 2: Start PostgreSQL

```powershell
cd payflow-payment-gateway\infra\docker
docker-compose up -d postgres
```

**What this does:**
1. Pulls `postgres:15-alpine` image (~80MB)
2. Creates container `payflow-postgres` on port 5432
3. Runs `init-db.sql` automatically (creates ALL databases)
4. Data persists in `postgres-data` volume (survives container restart)

### Step 3: Verify

```powershell
docker ps
# NAMES             STATUS          PORTS
# payflow-postgres   Up 10 seconds   0.0.0.0:5432->5432/tcp
```

```powershell
# Connect and verify databases exist:
docker exec -it payflow-postgres psql -U payflow -d payflow -c "\l"
#                 Name               | Owner
# payflow                            | payflow
# payflow_identity                   | payflow    ← THIS ONE
# payflow_merchant                   | payflow
# payflow_payment                    | payflow
# payflow_settlement                 | payflow
```

### Docker Compose PostgreSQL Configuration Explained

```yaml
services:
  postgres:
    image: postgres:15-alpine           # PostgreSQL 15 on Alpine Linux (small image)
    container_name: payflow-postgres    # Fixed name (instead of random)
    ports:
      - "5432:5432"                     # Host port:Container port
    environment:
      POSTGRES_USER: payflow            # Superuser name
      POSTGRES_PASSWORD: payflow123     # Superuser password
      POSTGRES_DB: payflow              # Default database (created on first start)
    volumes:
      - postgres-data:/var/lib/postgresql/data    # Persist data across restarts
      - ./init-db.sql:/docker-entrypoint-initdb.d/init-db.sql  # Run on first start
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payflow"]  # Check if PostgreSQL is ready
      interval: 10s
      timeout: 5s
      retries: 5
```

**Key:** The `init-db.sql` file is mounted into `/docker-entrypoint-initdb.d/` — PostgreSQL automatically executes any `.sql` file in that directory on FIRST startup only.

---

## 4. PostgreSQL Setup — Option B: Local Install

If you prefer not to use Docker:

### Step 1: Download and Install

Download from: https://www.postgresql.org/download/windows/

During installation:
- Port: **5432** (default)
- Superuser password: remember it (e.g., `postgres`)
- Check **pgAdmin** (GUI tool)

### Step 2: Create Database and User

Open **pgAdmin** or **SQL Shell (psql)** and run:

```sql
-- Create user
CREATE USER payflow WITH PASSWORD 'payflow123';

-- Create database
CREATE DATABASE payflow_identity OWNER payflow;

-- Grant permissions (required for PostgreSQL 15+)
\c payflow_identity
GRANT ALL ON SCHEMA public TO payflow;
```

### Step 3: Verify Connection

```powershell
# Test connection (if psql is in PATH):
psql -h localhost -p 5432 -U payflow -d payflow_identity -c "SELECT 1"
```

### application.yml Connection (Already Set)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/payflow_identity
    username: payflow
    password: payflow123
```

These match what we just created. If you used a different password, update the yml.

---

## 5. How init-db.sql Creates All Databases

**File:** `infra/docker/init-db.sql`

```sql
-- PayFlow Payment Gateway - Database Initialization
-- Creates separate databases for each microservice

CREATE DATABASE payflow_identity;
CREATE DATABASE payflow_merchant;
CREATE DATABASE payflow_payment;
CREATE DATABASE payflow_settlement;

-- Grant all privileges to the payflow user
GRANT ALL PRIVILEGES ON DATABASE payflow_identity TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_merchant TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_payment TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_settlement TO payflow;

-- Connect to each database and grant schema privileges
\c payflow_identity
GRANT ALL ON SCHEMA public TO payflow;

\c payflow_merchant
GRANT ALL ON SCHEMA public TO payflow;

\c payflow_payment
GRANT ALL ON SCHEMA public TO payflow;

\c payflow_settlement
GRANT ALL ON SCHEMA public TO payflow;
```

**WHY SEPARATE DATABASES?**
```
❌ Shared database:
  All services read/write same tables → tight coupling
  Identity bug could corrupt merchant data

✅ Database per service (our approach):
  payflow_identity  → only Identity Service touches this
  payflow_merchant  → only Merchant Service touches this
  payflow_payment   → only Payment Service touches this
  payflow_settlement → only Settlement Service touches this

  Services can't accidentally read/write each other's data.
```

**WHY `GRANT ALL ON SCHEMA public`?**
PostgreSQL 15+ changed default permissions. Without this grant, the `payflow` user can connect to the database but can't create tables in the `public` schema → Flyway migrations fail.

---

## 6. Step-by-Step: Dockerfile

**File:** `backend/identity-service/Dockerfile`

### Line-by-Line

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
```

| Part | Meaning |
|---|---|
| `FROM` | Start with this base image |
| `maven:3.9-eclipse-temurin-17` | Image with Maven 3.9 + JDK 17 (can compile Java) |
| `AS builder` | Name this stage "builder" (referenced in stage 2) |

```dockerfile
WORKDIR /app
```

All commands run inside `/app` directory.

```dockerfile
# Copy parent pom and all modules (Maven reactor requires all to be present)
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
```

**WHY COPY ALL MODULES?** The parent `pom.xml` lists all modules in `<modules>`. Maven's reactor needs every module directory to exist, even if we only build one. Without them: `Could not find artifact com.payflow:common-lib`.

**BUILD CONTEXT:** The `docker build` command is run from `backend/` directory, so all paths are relative to `backend/`.

```dockerfile
# Build only the target service and its dependencies
RUN mvn clean package -pl identity-service -am -DskipTests -B
```

| Flag | Meaning |
|---|---|
| `clean` | Delete previous build artifacts |
| `package` | Compile + package into JAR |
| `-pl identity-service` | Only build identity-service module |
| `-am` | Also build dependencies (common-lib) |
| `-DskipTests` | Don't run tests (faster build) |
| `-B` | Batch mode (non-interactive, cleaner output) |

**Result:** `identity-service/target/identity-service-1.0.0-SNAPSHOT.jar` (fat JAR, ~60MB)

```dockerfile
# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
```

**NEW BASE IMAGE:** JRE only (no JDK, no Maven). Alpine Linux = minimal OS.

| Stage | Image Size | Contains |
|---|---|---|
| Stage 1 (builder) | ~800MB | JDK + Maven + source code + all dependencies |
| Stage 2 (runtime) | ~180MB | JRE only — just enough to run Java |

**The final Docker image is Stage 2 only.** Stage 1 is discarded.

```dockerfile
WORKDIR /app
RUN addgroup -S payflow && adduser -S payflow -G payflow
USER payflow
```

**SECURITY — Non-root user:**
1. Create group `payflow`
2. Create user `payflow` in that group
3. Switch to that user

**WHY?** If the app has a vulnerability, an attacker running as root can escape the container. Running as `payflow` (non-root) limits damage.

```dockerfile
COPY --from=builder /app/identity-service/target/*.jar app.jar
```

**`--from=builder`:** Copy the JAR from Stage 1 into Stage 2. Only the JAR crosses the stage boundary — no source code, no Maven, no JDK.

```dockerfile
EXPOSE 8081
```

**Documentation only** — tells humans/tools this container listens on 8081. Doesn't actually open the port (you do that with `docker run -p`).

```dockerfile
HEALTHCHECK --interval=15s --timeout=10s --retries=5 --start-period=30s \
    CMD wget -qO- http://localhost:8081/actuator/health || exit 1
```

| Parameter | Meaning |
|---|---|
| `--interval=15s` | Check every 15 seconds |
| `--timeout=10s` | If check takes >10s, it failed |
| `--retries=5` | 5 failures in a row → mark unhealthy |
| `--start-period=30s` | Wait 30s after start (JVM needs time to boot) |
| `CMD wget...` | Call `/actuator/health` — if `{"status":"UP"}` → healthy |

**WHO USES THIS?** Docker and Kubernetes use health checks to decide whether to restart containers.

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
```

When the container starts, run: `java -jar app.jar` → starts Spring Boot on port 8081.

---

## 7. Building and Running with Docker

### Build the Image

```powershell
# From the backend/ directory:
cd payflow-payment-gateway\backend
docker build -t payflow/identity-service:latest -f identity-service/Dockerfile .
```

| Part | Meaning |
|---|---|
| `-t payflow/identity-service:latest` | Tag (name) the image |
| `-f identity-service/Dockerfile` | Use this Dockerfile |
| `.` | Build context is current directory (backend/) |

### Run the Container

```powershell
docker run -d \
  --name identity-service \
  -p 8081:8081 \
  -e SPRING_PROFILES_ACTIVE=docker \
  --network payflow-network \
  payflow/identity-service:latest
```

| Flag | Meaning |
|---|---|
| `-d` | Detached mode (runs in background) |
| `--name` | Container name |
| `-p 8081:8081` | Map host port to container port |
| `-e SPRING_PROFILES_ACTIVE=docker` | Activate Docker profile (uses Docker hostnames) |
| `--network` | Join the Docker network (so it can reach PostgreSQL by name) |

### Check Logs

```powershell
docker logs identity-service
# Look for: "Started IdentityServiceApplication in X seconds"
```

---

## 8. Running Without Docker (Local Development)

For daily development, Docker isn't needed. Just use Maven:

### Step 1: Ensure PostgreSQL is running

```powershell
# If using Docker Compose:
cd infra\docker
docker-compose up -d postgres

# If using local PostgreSQL: just make sure it's running
```

### Step 2: Build common-lib (if not done)

```powershell
cd backend
mvn install -pl common-lib -am -DskipTests
```

### Step 3: Run the service

```powershell
cd identity-service
mvn spring-boot:run
```

### Step 4: Verify

```powershell
# Health check:
curl http://localhost:8081/actuator/health
# Expected: {"status":"UP"}

# Register a user:
curl -s -X POST http://localhost:8081/v1/auth/register `
  -H "Content-Type: application/json" `
  -d '{"fullName":"Test User","email":"test@example.com","password":"Password1!"}' | jq
```

---

## 9. Startup Verification Checklist

After starting the Identity Service, verify each layer:

| Check | Command / Action | Expected Result |
|---|---|---|
| **PostgreSQL running** | `docker ps` or check pgAdmin | Container is up on :5432 |
| **Database exists** | `\l` in psql | `payflow_identity` listed |
| **Flyway migrations ran** | Check startup console | "Migrating to version 1...2...3...4" |
| **Hibernate validation** | Check startup console | No "Schema validation" errors |
| **Service started** | Check startup console | "Started IdentityServiceApplication in X seconds" |
| **Health endpoint** | `curl http://localhost:8081/actuator/health` | `{"status":"UP"}` |
| **Register works** | `curl POST /v1/auth/register ...` | 201 Created with tokens |
| **Login works** | `curl POST /v1/auth/login ...` | 200 OK with tokens |
| **Swagger UI** | Browser: `http://localhost:8081/swagger-ui/index.html` | API docs page loads |
| **Eureka registered** | Browser: `http://localhost:8761` | IDENTITY-SERVICE shows UP (if Eureka running) |

---

## 10. Troubleshooting Common Errors

### "Connection refused" to PostgreSQL

```
org.postgresql.util.PSQLException: Connection to localhost:5432 refused
```

**Cause:** PostgreSQL isn't running.
**Fix:**
```powershell
# Docker: 
docker-compose up -d postgres

# Local: Start PostgreSQL service
# Windows: Services → postgresql → Start
```

### "Database 'payflow_identity' does not exist"

```
FATAL: database "payflow_identity" does not exist
```

**Cause:** Database wasn't created.
**Fix:**
```sql
CREATE DATABASE payflow_identity;
GRANT ALL PRIVILEGES ON DATABASE payflow_identity TO payflow;
\c payflow_identity
GRANT ALL ON SCHEMA public TO payflow;
```

### "Permission denied for schema public"

```
ERROR: permission denied for schema public
```

**Cause:** PostgreSQL 15+ changed default permissions.
**Fix:**
```sql
\c payflow_identity
GRANT ALL ON SCHEMA public TO payflow;
```

### "Table 'users' doesn't exist" (Hibernate validation)

```
SchemaManagementException: Schema-validation: Missing table [users]
```

**Cause:** Flyway didn't run (migrations not found or failed).
**Fix:** Check that:
1. Files are in `src/main/resources/db/migration/`
2. Filenames start with `V1__`, `V2__` (double underscore)
3. `spring.flyway.enabled: true` in application.yml

### "Port already in use: 8081"

**Cause:** Another process on port 8081.
**Fix:**
```powershell
# Find what's using the port:
netstat -ano | findstr :8081
# Kill the process:
taskkill /PID <pid> /F
```

### "No qualifying bean of type 'XRepository'"

**Cause:** Repository interface not in a scanned package.
**Fix:** Ensure the repository is under `com.payflow.identity` (or a sub-package). `@SpringBootApplication` scans from its own package down.

---

## 11. What You Learned

| # | Concept | Key Takeaway |
|---|---------|-------------|
| 1 | **Database per service** | Each microservice has its own DB — no shared tables |
| 2 | **init-db.sql** | Docker Compose runs this once on first PostgreSQL start |
| 3 | **GRANT ON SCHEMA public** | Required for PostgreSQL 15+ — user needs schema access |
| 4 | **Multi-stage Docker build** | Stage 1 (800MB, builds JAR) → Stage 2 (180MB, runs JAR) |
| 5 | **COPY --from=builder** | Bridge between stages — only the JAR crosses |
| 6 | **Non-root Docker user** | Security: `adduser payflow` + `USER payflow` |
| 7 | **HEALTHCHECK** | Docker checks `/actuator/health` every 15s |
| 8 | **--start-period** | JVM needs 30s to boot — don't check too early |
| 9 | **EXPOSE** | Documentation only — doesn't open ports |
| 10 | **ENTRYPOINT** | The command Docker runs when container starts |
| 11 | **-pl and -am flags** | Build specific module + its dependencies (not everything) |
| 12 | **mvn spring-boot:run** | Local dev — no Docker needed |
| 13 | **docker-compose up -d postgres** | Start just PostgreSQL from the compose file |
| 14 | **optional:configserver:** | Service starts even if Config Server is down |

---

## 📚 Navigation

| Document | Title |
|---|---|
| [Overview](./phase4-part06-identity-service-overview.md) | Identity Service Overview |
| [Part 6a](./phase4-part06a-identity-entities.md) | Entities & Migrations |
| [Part 6b](./phase4-part06b-identity-jwt-auth.md) | JWT & Authentication |
| [Part 6c](./phase4-part06c-identity-controller-tests.md) | Controller & Tests |
| **Part 6d** | **Dockerfile, PostgreSQL & Prerequisites** (You are here) |
| [Part 6e](./phase4-part06e-identity-connections-and-flows.md) | Connections & Flows |

---

*Next: [Part 6e — How Everything Connects](./phase4-part06e-identity-connections-and-flows.md) →*
