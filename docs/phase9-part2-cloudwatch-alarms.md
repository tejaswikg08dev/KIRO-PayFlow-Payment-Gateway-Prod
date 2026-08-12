# Phase 9 Part 2: CloudWatch Dashboards & Alarms

## Overview

CloudWatch configuration for log aggregation, custom dashboards, and automated alarms for PayFlow services.

## CloudWatch Log Groups

```bash
# Create log groups for each service
aws logs create-log-group --log-group-name /payflow/api-gateway
aws logs create-log-group --log-group-name /payflow/payment-service
aws logs create-log-group --log-group-name /payflow/identity-service
aws logs create-log-group --log-group-name /payflow/merchant-service
aws logs create-log-group --log-group-name /payflow/routing-service
aws logs create-log-group --log-group-name /payflow/settlement-service

# Set retention (7 days for free tier)
aws logs put-retention-policy \
  --log-group-name /payflow/payment-service \
  --retention-in-days 7
```

## Docker Log Driver Configuration

```yaml
# docker-compose.yml - send logs to CloudWatch
services:
  payment-service:
    logging:
      driver: awslogs
      options:
        awslogs-group: /payflow/payment-service
        awslogs-region: ap-south-1
        awslogs-stream-prefix: payment
```

## CloudWatch Alarms

```bash
# Alarm: High Error Rate (> 5% of requests are 5xx)
aws cloudwatch put-metric-alarm \
  --alarm-name "PayFlow-HighErrorRate" \
  --alarm-description "Error rate exceeds 5%" \
  --metric-name "5XXError" \
  --namespace "AWS/ApplicationELB" \
  --statistic Average \
  --period 300 \
  --threshold 5 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --alarm-actions arn:aws:sns:ap-south-1:ACCOUNT:payflow-alerts

# Alarm: High Latency (P95 > 2 seconds)
aws cloudwatch put-metric-alarm \
  --alarm-name "PayFlow-HighLatency" \
  --alarm-description "P95 latency exceeds 2s" \
  --metric-name "TargetResponseTime" \
  --namespace "AWS/ApplicationELB" \
  --extended-statistic "p95" \
  --period 300 \
  --threshold 2.0 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --alarm-actions arn:aws:sns:ap-south-1:ACCOUNT:payflow-alerts

# Alarm: EC2 CPU > 80%
aws cloudwatch put-metric-alarm \
  --alarm-name "PayFlow-HighCPU" \
  --metric-name CPUUtilization \
  --namespace AWS/EC2 \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=InstanceId,Value=i-xxx \
  --evaluation-periods 3 \
  --alarm-actions arn:aws:sns:ap-south-1:ACCOUNT:payflow-alerts

# Alarm: RDS Free Storage < 2GB
aws cloudwatch put-metric-alarm \
  --alarm-name "PayFlow-LowDiskSpace" \
  --metric-name FreeStorageSpace \
  --namespace AWS/RDS \
  --statistic Average \
  --period 300 \
  --threshold 2147483648 \
  --comparison-operator LessThanThreshold \
  --dimensions Name=DBInstanceIdentifier,Value=payflow-db \
  --evaluation-periods 1 \
  --alarm-actions arn:aws:sns:ap-south-1:ACCOUNT:payflow-alerts
```

## Custom Dashboard (JSON Definition)

```json
{
  "widgets": [
    {
      "type": "metric",
      "properties": {
        "title": "Payment Success Rate",
        "metrics": [
          ["PayFlow", "payment.authorized.total", { "stat": "Sum", "period": 300 }],
          ["PayFlow", "payment.failed.total", { "stat": "Sum", "period": 300 }]
        ],
        "view": "timeSeries",
        "region": "ap-south-1",
        "period": 300
      }
    },
    {
      "type": "metric",
      "properties": {
        "title": "API Latency (P95)",
        "metrics": [
          ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", "app/payflow-alb/xxx", { "stat": "p95" }]
        ]
      }
    },
    {
      "type": "metric",
      "properties": {
        "title": "Request Count",
        "metrics": [
          ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", "app/payflow-alb/xxx", { "stat": "Sum" }]
        ]
      }
    },
    {
      "type": "metric",
      "properties": {
        "title": "EC2 CPU & Memory",
        "metrics": [
          ["AWS/EC2", "CPUUtilization", "InstanceId", "i-xxx"],
          ["CWAgent", "mem_used_percent", "InstanceId", "i-xxx"]
        ]
      }
    }
  ]
}
```

## Log Insights Queries

```sql
-- Find all payment failures in last hour
fields @timestamp, @message
| filter @message like /payment.failed/
| sort @timestamp desc
| limit 50

-- Count errors by service
fields @logGroup
| filter level = "ERROR"
| stats count() by @logGroup
| sort count desc

-- Average payment processing time
fields @timestamp, context.responseTime
| filter @message like /Payment authorized/
| stats avg(context.responseTime) as avgLatency, 
        pct(context.responseTime, 95) as p95
  by bin(5m)

-- Find orders stuck in PROCESSING
fields @timestamp, context.orderId, context.status
| filter context.status = "PROCESSING"
| filter @timestamp > ago(30m)
```

## SNS Alert Topic

```bash
# Create alert topic
aws sns create-topic --name payflow-alerts

# Subscribe email
aws sns subscribe \
  --topic-arn arn:aws:sns:ap-south-1:ACCOUNT:payflow-alerts \
  --protocol email \
  --notification-endpoint your-email@example.com
```

## Alarm Summary

| Alarm | Metric | Threshold | Action |
|-------|--------|-----------|--------|
| High Error Rate | 5xx count | > 5% for 10 min | Email alert |
| High Latency | P95 response time | > 2s for 10 min | Email alert |
| Service Down | Health check | 3 consecutive fails | Email + restart |
| High CPU | EC2 CPU% | > 80% for 15 min | Email alert |
| Low Disk | RDS free space | < 2GB | Email alert |
| Billing Spike | Estimated charges | > $10 | Email alert |

## Runbook for Common Alerts

| Alert | Likely Cause | Resolution |
|-------|-------------|------------|
| High Error Rate | DB connection exhausted | Check connection pool, restart service |
| High Latency | Kafka consumer lag | Check consumer group, increase partitions |
| Service Down | OOM killed | Increase memory limit, check for leaks |
| High CPU | Settlement batch running | Normal during batch; alert if sustained |
| Low Disk | Log/data growth | Increase volume or clean old data |
