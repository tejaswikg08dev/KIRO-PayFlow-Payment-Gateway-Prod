# Phase 8 Part 6: Load Balancer & Go-Live

## Overview

Application Load Balancer configuration, target groups, health checks, HTTPS setup, and final go-live checklist.

## ALB Setup

```bash
# Create Application Load Balancer
aws elbv2 create-load-balancer \
  --name payflow-alb \
  --subnets subnet-public-1 subnet-public-2 \
  --security-groups sg-alb \
  --scheme internet-facing \
  --type application \
  --tags Key=Project,Value=PayFlow

# Create target group
aws elbv2 create-target-group \
  --name payflow-api-tg \
  --protocol HTTP \
  --port 8080 \
  --vpc-id vpc-xxx \
  --target-type instance \
  --health-check-protocol HTTP \
  --health-check-path /actuator/health \
  --health-check-interval-seconds 30 \
  --healthy-threshold-count 2 \
  --unhealthy-threshold-count 3

# Register EC2 instance
aws elbv2 register-targets \
  --target-group-arn arn:aws:elasticloadbalancing:...:targetgroup/payflow-api-tg/xxx \
  --targets Id=i-xxx,Port=8080

# Create HTTPS listener (requires ACM certificate)
aws elbv2 create-listener \
  --load-balancer-arn arn:aws:elasticloadbalancing:...:loadbalancer/app/payflow-alb/xxx \
  --protocol HTTPS \
  --port 443 \
  --certificates CertificateArn=arn:aws:acm:...:certificate/xxx \
  --default-actions Type=forward,TargetGroupArn=arn:aws:elasticloadbalancing:...:targetgroup/payflow-api-tg/xxx

# HTTP → HTTPS redirect
aws elbv2 create-listener \
  --load-balancer-arn arn:aws:elasticloadbalancing:...:loadbalancer/app/payflow-alb/xxx \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=redirect,RedirectConfig='{Protocol=HTTPS,Port=443,StatusCode=HTTP_301}'
```

## Architecture After ALB

```
Internet
    │
    ▼
┌──────────────────┐
│  Route 53 DNS    │  api.payflow.io → ALB
└────────┬─────────┘
         │
┌────────▼─────────┐
│   ALB (HTTPS)    │  SSL termination
│   Port 443       │
└────────┬─────────┘
         │ HTTP :8080
┌────────▼─────────┐
│   EC2 Instance   │  API Gateway container
│   Port 8080      │  → routes to other services
└──────────────────┘
```

## Health Check Configuration

| Parameter | Value |
|-----------|-------|
| Protocol | HTTP |
| Path | `/actuator/health` |
| Port | 8080 |
| Interval | 30 seconds |
| Timeout | 5 seconds |
| Healthy threshold | 2 consecutive |
| Unhealthy threshold | 3 consecutive |

## DNS Configuration (Route 53)

```bash
# Create hosted zone
aws route53 create-hosted-zone \
  --name payflow.io \
  --caller-reference payflow-$(date +%s)

# Create A record (alias to ALB)
aws route53 change-resource-record-sets \
  --hosted-zone-id Z1234 \
  --change-batch '{
    "Changes": [{
      "Action": "CREATE",
      "ResourceRecordSet": {
        "Name": "api.payflow.io",
        "Type": "A",
        "AliasTarget": {
          "HostedZoneId": "ZALB_ZONE_ID",
          "DNSName": "payflow-alb-xxx.ap-south-1.elb.amazonaws.com",
          "EvaluateTargetHealth": true
        }
      }
    }]
  }'
```

## Go-Live Checklist

```
═══ PRE-DEPLOYMENT ═══
[ ] All tests passing in CI
[ ] Docker images built and pushed to ECR
[ ] RDS database created with correct schemas
[ ] Flyway migrations applied successfully
[ ] Environment variables set on EC2
[ ] Security groups configured correctly
[ ] SSL certificate issued and validated

═══ DEPLOYMENT ═══
[ ] Pull latest images on EC2
[ ] Start all containers with docker compose
[ ] Verify each service health endpoint
[ ] Verify API Gateway routes correctly
[ ] Test authentication flow (register → login)
[ ] Test payment flow (create order → pay → capture)
[ ] Verify Kafka events publishing
[ ] Check CloudWatch logs for errors

═══ POST-DEPLOYMENT ═══
[ ] CloudFront serving frontend correctly
[ ] HTTPS working on all domains
[ ] DNS resolving correctly
[ ] ALB health checks passing
[ ] Billing alerts configured
[ ] Monitoring dashboards created
[ ] Test from external network (not SSH)
[ ] Document any issues found

═══ SMOKE TESTS ═══
[ ] curl https://api.payflow.io/actuator/health → 200
[ ] POST /api/v1/auth/register → 201
[ ] POST /api/v1/auth/login → 200 + JWT
[ ] POST /api/v1/orders → 201 (with API key)
[ ] GET https://dashboard.payflow.io → 200
```

## Monitoring After Go-Live

```bash
# Check ALB metrics
aws cloudwatch get-metric-statistics \
  --namespace AWS/ApplicationELB \
  --metric-name RequestCount \
  --dimensions Name=LoadBalancer,Value=app/payflow-alb/xxx \
  --start-time 2024-01-15T00:00:00Z \
  --end-time 2024-01-15T23:59:59Z \
  --period 3600 \
  --statistics Sum

# Check target health
aws elbv2 describe-target-health \
  --target-group-arn arn:aws:elasticloadbalancing:...:targetgroup/payflow-api-tg/xxx
```

## Rollback Plan

```bash
# If deployment fails:
# 1. Check logs
docker compose logs --tail 50

# 2. Rollback to previous image tags
docker compose down
# Edit docker-compose.yml to use previous :sha tags
docker compose up -d

# 3. Verify health
curl http://localhost:8080/actuator/health
```
