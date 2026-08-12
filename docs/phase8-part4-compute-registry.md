# Phase 8 Part 4: Compute & Container Registry

## Overview

EC2 instance setup, Docker installation, ECR repository creation, and running PayFlow services on AWS compute.

## EC2 Instance Setup

```bash
# Launch EC2 instance
aws ec2 run-instances \
  --image-id ami-0f5ee92e2d63afc18 \  # Amazon Linux 2023
  --instance-type t3.small \
  --key-name payflow-key \
  --security-group-ids sg-ec2 \
  --subnet-id subnet-public \
  --iam-instance-profile Name=payflow-ec2-role \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3"}}]' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=payflow-server}]' \
  --user-data file://init-script.sh
```

### Instance Init Script

```bash
#!/bin/bash
# init-script.sh (EC2 user data)

# Update system
yum update -y

# Install Docker
yum install -y docker
systemctl start docker
systemctl enable docker
usermod -aG docker ec2-user

# Install Docker Compose
curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" \
  -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose

# Install AWS CLI (already on Amazon Linux)
# Install Java 17 (for troubleshooting)
yum install -y java-17-amazon-corretto-headless

# Create app directory
mkdir -p /home/ec2-user/payflow
chown ec2-user:ec2-user /home/ec2-user/payflow

# Login to ECR (will be done by CI/CD)
# aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin ACCOUNT.dkr.ecr.ap-south-1.amazonaws.com
```

## ECR Repository Setup

```bash
# Create repositories for each service
SERVICES=(api-gateway identity-service merchant-service payment-service routing-service bank-simulator settlement-service webhook-service notification-service)

for svc in "${SERVICES[@]}"; do
  aws ecr create-repository \
    --repository-name payflow/${svc} \
    --image-scanning-configuration scanOnPush=true \
    --encryption-configuration encryptionType=AES256 \
    --tags Key=Project,Value=PayFlow
done

# Set lifecycle policy (keep only last 5 images)
aws ecr put-lifecycle-policy \
  --repository-name payflow/payment-service \
  --lifecycle-policy-text '{
    "rules": [
      {
        "rulePriority": 1,
        "description": "Keep only 5 images",
        "selection": {
          "tagStatus": "any",
          "countType": "imageCountMoreThan",
          "countNumber": 5
        },
        "action": { "type": "expire" }
      }
    ]
  }'
```

## Docker on EC2

### Docker Compose for Production

```yaml
# /home/ec2-user/payflow/docker-compose.yml
version: '3.8'

services:
  redis:
    image: redis:7-alpine
    command: redis-server --maxmemory 128mb --maxmemory-policy allkeys-lru
    ports: ["6379:6379"]
    restart: always

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    restart: always

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    restart: always

  api-gateway:
    image: ${ECR_REGISTRY}/payflow/api-gateway:latest
    ports: ["8080:8080"]
    env_file: .env
    depends_on: [redis]
    restart: always
    deploy:
      resources:
        limits: { memory: 256M }

  identity-service:
    image: ${ECR_REGISTRY}/payflow/identity-service:latest
    ports: ["8081:8081"]
    env_file: .env
    restart: always
    deploy:
      resources:
        limits: { memory: 256M }

  merchant-service:
    image: ${ECR_REGISTRY}/payflow/merchant-service:latest
    ports: ["8083:8083"]
    env_file: .env
    restart: always
    deploy:
      resources:
        limits: { memory: 256M }

  payment-service:
    image: ${ECR_REGISTRY}/payflow/payment-service:latest
    ports: ["8082:8082"]
    env_file: .env
    depends_on: [redis, kafka]
    restart: always
    deploy:
      resources:
        limits: { memory: 384M }

  routing-service:
    image: ${ECR_REGISTRY}/payflow/routing-service:latest
    ports: ["8084:8084"]
    env_file: .env
    restart: always
    deploy:
      resources:
        limits: { memory: 256M }

  bank-simulator:
    image: ${ECR_REGISTRY}/payflow/bank-simulator:latest
    ports: ["9090:9090"]
    restart: always
    deploy:
      resources:
        limits: { memory: 128M }
```

### Environment File

```bash
# /home/ec2-user/payflow/.env
DB_HOST=payflow-db.xxx.rds.amazonaws.com
DB_PORT=5432
DB_USERNAME=payflow_admin
DB_PASSWORD=<secure-password>
JWT_SECRET=<base64-encoded-secret>
REDIS_HOST=redis
KAFKA_SERVERS=kafka:29092
AWS_REGION=ap-south-1
ECR_REGISTRY=123456789.dkr.ecr.ap-south-1.amazonaws.com
```

## Instance Sizing Guide

| Instance | vCPU | RAM | Cost/Month | Suitable For |
|----------|------|-----|-----------|--------------|
| t2.micro | 1 | 1GB | Free Tier | 2-3 services only |
| **t3.small** | 2 | 2GB | ~$15 | **All services (tight)** |
| t3.medium | 2 | 4GB | ~$30 | Comfortable for all |

## Memory Budget (t3.small: 2GB)

| Component | Memory |
|-----------|--------|
| OS + Docker | 200MB |
| Redis | 128MB |
| Kafka + Zookeeper | 600MB |
| API Gateway | 200MB |
| Payment Service | 256MB |
| Other Services (5×128MB) | 640MB |
| **Total** | **~2GB** |

## Deployment Script

```bash
#!/bin/bash
# deploy.sh - Run on EC2 to pull and restart
set -e

ECR_REGISTRY="123456789.dkr.ecr.ap-south-1.amazonaws.com"

# Login to ECR
aws ecr get-login-password --region ap-south-1 | \
  docker login --username AWS --password-stdin $ECR_REGISTRY

# Pull latest images
docker compose pull

# Restart with new images
docker compose up -d --remove-orphans

# Wait and check health
sleep 20
curl -f http://localhost:8080/actuator/health && echo "✅ Healthy" || echo "❌ Unhealthy"
```
