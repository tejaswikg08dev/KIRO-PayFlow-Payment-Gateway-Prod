# Phase 8 · Part 3 — Database & Messaging Setup

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 8 — AWS Deployment |
| **Part** | 3 — RDS, DynamoDB, SQS, SNS |
| **Previous** | [Part 2 — Networking & VPC](phase8-part2-networking-vpc.md) |
| **Next** | [Part 4 — Compute & Registry](phase8-part4-compute-registry.md) |
| **Time** | ~1.5 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | VPC and Security Groups from Part 2 |

---

## Table of Contents

1. [Create RDS PostgreSQL](#1-create-rds-postgresql)
2. [Configure Security Group for RDS](#2-configure-security-group-for-rds)
3. [Save Endpoint URL](#3-save-endpoint-url)
4. [Create DynamoDB Tables](#4-create-dynamodb-tables)
5. [Create SQS Queues](#5-create-sqs-queues)
6. [Create SNS Topics](#6-create-sns-topics)
7. [What You Learned](#what-you-learned)
8. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Create RDS PostgreSQL

### Using AWS Console

1. **Console** → Search "RDS" → Amazon RDS
2. Click **"Create database"**
3. Configuration:

| Setting | Value |
|---------|-------|
| Engine | PostgreSQL |
| Version | 15.x |
| Template | **Free tier** |
| DB Instance ID | `payflow-db` |
| Master username | `payflow_admin` |
| Master password | (strong password — save it!) |
| Instance class | `db.t3.micro` (free tier) |
| Storage | 20 GB gp2 |
| VPC | `payflow-vpc` |
| Subnet group | Create new (both subnets) |
| Public access | **No** (security!) |
| Security group | `payflow-rds-sg` |
| Initial DB name | `payflow_identity` |

4. Click **"Create database"** (takes 5-10 minutes)

### Using AWS CLI

```bash
# Create DB subnet group
aws rds create-db-subnet-group \
  --db-subnet-group-name payflow-db-subnets \
  --db-subnet-group-description "PayFlow DB subnets" \
  --subnet-ids subnet-0aaa111bbb222ccc subnet-0ddd444eee555fff

# Create RDS instance
aws rds create-db-instance \
  --db-instance-identifier payflow-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --engine-version 15 \
  --master-username payflow_admin \
  --master-user-password "YourStrongPassword123!" \
  --allocated-storage 20 \
  --db-name payflow_identity \
  --vpc-security-group-ids sg-0rds789 \
  --db-subnet-group-name payflow-db-subnets \
  --no-publicly-accessible \
  --backup-retention-period 7
```

---

## 2. Configure Security Group for RDS

The RDS security group should ONLY allow connections from the EC2 security group:

```bash
# Allow PostgreSQL (5432) from EC2 instances only
aws ec2 authorize-security-group-ingress \
  --group-id sg-0rds789 \
  --protocol tcp \
  --port 5432 \
  --source-group sg-0ec2456
```

```
┌─────────────────────────────────────────────────┐
│  RDS Security Group Rules                        │
├────────────┬──────┬────────────────┬────────────┤
│ Type       │ Port │ Source         │ Purpose    │
├────────────┼──────┼────────────────┼────────────┤
│ PostgreSQL │ 5432 │ sg-0ec2456     │ App access │
│ (NO other rules — no public access!)            │
└─────────────────────────────────────────────────┘
```

---

## 3. Save Endpoint URL

Once RDS is "Available" (Status = green):

```bash
# Get endpoint
aws rds describe-db-instances \
  --db-instance-identifier payflow-db \
  --query "DBInstances[0].Endpoint.Address" \
  --output text

# Output: payflow-db.c9abc123xyz.ap-south-1.rds.amazonaws.com
```

**Save this for your application config:**

```bash
# Connection string format
SPRING_DATASOURCE_URL=jdbc:postgresql://payflow-db.c9abc123xyz.ap-south-1.rds.amazonaws.com:5432/payflow_identity
SPRING_DATASOURCE_USERNAME=payflow_admin
SPRING_DATASOURCE_PASSWORD=YourStrongPassword123!
```

### Create Additional Databases

SSH into EC2 (after Part 4), connect to RDS, and create remaining databases:

```bash
# From EC2 instance
psql -h payflow-db.c9abc123xyz.ap-south-1.rds.amazonaws.com -U payflow_admin -d payflow_identity

# Create other PayFlow databases
CREATE DATABASE payflow_payments;
CREATE DATABASE payflow_merchants;
CREATE DATABASE payflow_settlements;
```

---

## 4. Create DynamoDB Tables

DynamoDB is used for high-throughput, low-latency data that doesn't need relational joins.

### webhook_delivery Table

```bash
aws dynamodb create-table \
  --table-name webhook_delivery \
  --attribute-definitions \
    AttributeName=webhookId,AttributeType=S \
    AttributeName=deliveryId,AttributeType=S \
    AttributeName=merchantId,AttributeType=S \
  --key-schema \
    AttributeName=webhookId,KeyType=HASH \
    AttributeName=deliveryId,KeyType=RANGE \
  --global-secondary-indexes \
    '[{
      "IndexName": "merchant-index",
      "KeySchema": [{"AttributeName":"merchantId","KeyType":"HASH"}],
      "Projection": {"ProjectionType":"ALL"},
      "ProvisionedThroughput": {"ReadCapacityUnits":5,"WriteCapacityUnits":5}
    }]' \
  --provisioned-throughput ReadCapacityUnits=5,WriteCapacityUnits=5 \
  --tags Key=Project,Value=PayFlow
```

### routing_metrics Table

```bash
aws dynamodb create-table \
  --table-name routing_metrics \
  --attribute-definitions \
    AttributeName=routeId,AttributeType=S \
    AttributeName=timestamp,AttributeType=N \
  --key-schema \
    AttributeName=routeId,KeyType=HASH \
    AttributeName=timestamp,KeyType=RANGE \
  --provisioned-throughput ReadCapacityUnits=5,WriteCapacityUnits=5 \
  --tags Key=Project,Value=PayFlow
```

### Verify Tables

```bash
aws dynamodb list-tables
# {
#   "TableNames": ["routing_metrics", "webhook_delivery"]
# }
```

---

## 5. Create SQS Queues

Amazon SQS provides reliable async messaging between services.

### payment-events Queue

```bash
# Main queue
aws sqs create-queue \
  --queue-name payment-events \
  --attributes '{
    "VisibilityTimeout": "60",
    "MessageRetentionPeriod": "1209600",
    "ReceiveMessageWaitTimeSeconds": "20"
  }'

# Dead-letter queue (for failed messages)
aws sqs create-queue \
  --queue-name payment-events-dlq \
  --attributes '{
    "MessageRetentionPeriod": "1209600"
  }'

# Link DLQ to main queue
aws sqs set-queue-attributes \
  --queue-url https://sqs.ap-south-1.amazonaws.com/123456789012/payment-events \
  --attributes '{
    "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:ap-south-1:123456789012:payment-events-dlq\",\"maxReceiveCount\":\"3\"}"
  }'
```

### notification-events Queue

```bash
aws sqs create-queue --queue-name notification-events \
  --attributes '{"VisibilityTimeout":"30","ReceiveMessageWaitTimeSeconds":"20"}'

aws sqs create-queue --queue-name notification-events-dlq
```

| Queue | Purpose | Visibility Timeout |
|-------|---------|-------------------|
| `payment-events` | Payment state changes | 60s |
| `payment-events-dlq` | Failed payment messages | — |
| `notification-events` | Email/SMS triggers | 30s |
| `notification-events-dlq` | Failed notifications | — |

---

## 6. Create SNS Topics

SNS is used for fan-out notifications (one message → multiple subscribers).

```bash
# Email notifications topic
aws sns create-topic --name payflow-email-notifications
# Output: arn:aws:sns:ap-south-1:123456789012:payflow-email-notifications

# SMS notifications topic
aws sns create-topic --name payflow-sms-notifications
# Output: arn:aws:sns:ap-south-1:123456789012:payflow-sms-notifications

# Subscribe an email for testing
aws sns subscribe \
  --topic-arn arn:aws:sns:ap-south-1:123456789012:payflow-email-notifications \
  --protocol email \
  --notification-endpoint your-email@example.com

# Confirm subscription (check your email!)
```

### Verify All Resources

```bash
# List queues
aws sqs list-queues --queue-name-prefix payflow

# List topics
aws sns list-topics

# List DynamoDB tables
aws dynamodb list-tables

# Check RDS status
aws rds describe-db-instances --db-instance-identifier payflow-db --query "DBInstances[0].DBInstanceStatus"
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | RDS PostgreSQL | Managed database with backups, free tier = db.t3.micro |
| 2 | Security groups | RDS accessible ONLY from EC2 — never public |
| 3 | Endpoint URL | Connection string for Spring Boot config |
| 4 | DynamoDB | NoSQL for high-throughput webhook/routing data |
| 5 | SQS queues | Async messaging with dead-letter queues for failures |
| 6 | SNS topics | Fan-out notifications (email, SMS) |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| RDS "Creating" for 15+ min | Normal — first creation is slow | Wait up to 20 minutes |
| Can't connect to RDS from EC2 | Security group wrong | Verify SG allows 5432 from EC2's security group |
| `FATAL: database does not exist` | Only initial DB created | Create additional databases via `psql` |
| DynamoDB `ResourceInUseException` | Table already exists | Use different name or delete existing |
| SQS message not received | `VisibilityTimeout` too low | Increase timeout above processing time |
| SNS subscription "PendingConfirmation" | Email not confirmed | Check inbox (including spam) for confirmation link |

---

<div align="center">

**[← Part 2: Networking & VPC](phase8-part2-networking-vpc.md)** | **[Documentation Index](../README.md)** | **[Part 4: Compute & Registry →](phase8-part4-compute-registry.md)**

</div>
