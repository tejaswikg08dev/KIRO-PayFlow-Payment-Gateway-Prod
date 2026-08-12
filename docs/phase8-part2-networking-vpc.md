# Phase 8 Part 2: Networking & VPC

## Overview

VPC configuration, subnets, security groups, and network architecture for PayFlow on AWS.

## VPC Architecture

```
┌─────────────────────── VPC: 10.0.0.0/16 ───────────────────────┐
│                                                                   │
│  ┌─────────────── Public Subnet: 10.0.1.0/24 ──────────────┐   │
│  │                                                            │   │
│  │  [ALB]           [NAT Gateway]     [EC2 - PayFlow]        │   │
│  │  10.0.1.10       10.0.1.20         10.0.1.100             │   │
│  │                                                            │   │
│  └────────────────────────────────────────────────────────────┘   │
│                              │                                     │
│  ┌─────────────── Private Subnet: 10.0.2.0/24 ─────────────┐   │
│  │                                                            │   │
│  │  [RDS PostgreSQL]        [ElastiCache Redis]              │   │
│  │  10.0.2.50               10.0.2.60                        │   │
│  │                                                            │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  Internet Gateway ←→ Public Subnet                               │
│  NAT Gateway ←→ Private Subnet (outbound only)                  │
└───────────────────────────────────────────────────────────────────┘
```

## VPC Creation

```bash
# Create VPC
aws ec2 create-vpc \
  --cidr-block 10.0.0.0/16 \
  --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=payflow-vpc}]'

# Create public subnet
aws ec2 create-subnet \
  --vpc-id vpc-xxx \
  --cidr-block 10.0.1.0/24 \
  --availability-zone ap-south-1a \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=payflow-public}]'

# Create private subnet
aws ec2 create-subnet \
  --vpc-id vpc-xxx \
  --cidr-block 10.0.2.0/24 \
  --availability-zone ap-south-1a \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=payflow-private}]'

# Create Internet Gateway
aws ec2 create-internet-gateway \
  --tag-specifications 'ResourceType=internet-gateway,Tags=[{Key=Name,Value=payflow-igw}]'
aws ec2 attach-internet-gateway --vpc-id vpc-xxx --internet-gateway-id igw-xxx
```

## Security Groups

### ALB Security Group

```bash
aws ec2 create-security-group \
  --group-name payflow-alb-sg \
  --description "ALB - Allow HTTP/HTTPS from internet" \
  --vpc-id vpc-xxx

# Allow inbound HTTPS from anywhere
aws ec2 authorize-security-group-ingress \
  --group-id sg-alb \
  --protocol tcp --port 443 --cidr 0.0.0.0/0

# Allow HTTP (redirect to HTTPS)
aws ec2 authorize-security-group-ingress \
  --group-id sg-alb \
  --protocol tcp --port 80 --cidr 0.0.0.0/0
```

### EC2 Security Group

```bash
aws ec2 create-security-group \
  --group-name payflow-ec2-sg \
  --description "EC2 - Allow from ALB only" \
  --vpc-id vpc-xxx

# Allow from ALB only (port 8080)
aws ec2 authorize-security-group-ingress \
  --group-id sg-ec2 \
  --protocol tcp --port 8080 --source-group sg-alb

# Allow SSH (from your IP only)
aws ec2 authorize-security-group-ingress \
  --group-id sg-ec2 \
  --protocol tcp --port 22 --cidr YOUR_IP/32
```

### RDS Security Group

```bash
aws ec2 create-security-group \
  --group-name payflow-rds-sg \
  --description "RDS - Allow from EC2 only" \
  --vpc-id vpc-xxx

# Allow PostgreSQL from EC2 SG only
aws ec2 authorize-security-group-ingress \
  --group-id sg-rds \
  --protocol tcp --port 5432 --source-group sg-ec2
```

## Security Group Summary

| Security Group | Inbound Rules | Outbound |
|---------------|---------------|----------|
| `payflow-alb-sg` | 80, 443 from 0.0.0.0/0 | All |
| `payflow-ec2-sg` | 8080 from ALB SG; 22 from your IP | All |
| `payflow-rds-sg` | 5432 from EC2 SG | All |
| `payflow-redis-sg` | 6379 from EC2 SG | All |

## Simplified Setup (Free Tier Friendly)

For the portfolio project, a simpler setup works fine:

```
- Single public subnet (no NAT Gateway cost)
- EC2 in public subnet with security groups
- RDS in same VPC, private access only via SG rules
- No need for multiple AZs (single instance anyway)
```

## Route Tables

```bash
# Public subnet route table
aws ec2 create-route \
  --route-table-id rtb-public \
  --destination-cidr-block 0.0.0.0/0 \
  --gateway-id igw-xxx
```

## Network Flow

```
User → CloudFront → ALB:443 → EC2:8080 → Service
                                    ↓
                              RDS:5432 (private)
                              Redis:6379 (private)
```
