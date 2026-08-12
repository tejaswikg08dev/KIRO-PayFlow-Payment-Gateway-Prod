# Phase 8 · Part 2 — Networking & VPC

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 8 — AWS Deployment |
| **Part** | 2 — VPC, Subnets, and Security Groups |
| **Previous** | [Part 1 — AWS Account Setup](phase8-part1-aws-account-setup.md) |
| **Next** | [Part 3 — Database & Messaging](phase8-part3-database-messaging.md) |
| **Time** | ~1.5 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | AWS account configured, basic networking concepts |

---

## Table of Contents

1. [What is a VPC?](#1-what-is-a-vpc)
2. [Create VPC](#2-create-vpc)
3. [Create Subnets](#3-create-subnets)
4. [Internet Gateway & Route Table](#4-internet-gateway--route-table)
5. [Security Groups](#5-security-groups)
6. [What You Learned](#what-you-learned)
7. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. What is a VPC?

A **Virtual Private Cloud (VPC)** is your own private network in AWS. Think of it as your own data center in the cloud — you control who gets in and who doesn't.

```
┌───────────────────── AWS Cloud ──────────────────────┐
│                                                       │
│  ┌──────────── VPC (10.0.0.0/16) ────────────────┐  │
│  │                                                 │  │
│  │  ┌─── Public Subnet A ───┐  ┌─── Public Subnet B ───┐  │
│  │  │  10.0.1.0/24          │  │  10.0.2.0/24          │  │
│  │  │  AZ: ap-south-1a      │  │  AZ: ap-south-1b      │  │
│  │  │                        │  │                        │  │
│  │  │  [EC2 Instance]       │  │  [RDS (standby)]      │  │
│  │  │  [Load Balancer]      │  │  [Load Balancer]      │  │
│  │  └────────────────────────┘  └────────────────────────┘  │
│  │                                                 │  │
│  │  Internet Gateway ←── Public access             │  │
│  └─────────────────────────────────────────────────┘  │
│                                                       │
└───────────────────────────────────────────────────────┘
```

**Key concepts:**
- **CIDR block**: Range of IP addresses (e.g., `10.0.0.0/16` = 65,536 IPs)
- **Subnet**: Subdivision of the VPC in a specific Availability Zone
- **Internet Gateway**: Door to the public internet
- **Route Table**: Rules for where traffic goes
- **Security Group**: Firewall rules (allow/deny traffic)

---

## 2. Create VPC

### Using AWS Console

1. **Console** → Search "VPC" → VPC Dashboard
2. Click **"Create VPC"**
3. Settings:
   - Name: `payflow-vpc`
   - IPv4 CIDR: `10.0.0.0/16`
   - IPv6 CIDR: No
   - Tenancy: Default
4. Click **"Create VPC"**

### Using AWS CLI

```bash
# Create VPC
aws ec2 create-vpc \
  --cidr-block 10.0.0.0/16 \
  --tag-specifications 'ResourceType=vpc,Tags=[{Key=Name,Value=payflow-vpc}]'

# Output: vpc-0abc123def456789
# Save this VPC ID!
```

### CIDR Block Explained

```
10.0.0.0/16
│  │ │ │ │
│  │ │ │ └── /16 = first 16 bits are fixed (network part)
│  │ │ └──── last 16 bits available for hosts = 65,536 IPs
│  │ └────── .0 (will vary)
│  └──────── .0 (will vary)
└────────── 10 (fixed)
```

---

## 3. Create Subnets

We need 2 public subnets in different Availability Zones (required for high availability and load balancers).

### Subnet A (ap-south-1a)

```bash
aws ec2 create-subnet \
  --vpc-id vpc-0abc123def456789 \
  --cidr-block 10.0.1.0/24 \
  --availability-zone ap-south-1a \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=payflow-public-1a}]'
```

### Subnet B (ap-south-1b)

```bash
aws ec2 create-subnet \
  --vpc-id vpc-0abc123def456789 \
  --cidr-block 10.0.2.0/24 \
  --availability-zone ap-south-1b \
  --tag-specifications 'ResourceType=subnet,Tags=[{Key=Name,Value=payflow-public-1b}]'
```

### Enable Auto-Assign Public IP

```bash
aws ec2 modify-subnet-attribute \
  --subnet-id subnet-0aaa111bbb222ccc \
  --map-public-ip-on-launch
```

| Subnet | CIDR | AZ | Purpose |
|--------|------|-----|---------|
| `payflow-public-1a` | 10.0.1.0/24 | ap-south-1a | EC2, ALB |
| `payflow-public-1b` | 10.0.2.0/24 | ap-south-1b | ALB (multi-AZ), RDS standby |

---

## 4. Internet Gateway & Route Table

### Create Internet Gateway

```bash
# Create IGW
aws ec2 create-internet-gateway \
  --tag-specifications 'ResourceType=internet-gateway,Tags=[{Key=Name,Value=payflow-igw}]'
# Output: igw-0abc123

# Attach to VPC
aws ec2 attach-internet-gateway \
  --internet-gateway-id igw-0abc123 \
  --vpc-id vpc-0abc123def456789
```

### Configure Route Table

```bash
# Get the main route table ID
aws ec2 describe-route-tables --filters "Name=vpc-id,Values=vpc-0abc123def456789"
# Output: rtb-0abc123

# Add route: all internet traffic (0.0.0.0/0) → Internet Gateway
aws ec2 create-route \
  --route-table-id rtb-0abc123 \
  --destination-cidr-block 0.0.0.0/0 \
  --gateway-id igw-0abc123

# Associate route table with subnets
aws ec2 associate-route-table --route-table-id rtb-0abc123 --subnet-id subnet-0aaa111bbb222ccc
aws ec2 associate-route-table --route-table-id rtb-0abc123 --subnet-id subnet-0ddd444eee555fff
```

**Traffic flow:**
```
Internet → IGW → Route Table → Subnet → Security Group → EC2 Instance
```

---

## 5. Security Groups

Security Groups are virtual firewalls. PayFlow needs several:

### Create Security Groups

```bash
# ALB Security Group (public-facing)
aws ec2 create-security-group \
  --group-name payflow-alb-sg \
  --description "ALB - allows HTTP/HTTPS from internet" \
  --vpc-id vpc-0abc123def456789

# EC2 Security Group (app servers)
aws ec2 create-security-group \
  --group-name payflow-ec2-sg \
  --description "EC2 - allows traffic from ALB only" \
  --vpc-id vpc-0abc123def456789

# RDS Security Group (database)
aws ec2 create-security-group \
  --group-name payflow-rds-sg \
  --description "RDS - allows PostgreSQL from EC2 only" \
  --vpc-id vpc-0abc123def456789
```

### Security Group Rules

| Security Group | Type | Port | Source | Purpose |
|---------------|------|------|--------|---------|
| `payflow-alb-sg` | Inbound | 80 | 0.0.0.0/0 | HTTP from internet |
| `payflow-alb-sg` | Inbound | 443 | 0.0.0.0/0 | HTTPS from internet |
| `payflow-ec2-sg` | Inbound | 8080 | payflow-alb-sg | API Gateway from ALB |
| `payflow-ec2-sg` | Inbound | 22 | Your IP/32 | SSH (your IP only!) |
| `payflow-rds-sg` | Inbound | 5432 | payflow-ec2-sg | PostgreSQL from EC2 |

```bash
# Example: Allow HTTP on ALB
aws ec2 authorize-security-group-ingress \
  --group-id sg-0alb123 \
  --protocol tcp --port 80 --cidr 0.0.0.0/0

# Allow EC2 from ALB only (reference security group)
aws ec2 authorize-security-group-ingress \
  --group-id sg-0ec2456 \
  --protocol tcp --port 8080 \
  --source-group sg-0alb123

# Allow PostgreSQL from EC2 only
aws ec2 authorize-security-group-ingress \
  --group-id sg-0rds789 \
  --protocol tcp --port 5432 \
  --source-group sg-0ec2456
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | VPC | Your private isolated network in AWS |
| 2 | CIDR | `10.0.0.0/16` gives 65,536 IP addresses |
| 3 | Subnets | Divide VPC across AZs for high availability |
| 4 | Internet Gateway | Connects VPC to public internet |
| 5 | Route tables | Direct traffic: 0.0.0.0/0 → IGW for public access |
| 6 | Security groups | Firewall rules; restrict by port and source |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `InvalidVpcID.NotFound` | Wrong VPC ID or wrong region | Verify region with `aws configure get region` |
| EC2 can't reach internet | No Internet Gateway or route | Attach IGW and add 0.0.0.0/0 route |
| Can't SSH to EC2 | Security group missing port 22 rule | Add inbound rule for SSH from your IP |
| RDS connection timeout | RDS SG doesn't allow EC2 SG | Add inbound rule referencing EC2 security group |
| ALB requires 2 AZs | Only 1 subnet created | Create subnets in 2 different AZs |
| `InsufficientFreeAddressesInSubnet` | /28 CIDR too small | Use /24 (256 IPs) for subnets |

---

<div align="center">

**[← Part 1: AWS Account Setup](phase8-part1-aws-account-setup.md)** | **[Documentation Index](../README.md)** | **[Part 3: Database & Messaging →](phase8-part3-database-messaging.md)**

</div>
