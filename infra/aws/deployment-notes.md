# PayFlow - AWS Deployment Notes

Step-by-step reference for deploying PayFlow Payment Gateway to AWS.

## Prerequisites

- AWS CLI configured with appropriate IAM credentials
- Docker installed for building images
- Terraform (optional, for IaC)
- Domain name configured in Route 53

## Step 1: VPC & Networking

```
1. Create VPC (10.0.0.0/16)
2. Create subnets:
   - Public subnets (2 AZs): 10.0.1.0/24, 10.0.2.0/24
   - Private subnets (2 AZs): 10.0.3.0/24, 10.0.4.0/24
   - Database subnets (2 AZs): 10.0.5.0/24, 10.0.6.0/24
3. Create Internet Gateway, attach to VPC
4. Create NAT Gateway in public subnet
5. Configure route tables:
   - Public: 0.0.0.0/0 → IGW
   - Private: 0.0.0.0/0 → NAT GW
6. Create security groups:
   - ALB SG: Inbound 80, 443 from 0.0.0.0/0
   - App SG: Inbound 8080-8087 from ALB SG
   - DB SG: Inbound 5432 from App SG
   - Redis SG: Inbound 6379 from App SG
   - Kafka SG: Inbound 9092 from App SG
```

## Step 2: Database (RDS PostgreSQL)

```
1. Create RDS subnet group (database subnets)
2. Launch RDS PostgreSQL 15 instance:
   - Instance: db.t3.medium (dev) / db.r6g.large (prod)
   - Multi-AZ: Yes (production)
   - Storage: 100GB gp3, auto-scaling
   - DB Name: payflow
   - Master user: payflow_admin
3. Run init-db.sql to create databases
4. Enable automated backups (7-day retention)
5. Enable Performance Insights
```

## Step 3: Caching (ElastiCache Redis)

```
1. Create ElastiCache subnet group
2. Launch Redis cluster:
   - Node type: cache.t3.medium (dev) / cache.r6g.large (prod)
   - Number of replicas: 2 (production)
   - Multi-AZ: Yes
   - Encryption at-rest: Yes
   - Encryption in-transit: Yes
3. Note endpoint for application configuration
```

## Step 4: Messaging (Amazon MSK)

```
1. Create MSK cluster:
   - Broker type: kafka.t3.small (dev) / kafka.m5.large (prod)
   - Number of brokers: 3 (one per AZ)
   - Kafka version: 3.5.x
   - Storage: 100GB per broker
   - Encryption: TLS
2. Create topics using create-kafka-topics.sh (modified for MSK)
3. Note bootstrap broker endpoints
```

## Step 5: Container Registry (ECR)

```
1. Create ECR repositories for each service:
   - payflow-service-registry
   - payflow-config-server
   - payflow-api-gateway
   - payflow-identity-service
   - payflow-merchant-service
   - payflow-payment-service
   - payflow-routing-service
   - payflow-settlement-service
   - payflow-webhook-service
   - payflow-notification-service
   - payflow-bank-simulator
2. Enable image scanning on push
3. Set lifecycle policy (keep last 10 images)
```

## Step 6: Compute (EC2 / ECS)

### Option A: EC2 with Docker Compose

```
1. Launch EC2 instance:
   - AMI: Amazon Linux 2023
   - Type: t3.xlarge (dev) / c6i.2xlarge (prod)
   - Subnet: Private
   - Security group: App SG
   - IAM role: EC2-PayFlow-Role (ECR pull, SSM, CloudWatch)
2. Install Docker, Docker Compose
3. Deploy using docker-compose.prod.yml
4. Configure CloudWatch agent for logs
```

### Option B: ECS Fargate (Recommended for Production)

```
1. Create ECS cluster
2. Create task definitions for each service
3. Create ECS services with desired count
4. Configure auto-scaling policies
5. Set up service discovery (Cloud Map)
```

## Step 7: Load Balancer (ALB)

```
1. Create Application Load Balancer:
   - Scheme: Internet-facing
   - Subnets: Public subnets
   - Security group: ALB SG
2. Create target groups for each service
3. Configure listeners:
   - HTTP:80 → Redirect to HTTPS
   - HTTPS:443 → Forward to API Gateway target group
4. Configure path-based routing:
   - /api/* → API Gateway
   - / → Merchant Portal (S3/CloudFront)
5. Add ACM SSL certificate
```

## Step 8: Frontend (S3 + CloudFront)

```
1. Create S3 buckets:
   - payflow-merchant-portal
   - payflow-hosted-checkout
2. Enable static website hosting
3. Create CloudFront distributions:
   - Origin: S3 bucket
   - Viewer protocol: Redirect HTTP to HTTPS
   - Cache policy: CachingOptimized
   - Origin request policy: CORS-S3Origin
4. Configure custom domain with Route 53
5. Add WAF web ACL for protection
```

## Step 9: DynamoDB

```
1. Create tables:
   - webhook_delivery_records (PAY_PER_REQUEST)
   - routing_metrics (PAY_PER_REQUEST)
   - event_logs (PAY_PER_REQUEST)
2. Enable point-in-time recovery
3. Enable DynamoDB Streams where needed
4. Set up auto-scaling for provisioned mode (production)
```

## Step 10: Monitoring & Logging

```
1. CloudWatch:
   - Create log groups for each service
   - Set retention to 30 days
   - Create dashboards for key metrics
   - Set up alarms (CPU, Memory, Error rates, Latency)
2. X-Ray:
   - Enable tracing in services
   - Create service map
3. SNS:
   - Create alert topics
   - Subscribe email/Slack endpoints
```

## Step 11: Security

```
1. IAM:
   - Create service roles with least privilege
   - Enable MFA for all users
2. Secrets Manager:
   - Store DB credentials
   - Store JWT secrets
   - Store API keys
3. WAF:
   - Rate limiting rules
   - SQL injection protection
   - XSS protection
   - Geo-blocking (if needed)
4. GuardDuty: Enable threat detection
5. Config: Enable compliance monitoring
```

## Step 12: CI/CD

```
1. GitHub Actions (already configured):
   - Build → Test → Docker → Deploy
2. Configure GitHub secrets:
   - AWS_ACCESS_KEY_ID
   - AWS_SECRET_ACCESS_KEY
   - EC2_HOST / ECS_CLUSTER
   - DOCKER_REGISTRY (ECR URL)
3. Set up deployment environments:
   - staging (auto-deploy from develop)
   - production (manual approval from main)
```

## Environment Variables (Production)

```env
DOCKER_REGISTRY=<account-id>.dkr.ecr.<region>.amazonaws.com
RDS_ENDPOINT=payflow-db.<id>.<region>.rds.amazonaws.com
RDS_USERNAME=payflow_admin
RDS_PASSWORD=<from-secrets-manager>
ELASTICACHE_ENDPOINT=payflow-redis.<id>.cache.amazonaws.com
MSK_BROKERS=b-1.<id>.kafka.<region>.amazonaws.com:9092,b-2...
JWT_SECRET=<from-secrets-manager>
AWS_REGION=ap-south-1
```

## Estimated Monthly Costs (Production)

| Service | Configuration | Estimated Cost |
|---------|--------------|---------------|
| EC2 / ECS | 2x c6i.large | ~$120 |
| RDS PostgreSQL | db.r6g.large Multi-AZ | ~$350 |
| ElastiCache | cache.r6g.large + replica | ~$200 |
| MSK | 3x kafka.m5.large | ~$500 |
| ALB | 1 ALB + traffic | ~$30 |
| CloudFront | 2 distributions | ~$20 |
| DynamoDB | On-demand | ~$30 |
| S3 | Storage + requests | ~$5 |
| Monitoring | CloudWatch + X-Ray | ~$50 |
| **Total** | | **~$1,300/month** |

Note: Costs vary by region and traffic. Use AWS Pricing Calculator for accurate estimates.
