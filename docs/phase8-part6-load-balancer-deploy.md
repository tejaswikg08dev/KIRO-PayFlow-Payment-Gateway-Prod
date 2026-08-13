# Phase 8 · Part 6 — Load Balancer & Final Deployment

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 8 — AWS Deployment |
| **Part** | 6 — ALB, Full Deploy, and Go-Live |
| **Previous** | [Part 5 — Frontend Hosting](phase8-part5-frontend-hosting.md) |
| **Next** | [Phase 9 — Monitoring & Observability](phase9-monitoring-observability.md) |
| **Time** | ~2.5 hours |
| **Difficulty** | ★★★★☆ Intermediate-Advanced |
| **Prerequisites** | All Phase 8 parts (VPC, EC2, RDS, ECR, S3) |

---

## Table of Contents

1. [Create Application Load Balancer](#1-create-application-load-balancer)
2. [Create Target Group](#2-create-target-group)
3. [Configure Listener Rules](#3-configure-listener-rules)
4. [Deploy docker-compose.prod.yml on EC2](#4-deploy-docker-composeprodyml-on-ec2)
5. [Configure Environment Variables](#5-configure-environment-variables)
6. [Start All Services](#6-start-all-services)
7. [End-to-End Test](#7-end-to-end-test)
8. [Go-Live Checklist](#8-go-live-checklist)
9. [What You Learned](#what-you-learned)
10. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Create Application Load Balancer

```bash
# Create ALB
aws elbv2 create-load-balancer \
  --name payflow-alb \
  --subnets subnet-0aaa111bbb222ccc subnet-0ddd444eee555fff \
  --security-groups sg-0alb123 \
  --scheme internet-facing \
  --type application

# Output: arn:aws:elasticloadbalancing:ap-south-1:123456789012:loadbalancer/app/payflow-alb/abc123
```

```
┌──────────────────────────────────────────────────────────────┐
│                    APPLICATION LOAD BALANCER                   │
│                                                               │
│   Internet → ALB (payflow-alb)                               │
│                 │                                             │
│                 ├── /api/*  → Target Group (EC2:8080)        │
│                 ├── /health → Target Group (EC2:8080)        │
│                 └── /*      → S3/CloudFront (frontend)       │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Create Target Group

Target groups define where the ALB sends traffic.

```bash
# Create target group
aws elbv2 create-target-group \
  --name payflow-backend-tg \
  --protocol HTTP \
  --port 8080 \
  --vpc-id vpc-0abc123def456789 \
  --health-check-path /actuator/health \
  --health-check-interval-seconds 30 \
  --healthy-threshold-count 2 \
  --unhealthy-threshold-count 3 \
  --target-type instance

# Register EC2 instance
aws elbv2 register-targets \
  --target-group-arn arn:aws:elasticloadbalancing:ap-south-1:123456789012:targetgroup/payflow-backend-tg/abc123 \
  --targets Id=i-0ec2instance123
```

---

## 3. Configure Listener Rules

```bash
# Create HTTP listener (port 80) — redirects to HTTPS
aws elbv2 create-listener \
  --load-balancer-arn arn:aws:elasticloadbalancing:...:loadbalancer/app/payflow-alb/abc123 \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=redirect,RedirectConfig='{Protocol=HTTPS,Port=443,StatusCode=HTTP_301}'

# Create HTTPS listener (port 443)
aws elbv2 create-listener \
  --load-balancer-arn arn:aws:elasticloadbalancing:...:loadbalancer/app/payflow-alb/abc123 \
  --protocol HTTPS \
  --port 443 \
  --certificates CertificateArn=arn:aws:acm:ap-south-1:123456789012:certificate/abc123 \
  --default-actions Type=forward,TargetGroupArn=arn:aws:elasticloadbalancing:...:targetgroup/payflow-backend-tg/abc123
```

### Path-Based Routing Rules

| Path Pattern | Forward To | Purpose |
|-------------|-----------|---------|
| `/v1/*` | payflow-backend-tg (EC2:8080) | API requests |
| `/actuator/*` | payflow-backend-tg (EC2:8080) | Health checks |
| `/checkout/*` | payflow-backend-tg (EC2:8080) | Hosted checkout |

---

## 4. Deploy docker-compose.prod.yml on EC2

SSH into EC2 and set up the production compose file:

```bash
# SSH into EC2
ssh -i payflow-key.pem ec2-user@13.235.xx.xx

# Create project directory
sudo mkdir -p /opt/payflow
sudo chown ec2-user:ec2-user /opt/payflow
cd /opt/payflow

# Create production compose file
cat > docker-compose.prod.yml << 'EOF'
version: '3.8'

services:
  api-gateway:
    image: ${ECR_REGISTRY}/payflow-api-gateway:latest
    ports:
      - "8080:8080"
    env_file: .env
    environment:
      - SPRING_PROFILES_ACTIVE=prod
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3

  identity-service:
    image: ${ECR_REGISTRY}/payflow-identity-service:latest
    env_file: .env
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_URL=jdbc:postgresql://${RDS_ENDPOINT}:5432/payflow_identity
    restart: unless-stopped

  merchant-service:
    image: ${ECR_REGISTRY}/payflow-merchant-service:latest
    env_file: .env
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_URL=jdbc:postgresql://${RDS_ENDPOINT}:5432/payflow_merchants
    restart: unless-stopped

  payment-service:
    image: ${ECR_REGISTRY}/payflow-payment-service:latest
    env_file: .env
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_URL=jdbc:postgresql://${RDS_ENDPOINT}:5432/payflow_payments
    restart: unless-stopped

  routing-engine:
    image: ${ECR_REGISTRY}/payflow-routing-engine:latest
    env_file: .env
    restart: unless-stopped

  bank-simulator:
    image: ${ECR_REGISTRY}/payflow-bank-simulator:latest
    env_file: .env
    restart: unless-stopped

  settlement-service:
    image: ${ECR_REGISTRY}/payflow-settlement-service:latest
    env_file: .env
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://${RDS_ENDPOINT}:5432/payflow_settlements
    restart: unless-stopped

  notification-service:
    image: ${ECR_REGISTRY}/payflow-notification-service:latest
    env_file: .env
    restart: unless-stopped

  webhook-service:
    image: ${ECR_REGISTRY}/payflow-webhook-service:latest
    env_file: .env
    restart: unless-stopped

  analytics-service:
    image: ${ECR_REGISTRY}/payflow-analytics-service:latest
    env_file: .env
    restart: unless-stopped

  reconciliation-service:
    image: ${ECR_REGISTRY}/payflow-reconciliation-service:latest
    env_file: .env
    restart: unless-stopped
EOF
```

---

## 5. Configure Environment Variables

```bash
# Create .env file with real AWS values
cat > /opt/payflow/.env << 'EOF'
# ─── AWS ──────────────────────────────────────────
ECR_REGISTRY=123456789012.dkr.ecr.ap-south-1.amazonaws.com
AWS_REGION=ap-south-1

# ─── Database ────────────────────────────────────
RDS_ENDPOINT=payflow-db.c9abc123xyz.ap-south-1.rds.amazonaws.com
SPRING_DATASOURCE_USERNAME=payflow_admin
SPRING_DATASOURCE_PASSWORD=YourStrongPassword123!

# ─── Redis ────────────────────────────────────────
SPRING_DATA_REDIS_HOST=payflow-redis.abc123.cache.amazonaws.com
SPRING_DATA_REDIS_PORT=6379

# ─── Kafka ────────────────────────────────────────
SPRING_KAFKA_BOOTSTRAP_SERVERS=b-1.payflow-kafka.abc123.kafka.ap-south-1.amazonaws.com:9092

# ─── JWT ──────────────────────────────────────────
JWT_SECRET=your-256-bit-secret-key-here-minimum-32-chars
JWT_EXPIRATION_MS=86400000

# ─── SQS ─────────────────────────────────────────
SQS_PAYMENT_EVENTS_URL=https://sqs.ap-south-1.amazonaws.com/123456789012/payment-events
SQS_NOTIFICATION_EVENTS_URL=https://sqs.ap-south-1.amazonaws.com/123456789012/notification-events

# ─── SNS ─────────────────────────────────────────
SNS_EMAIL_TOPIC_ARN=arn:aws:sns:ap-south-1:123456789012:payflow-email-notifications
SNS_SMS_TOPIC_ARN=arn:aws:sns:ap-south-1:123456789012:payflow-sms-notifications
EOF

# Secure the file
chmod 600 /opt/payflow/.env
```

---

## 6. Start All Services

```bash
cd /opt/payflow

# Login to ECR
aws ecr get-login-password --region ap-south-1 | \
  docker login --username AWS --password-stdin ${ECR_REGISTRY}

# Pull latest images
docker compose -f docker-compose.prod.yml pull

# Start all services
docker compose -f docker-compose.prod.yml up -d

# Verify all healthy
docker compose -f docker-compose.prod.yml ps

# Check logs for any errors
docker compose -f docker-compose.prod.yml logs --tail=50
```

### Verify Health

```bash
# API Gateway health
curl http://localhost:8080/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"}}}

# All services
for port in 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090; do
  echo -n "Port $port: "
  curl -s http://localhost:$port/actuator/health | jq -r '.status'
done
```

---

## 7. End-to-End Test

Full lifecycle test: register → login → create payment.

```bash
# 1. Register a merchant
curl -X POST http://ALB-DNS:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@merchant.com","password":"Test123!","businessName":"Test Merchant"}'

# 2. Login
TOKEN=$(curl -s -X POST http://ALB-DNS:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@merchant.com","password":"Test123!"}' | jq -r '.token')

echo "Token: $TOKEN"

# 3. Create payment order
curl -X POST http://ALB-DNS:8080/v1/payments/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Idempotency-Key: test-$(date +%s)" \
  -d '{"amount":1000,"currency":"INR","description":"Test Payment"}'

# Expected: 201 Created with order details
```

---

## 8. Go-Live Checklist

| # | Task | Status |
|---|------|--------|
| 1 | VPC + subnets + IGW configured | ☐ |
| 2 | Security groups restrict access properly | ☐ |
| 3 | RDS running + databases created | ☐ |
| 4 | SQS queues + DLQs created | ☐ |
| 5 | EC2 running + Docker installed | ☐ |
| 6 | ECR repos created + images pushed | ☐ |
| 7 | ALB + target group + health checks | ☐ |
| 8 | All services healthy on EC2 | ☐ |
| 9 | Frontend deployed to S3 + CloudFront | ☐ |
| 10 | E2E test passes (register → login → pay) | ☐ |
| 11 | Budget alerts configured | ☐ |
| 12 | `.env` file secured (chmod 600) | ☐ |
| 13 | CI/CD pipeline deploys automatically | ☐ |
| 14 | Monitoring/alerts configured (Phase 9) | ☐ |

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | ALB | Distributes traffic and terminates SSL |
| 2 | Target groups | Route requests to healthy EC2 instances |
| 3 | Path routing | `/api/*` → backend, `/*` → frontend |
| 4 | Production compose | ECR images + env file + restart policies |
| 5 | Environment config | All secrets in `.env` with strict permissions |
| 6 | Service startup | Pull → up → verify health for all 11 services |
| 7 | E2E testing | Full flow validation before declaring go-live |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| ALB returns 502 Bad Gateway | Target unhealthy or app not started | Check target group health, verify service is running |
| Health check failing | Wrong path or port | Verify `/actuator/health` endpoint and port mapping |
| Service can't reach RDS | Security group or wrong endpoint | Verify SG allows EC2 → RDS:5432 |
| `docker compose pull` access denied | ECR token expired | Re-authenticate with `aws ecr get-login-password` |
| Services keep restarting | Config error (DB password wrong) | Check logs: `docker compose logs service-name` |
| ALB 504 timeout | Service takes too long to respond | Increase ALB idle timeout or fix app performance |
| Can't reach ALB from internet | SG missing 80/443 inbound from 0.0.0.0/0 | Add rules to ALB security group |

---

<div align="center">

**[← Part 5: Frontend Hosting](phase8-part5-frontend-hosting.md)** | **[Documentation Index](../README.md)** | **[Phase 9: Monitoring →](phase9-monitoring-observability.md)**

</div>
