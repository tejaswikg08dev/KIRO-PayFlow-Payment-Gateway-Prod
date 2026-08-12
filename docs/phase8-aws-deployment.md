# Phase 8: AWS Deployment Overview

## Overview

Deploy PayFlow to AWS using Free Tier eligible services. Architecture designed for cost-efficiency while maintaining production-quality patterns.

## AWS Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        AWS CLOUD                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────┐         ┌──────────────────┐                  │
│  │ CloudFront   │ ←──────│  S3 Bucket       │                  │
│  │ (CDN)        │         │ (Dashboard +     │                  │
│  └──────┬───────┘         │  Checkout)       │                  │
│         │                  └──────────────────┘                  │
│         │                                                        │
│  ┌──────▼───────┐                                               │
│  │     ALB      │  (Application Load Balancer)                  │
│  └──────┬───────┘                                               │
│         │                                                        │
│  ┌──────▼────────────────────────────────────────┐              │
│  │              EC2 Instance (t2.micro)           │              │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐        │              │
│  │  │Gateway  │ │Identity │ │Payment  │  ...    │              │
│  │  │:8080    │ │:8081    │ │:8082    │         │              │
│  │  └─────────┘ └─────────┘ └─────────┘        │              │
│  │  (Docker containers on single instance)       │              │
│  └───────────────────────────────────────────────┘              │
│         │                    │                                    │
│  ┌──────▼───────┐    ┌──────▼───────┐                          │
│  │  RDS         │    │  ElastiCache │                          │
│  │  PostgreSQL  │    │  Redis       │                          │
│  │  (db.t3.micro)│    │  (Free Tier) │                          │
│  └──────────────┘    └──────────────┘                          │
│                                                                   │
│  ┌──────────────┐    ┌──────────────┐                          │
│  │  DynamoDB    │    │  SQS/Kafka   │                          │
│  │  (On-demand) │    │  (On EC2)    │                          │
│  └──────────────┘    └──────────────┘                          │
└─────────────────────────────────────────────────────────────────┘
```

## AWS Free Tier Limits (12 months)

| Service | Free Tier Allowance | PayFlow Usage |
|---------|-------------------|---------------|
| EC2 | 750 hrs/month t2.micro | 1 instance (always-on) |
| RDS | 750 hrs/month db.t3.micro, 20GB | 1 PostgreSQL instance |
| S3 | 5GB storage, 20K GET, 2K PUT | Dashboard + Checkout |
| CloudFront | 1TB transfer, 10M requests | CDN for frontend |
| DynamoDB | 25GB, 25 RCU, 25 WCU | Webhook delivery logs |
| ECR | 500MB private repos | Docker images |
| SES | 62,000 emails/month | Notifications |
| CloudWatch | 10 custom metrics, 5GB logs | Monitoring |
| ALB | 750 hrs, 15 LCUs | Load balancer |

## Cost Estimation (Beyond Free Tier)

| Service | Estimated Monthly Cost |
|---------|----------------------|
| EC2 t3.small (if upgrade needed) | $15 |
| RDS db.t3.micro (20GB) | $0 (free tier) |
| S3 + CloudFront | $1-2 |
| DynamoDB (on-demand) | $0-1 |
| Data transfer | $2-5 |
| **Total (with free tier)** | **$0-5/month** |

## Deployment Phases

```
Phase 1: Account Setup + IAM + Billing Alerts
Phase 2: VPC + Networking + Security Groups
Phase 3: RDS PostgreSQL + DynamoDB
Phase 4: EC2 + Docker + ECR
Phase 5: S3 + CloudFront (Frontend)
Phase 6: ALB + DNS + Go Live
```

## Security Architecture

```
Internet → CloudFront (HTTPS) → S3 (private)
Internet → ALB (HTTPS) → EC2 (private subnet ideally)
EC2 → RDS (VPC internal, port 5432)
EC2 → Redis (VPC internal, port 6379)
EC2 → DynamoDB (VPC endpoint)
```

## Key AWS Services Used

| Service | Purpose in PayFlow |
|---------|-------------------|
| EC2 | Run Docker containers (all backend services) |
| RDS | Managed PostgreSQL (auto-backups, maintenance) |
| S3 | Static file hosting (React builds) |
| CloudFront | CDN, HTTPS termination, caching |
| ALB | Load balancing, path-based routing |
| ECR | Private Docker image registry |
| DynamoDB | Webhook delivery tracking |
| SES | Transactional emails |
| CloudWatch | Logs, metrics, alarms |
| IAM | Access control, service roles |
| VPC | Network isolation |
