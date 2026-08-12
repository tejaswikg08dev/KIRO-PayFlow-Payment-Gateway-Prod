# Phase 8 Part 3: Database & Messaging Setup

## Overview

Setting up RDS PostgreSQL, DynamoDB, and messaging (Kafka on EC2 or SQS as alternative) on AWS.

## RDS PostgreSQL Setup

```bash
# Create DB subnet group
aws rds create-db-subnet-group \
  --db-subnet-group-name payflow-db-subnet \
  --db-subnet-group-description "PayFlow DB subnets" \
  --subnet-ids subnet-xxx subnet-yyy

# Create RDS instance (Free Tier eligible)
aws rds create-db-instance \
  --db-instance-identifier payflow-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --engine-version 15.4 \
  --master-username payflow_admin \
  --master-user-password "${DB_PASSWORD}" \
  --allocated-storage 20 \
  --storage-type gp2 \
  --vpc-security-group-ids sg-rds \
  --db-subnet-group-name payflow-db-subnet \
  --no-publicly-accessible \
  --backup-retention-period 7 \
  --preferred-backup-window "03:00-04:00" \
  --preferred-maintenance-window "sun:04:00-sun:05:00" \
  --no-multi-az \
  --auto-minor-version-upgrade \
  --tags Key=Project,Value=PayFlow
```

### Create Databases

```bash
# Connect to RDS (from EC2)
psql -h payflow-db.xxx.ap-south-1.rds.amazonaws.com \
  -U payflow_admin -d postgres

# Create service databases
CREATE DATABASE payflow_identity;
CREATE DATABASE payflow_merchant;
CREATE DATABASE payflow_payment;
CREATE DATABASE payflow_settlement;

# Create service users (least privilege)
CREATE USER identity_svc WITH PASSWORD 'xxx';
GRANT ALL PRIVILEGES ON DATABASE payflow_identity TO identity_svc;

CREATE USER payment_svc WITH PASSWORD 'xxx';
GRANT ALL PRIVILEGES ON DATABASE payflow_payment TO payment_svc;
```

## RDS Configuration

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Instance Class | db.t3.micro | Free Tier eligible |
| Storage | 20 GB gp2 | Free Tier max |
| Multi-AZ | No | Cost saving (portfolio project) |
| Backup Retention | 7 days | Automatic daily backups |
| Encryption | Yes (KMS default) | Security best practice |
| Public Access | No | Only from EC2 via SG |

## DynamoDB Setup

```bash
# Create webhook delivery table
aws dynamodb create-table \
  --table-name payflow-webhook-deliveries \
  --attribute-definitions \
    AttributeName=id,AttributeType=S \
    AttributeName=merchantId,AttributeType=S \
    AttributeName=createdAt,AttributeType=N \
  --key-schema \
    AttributeName=id,KeyType=HASH \
    AttributeName=createdAt,KeyType=RANGE \
  --global-secondary-indexes '[
    {
      "IndexName": "merchant-index",
      "KeySchema": [{"AttributeName":"merchantId","KeyType":"HASH"}],
      "Projection": {"ProjectionType":"ALL"},
      "ProvisionedThroughput": {"ReadCapacityUnits":5,"WriteCapacityUnits":5}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST \
  --tags Key=Project,Value=PayFlow

# Enable TTL (auto-delete after 30 days)
aws dynamodb update-time-to-live \
  --table-name payflow-webhook-deliveries \
  --time-to-live-specification Enabled=true,AttributeName=ttl
```

## Kafka on EC2 (or SQS Alternative)

### Option A: Kafka on EC2 (chosen for learning)

```bash
# On EC2 instance, use Docker Compose for Kafka
# Included in the main docker-compose.yml

# Kafka requires at minimum:
# - 1GB RAM for broker
# - 512MB for Zookeeper
# Total: ~1.5GB just for messaging

# For t2.micro (1GB RAM total) → use SQS instead
# For t3.small (2GB RAM) → Kafka is viable
```

### Option B: Amazon SQS (Free Tier friendly)

```bash
# Create SQS queues as Kafka alternative
aws sqs create-queue --queue-name payflow-payment-events
aws sqs create-queue --queue-name payflow-webhook-delivery
aws sqs create-queue --queue-name payflow-notifications

# Dead letter queue
aws sqs create-queue --queue-name payflow-payment-events-dlq
```

### SQS vs Kafka Decision

| Factor | Kafka (on EC2) | SQS |
|--------|---------------|-----|
| Cost | EC2 RAM usage | Free Tier: 1M requests |
| Complexity | High (manage broker) | Zero (serverless) |
| Event Replay | ✅ Yes | ❌ No |
| Ordering | ✅ Per-partition | ✅ FIFO queues |
| Learning Value | High | Lower |
| **Decision** | ✅ Use if t3.small+ | Use if t2.micro only |

## Redis on EC2 (vs ElastiCache)

```bash
# Option 1: ElastiCache (managed, costs after free tier)
aws elasticache create-cache-cluster \
  --cache-cluster-id payflow-redis \
  --engine redis \
  --cache-node-type cache.t3.micro \
  --num-cache-nodes 1

# Option 2: Redis in Docker on EC2 (free, simpler)
# Already in docker-compose.yml
docker run -d --name redis \
  -p 6379:6379 \
  redis:7-alpine \
  redis-server --maxmemory 128mb --maxmemory-policy allkeys-lru
```

## Connection Strings (Environment Variables)

```bash
# .env on EC2
DB_HOST=payflow-db.xxx.ap-south-1.rds.amazonaws.com
DB_PORT=5432
DB_USERNAME=payflow_admin
DB_PASSWORD=<from-secrets-manager>

REDIS_HOST=localhost  # Redis in Docker on same EC2
REDIS_PORT=6379

KAFKA_SERVERS=localhost:9092  # Kafka in Docker on same EC2

DYNAMODB_ENDPOINT=https://dynamodb.ap-south-1.amazonaws.com
AWS_REGION=ap-south-1
```

## Backup Strategy

| Data Store | Backup Method | Frequency | Retention |
|-----------|---------------|-----------|-----------|
| RDS PostgreSQL | Automated snapshots | Daily | 7 days |
| DynamoDB | Point-in-time recovery | Continuous | 35 days |
| Redis | Not backed up (cache only) | N/A | N/A |
| Kafka logs | Not backed up (ephemeral) | N/A | 7 day retention |
