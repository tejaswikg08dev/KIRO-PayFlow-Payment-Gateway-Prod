# Phase 8 · Part 4 — Compute & Container Registry

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 8 — AWS Deployment |
| **Part** | 4 — EC2, Docker, and ECR |
| **Previous** | [Part 3 — Database & Messaging](phase8-part3-database-messaging.md) |
| **Next** | [Part 5 — Frontend Hosting](phase8-part5-frontend-hosting.md) |
| **Time** | ~2 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | VPC, security groups, Docker basics |

---

## Table of Contents

1. [Launch EC2 Instance](#1-launch-ec2-instance)
2. [SSH into Instance](#2-ssh-into-instance)
3. [Install Docker & Docker Compose](#3-install-docker--docker-compose)
4. [Create ECR Repositories](#4-create-ecr-repositories)
5. [Authenticate Docker to ECR](#5-authenticate-docker-to-ecr)
6. [Build and Push First Image](#6-build-and-push-first-image)
7. [Pull and Run on EC2](#7-pull-and-run-on-ec2)
8. [What You Learned](#what-you-learned)
9. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Launch EC2 Instance

### Using AWS Console

1. **Console** → EC2 → **"Launch Instance"**
2. Configuration:

| Setting | Value |
|---------|-------|
| Name | `payflow-server` |
| AMI | Amazon Linux 2023 |
| Instance type | `t2.micro` (free tier) or `t3.medium` (for all services) |
| Key pair | Create new → `payflow-key` → Download `.pem` |
| VPC | `payflow-vpc` |
| Subnet | `payflow-public-1a` |
| Auto-assign public IP | Enable |
| Security group | `payflow-ec2-sg` |
| Storage | 30 GB gp3 |

3. Click **"Launch Instance"**

### Using AWS CLI

```bash
# Create key pair
aws ec2 create-key-pair \
  --key-name payflow-key \
  --query 'KeyMaterial' \
  --output text > payflow-key.pem

# Set permissions (Linux/Mac)
chmod 400 payflow-key.pem

# Launch instance
aws ec2 run-instances \
  --image-id ami-0c55b159cbfafe1f0 \
  --instance-type t2.micro \
  --key-name payflow-key \
  --security-group-ids sg-0ec2456 \
  --subnet-id subnet-0aaa111bbb222ccc \
  --associate-public-ip-address \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=payflow-server}]'
```

---

## 2. SSH into Instance

### Get Public IP

```bash
aws ec2 describe-instances \
  --filters "Name=tag:Name,Values=payflow-server" \
  --query "Reservations[0].Instances[0].PublicIpAddress" \
  --output text
# Output: 13.235.xx.xx
```

### Connect

```bash
# Linux/Mac
ssh -i payflow-key.pem ec2-user@13.235.xx.xx

# Windows (PowerShell)
ssh -i .\payflow-key.pem ec2-user@13.235.xx.xx
```

### First-Time Setup

```bash
# Update system
sudo dnf update -y

# Check instance info
cat /etc/os-release
# Amazon Linux 2023

uname -m
# x86_64
```

---

## 3. Install Docker & Docker Compose

```bash
# ─── Install Docker ──────────────────────────────────
sudo dnf install -y docker

# Start Docker service
sudo systemctl start docker
sudo systemctl enable docker

# Add ec2-user to docker group (avoid sudo for docker commands)
sudo usermod -aG docker ec2-user

# Apply group change (logout/login or use newgrp)
newgrp docker

# Verify Docker
docker --version
# Docker version 24.x.x

docker run hello-world
# ✅ Hello from Docker!

# ─── Install Docker Compose V2 ───────────────────────
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# Verify Compose
docker compose version
# Docker Compose version v2.x.x

# ─── Install AWS CLI (if not pre-installed) ──────────
# Amazon Linux 2023 usually has it pre-installed
aws --version
```

---

## 4. Create ECR Repositories

AWS Elastic Container Registry (ECR) stores your Docker images — one repository per service.

```bash
# Create repositories for all 11 services
SERVICES=(
  "api-gateway"
  "identity-service"
  "merchant-service"
  "payment-service"
  "routing-engine"
  "bank-simulator"
  "settlement-service"
  "notification-service"
  "webhook-service"
  "analytics-service"
  "reconciliation-service"
)

for svc in "${SERVICES[@]}"; do
  aws ecr create-repository \
    --repository-name "payflow-${svc}" \
    --image-scanning-configuration scanOnPush=true \
    --tags Key=Project,Value=PayFlow
  echo "✅ Created: payflow-${svc}"
done
```

### Verify Repositories

```bash
aws ecr describe-repositories --query "repositories[].repositoryName" --output table
# ┌───────────────────────────────────┐
# │       repositoryName              │
# ├───────────────────────────────────┤
# │  payflow-api-gateway              │
# │  payflow-identity-service         │
# │  payflow-merchant-service         │
# │  payflow-payment-service          │
# │  payflow-routing-engine           │
# │  payflow-bank-simulator           │
# │  payflow-settlement-service       │
# │  payflow-notification-service     │
# │  payflow-webhook-service          │
# │  payflow-analytics-service        │
# │  payflow-reconciliation-service   │
# └───────────────────────────────────┘
```

---

## 5. Authenticate Docker to ECR

ECR requires authentication — the token is valid for 12 hours.

```bash
# Get ECR login token and pipe to docker login
aws ecr get-login-password --region ap-south-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.ap-south-1.amazonaws.com

# Output: Login Succeeded ✅
```

**Registry URL format:** `{account-id}.dkr.ecr.{region}.amazonaws.com`

---

## 6. Build and Push First Image

Let's push the payment-service as an example.

### On Your Local Machine (or CI)

```bash
# Navigate to service directory
cd backend/payment-service

# Build the Docker image
docker build -t payflow-payment-service:1.0.0 .

# Tag for ECR
docker tag payflow-payment-service:1.0.0 \
  123456789012.dkr.ecr.ap-south-1.amazonaws.com/payflow-payment-service:1.0.0

docker tag payflow-payment-service:1.0.0 \
  123456789012.dkr.ecr.ap-south-1.amazonaws.com/payflow-payment-service:latest

# Push to ECR
docker push 123456789012.dkr.ecr.ap-south-1.amazonaws.com/payflow-payment-service:1.0.0
docker push 123456789012.dkr.ecr.ap-south-1.amazonaws.com/payflow-payment-service:latest
```

### Verify in ECR

```bash
aws ecr list-images --repository-name payflow-payment-service
# {
#   "imageIds": [
#     {"imageTag": "1.0.0", "imageDigest": "sha256:abc123..."},
#     {"imageTag": "latest", "imageDigest": "sha256:abc123..."}
#   ]
# }
```

---

## 7. Pull and Run on EC2

SSH into your EC2 instance and pull the image:

```bash
# On EC2 — authenticate to ECR
aws ecr get-login-password --region ap-south-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.ap-south-1.amazonaws.com

# Pull the image
docker pull 123456789012.dkr.ecr.ap-south-1.amazonaws.com/payflow-payment-service:latest

# Run it
docker run -d \
  --name payment-service \
  -p 8082:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://payflow-db.xxx.rds.amazonaws.com:5432/payflow_payments \
  -e SPRING_DATASOURCE_USERNAME=payflow_admin \
  -e SPRING_DATASOURCE_PASSWORD=YourStrongPassword123! \
  123456789012.dkr.ecr.ap-south-1.amazonaws.com/payflow-payment-service:latest

# Verify it's running
docker ps
docker logs payment-service

# Test health endpoint
curl http://localhost:8082/actuator/health
# {"status":"UP"}
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | EC2 launch | Amazon Linux 2023 + t2.micro for free tier |
| 2 | SSH access | .pem key file + security group port 22 |
| 3 | Docker on EC2 | Install, start, add user to docker group |
| 4 | ECR repositories | One repo per service, image scanning enabled |
| 5 | ECR auth | `get-login-password` piped to `docker login` |
| 6 | Build + push | Tag with registry URL, push both version + latest |
| 7 | Pull + run | Pull from ECR on EC2, run with environment config |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `Permission denied (publickey)` | Wrong .pem file or wrong permissions | `chmod 400 payflow-key.pem`, verify correct file |
| `Connection timed out` SSH | Security group missing SSH rule | Add port 22 inbound from your IP |
| `docker: Got permission denied` | User not in docker group | Run `newgrp docker` or logout/login |
| ECR `no basic auth credentials` | Auth token expired (12 hours) | Re-run `aws ecr get-login-password` command |
| `no space left on device` | 8GB default storage full | Use 30GB when launching instance |
| Image push slow | Large image + slow upload | Use multi-stage builds for smaller images |
| `requested access to resource is denied` | Wrong ECR URL or repo doesn't exist | Verify repository name and account ID |

---

<div align="center">

**[← Part 3: Database & Messaging](phase8-part3-database-messaging.md)** | **[Documentation Index](../README.md)** | **[Part 5: Frontend Hosting →](phase8-part5-frontend-hosting.md)**

</div>
