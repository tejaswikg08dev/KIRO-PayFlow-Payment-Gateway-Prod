# Phase 6 · Part 1 — Docker Concepts & Fundamentals

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 6 — Containerization |
| **Part** | 1 — Docker Concepts |
| **Previous** | [Phase 5 — Testing](phase5-testing.md) |
| **Next** | [Part 2 — Dockerfile Explained](phase6-part2-dockerfile-explained.md) |
| **Time** | ~1.5 hours |
| **Difficulty** | ★★☆☆☆ Beginner |
| **Prerequisites** | Basic command line, understanding of application deployment |

---

## Table of Contents

1. [What is Docker?](#1-what-is-docker)
2. [Container vs VM](#2-container-vs-vm)
3. [Image vs Container](#3-image-vs-container)
4. [Docker Vocabulary](#4-docker-vocabulary)
5. [Basic Commands](#5-basic-commands)
6. [Hands-On: Run PostgreSQL in Docker](#6-hands-on-run-postgresql-in-docker)
7. [What You Learned](#what-you-learned)
8. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. What is Docker?

**The Shipping Container Analogy:**

Before shipping containers, cargo was loaded individually — barrels, crates, sacks — each handled differently. Shipping containers standardized everything: same size box, same cranes, fits on any ship or truck.

```
BEFORE DOCKER:                        WITH DOCKER:
+--------+  +--------+               +------------------+
| Java 8 |  | Java 17|               | Container A      |
| + libs  |  | + libs |               | Java 17 + App    |
| + App A |  | + App B|               +------------------+
+----+----+  +----+---+               +------------------+
     |            |                   | Container B      |
     v            v                   | Python 3.11 +App |
+---------------------------+         +------------------+
|    Server OS (conflicts!) |         +------------------+
+---------------------------+         |    Docker Engine  |
                                      +------------------+
                                      |    Host OS        |
                                      +------------------+
```

Docker packages your application WITH its dependencies into a standardized unit called a **container**. It runs the same way everywhere — your laptop, CI server, production.

**Why PayFlow uses Docker:**
- 11 microservices — each in its own container
- Consistent environments (no "works on my machine")
- Easy scaling — run multiple instances
- Simple deployment — ship containers, not code

---

## 2. Container vs VM

```
VIRTUAL MACHINES                    CONTAINERS
+-------+ +-------+ +-------+      +-------+ +-------+ +-------+
| App A | | App B | | App C |      | App A | | App B | | App C |
+-------+ +-------+ +-------+      +-------+ +-------+ +-------+
| Bins/ | | Bins/ | | Bins/ |      | Bins/ | | Bins/ | | Bins/ |
| Libs  | | Libs  | | Libs  |      | Libs  | | Libs  | | Libs  |
+-------+ +-------+ +-------+      +-------+-+-------+-+-------+
| Guest | | Guest | | Guest |      |       Docker Engine        |
|  OS   | |  OS   | |  OS   |      +----------------------------+
+-------+-+-------+-+-------+      |         Host OS            |
|       Hypervisor          |      +----------------------------+
+----------------------------+      |        Hardware            |
|         Host OS            |      +----------------------------+
+----------------------------+
|        Hardware            |
+----------------------------+
```

| Feature | VM | Container |
|---------|-----|-----------|
| Boot time | Minutes | Seconds |
| Size | GBs (full OS) | MBs (just app + deps) |
| Isolation | Complete (separate kernel) | Process-level (shared kernel) |
| Performance | ~5-10% overhead | Near-native |
| Density | ~10 per host | ~100s per host |
| Use case | Different OS needs | Same OS, different apps |

**For PayFlow:** Containers are ideal — all services run on Linux, we want fast startup, and we need to run 11+ services on a single development machine.

---

## 3. Image vs Container

Think of it as a **recipe vs a cooked dish**:

| Concept | Analogy | Docker |
|---------|---------|--------|
| Recipe | Blueprint | **Image** — read-only template |
| Cooked dish | Running instance | **Container** — live process from an image |
| Cookbook | Collection of recipes | **Registry** (Docker Hub, ECR) |

```
+---------------------+        docker run         +---------------------+
|      IMAGE          | ----------------------->  |     CONTAINER       |
| (read-only layers)  |                           | (image + write layer)|
|                     |                           | (running process)    |
| - Java 17 base     |    Can create MANY         | - PID 1: java -jar  |
| - App dependencies  |    containers from        | - Port 8080 open    |
| - Application jar   |    ONE image              | - Logs streaming    |
+---------------------+                           +---------------------+
```

**Key insight:** One image → many containers. Just like one recipe → many dishes.

---

## 4. Docker Vocabulary

| Term | Definition | PayFlow Example |
|------|-----------|-----------------|
| **Image** | Read-only template with app + dependencies | `payflow/payment-service:1.0.0` |
| **Container** | Running instance of an image | Payment service process on port 8082 |
| **Dockerfile** | Text file with instructions to build an image | `backend/payment-service/Dockerfile` |
| **Layer** | Each instruction creates a cached layer | `COPY pom.xml` = one layer |
| **Registry** | Store for Docker images | AWS ECR, Docker Hub |
| **Volume** | Persistent storage outside container | PostgreSQL data directory |
| **Network** | Virtual network connecting containers | `backend-net` for service-to-service |
| **Compose** | Tool to run multi-container apps | `docker-compose.yml` — all 11 services |
| **Tag** | Version label for an image | `latest`, `1.0.0`, `abc123` |
| **Port mapping** | Host port → container port | `-p 8080:8080` |

---

## 5. Basic Commands

### Essential Docker Commands

```bash
# ─── Images ───────────────────────────────────────────
docker images                          # List all local images
docker pull postgres:15                # Download image from registry
docker build -t myapp:1.0 .           # Build image from Dockerfile
docker rmi myapp:1.0                  # Remove an image

# ─── Containers ───────────────────────────────────────
docker run -d --name mydb postgres:15  # Run container in background
docker ps                              # List running containers
docker ps -a                           # List ALL containers (incl. stopped)
docker logs mydb                       # View container logs
docker logs -f mydb                    # Follow logs (live)
docker stop mydb                       # Stop container gracefully
docker start mydb                      # Start stopped container
docker rm mydb                         # Remove stopped container
docker exec -it mydb bash             # Open shell inside container

# ─── Cleanup ──────────────────────────────────────────
docker system prune                    # Remove unused data
docker volume prune                    # Remove unused volumes
```

### Command Flow Diagram

```
docker build → IMAGE → docker run → CONTAINER → docker stop → docker rm
                ↑                        |
                |                        ↓
           Dockerfile              docker logs
                                   docker exec
```

---

## 6. Hands-On: Run PostgreSQL in Docker

Let's run the same database PayFlow uses — in a single command.

### Step 1: Pull and Run

```bash
docker run -d \
  --name payflow-postgres \
  -e POSTGRES_DB=payflow_payments \
  -e POSTGRES_USER=payflow \
  -e POSTGRES_PASSWORD=secret123 \
  -p 5432:5432 \
  -v pgdata:/var/lib/postgresql/data \
  postgres:15
```

**Breaking it down:**

| Flag | Purpose |
|------|---------|
| `-d` | Run in background (detached) |
| `--name payflow-postgres` | Give it a memorable name |
| `-e POSTGRES_DB=...` | Set environment variable (creates DB) |
| `-p 5432:5432` | Map host port 5432 → container port 5432 |
| `-v pgdata:/var/lib/postgresql/data` | Persist data in a named volume |
| `postgres:15` | Image name and tag |

### Step 2: Verify It's Running

```bash
docker ps
# CONTAINER ID  IMAGE        STATUS         PORTS                   NAMES
# a1b2c3d4e5f6  postgres:15  Up 2 minutes   0.0.0.0:5432->5432/tcp  payflow-postgres
```

### Step 3: Connect to It

```bash
docker exec -it payflow-postgres psql -U payflow -d payflow_payments

# Inside psql:
payflow_payments=# \dt          -- list tables (empty for now)
payflow_payments=# \q           -- quit
```

### Step 4: Clean Up

```bash
docker stop payflow-postgres
docker rm payflow-postgres
# Volume 'pgdata' persists — data is safe
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Docker purpose | Packages app + dependencies into portable containers |
| 2 | Container vs VM | Containers are lighter, faster, share host kernel |
| 3 | Image vs Container | Image = blueprint (read-only), Container = running instance |
| 4 | Vocabulary | Image, container, Dockerfile, layer, volume, network, registry |
| 5 | Basic commands | `run`, `ps`, `logs`, `stop`, `rm`, `exec`, `build` |
| 6 | Hands-on | Run PostgreSQL in Docker with one command |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `docker: command not found` | Docker not installed | Install Docker Desktop |
| `Cannot connect to the Docker daemon` | Docker Desktop not running | Start Docker Desktop app |
| `port is already allocated` | Another process on that port | Use different host port: `-p 5433:5432` |
| `image not found` | Typo in image name or no internet | Check name, ensure `docker pull` works |
| `permission denied` | Linux: user not in docker group | `sudo usermod -aG docker $USER` then logout/login |
| Container exits immediately | App crashes on start | Check `docker logs <container>` for error |

---

<div align="center">

**[← Phase 5: Testing](phase5-testing.md)** | **[Documentation Index](../README.md)** | **[Part 2: Dockerfile Explained →](phase6-part2-dockerfile-explained.md)**

</div>
