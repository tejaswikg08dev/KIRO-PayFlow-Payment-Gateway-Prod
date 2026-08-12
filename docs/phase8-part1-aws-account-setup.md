# Phase 8 Part 1: AWS Account Setup

## Overview

AWS account creation, IAM user configuration, billing alerts, and CLI setup for PayFlow deployment.

## Account Creation Checklist

```
[ ] Create AWS account (use personal email)
[ ] Enable MFA on root account (Google Authenticator)
[ ] Create IAM admin user (never use root for daily work)
[ ] Set up billing alerts ($5, $10, $25 thresholds)
[ ] Install AWS CLI v2
[ ] Configure CLI with credentials
[ ] Verify free tier eligibility
```

## IAM Setup

### Create Admin User

```bash
# Create IAM user for PayFlow deployment
aws iam create-user --user-name payflow-deployer

# Attach policies
aws iam attach-user-policy --user-name payflow-deployer \
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2FullAccess
aws iam attach-user-policy --user-name payflow-deployer \
  --policy-arn arn:aws:iam::aws:policy/AmazonRDSFullAccess
aws iam attach-user-policy --user-name payflow-deployer \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
aws iam attach-user-policy --user-name payflow-deployer \
  --policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess
aws iam attach-user-policy --user-name payflow-deployer \
  --policy-arn arn:aws:iam::aws:policy/AmazonECS_FullAccess
aws iam attach-user-policy --user-name payflow-deployer \
  --policy-arn arn:aws:iam::aws:policy/CloudFrontFullAccess

# Create access keys
aws iam create-access-key --user-name payflow-deployer
```

### EC2 Instance Role (for services to access AWS)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "ecr:GetAuthorizationToken",
        "ses:SendEmail",
        "ses:SendRawEmail",
        "sns:Publish",
        "dynamodb:PutItem",
        "dynamodb:GetItem",
        "dynamodb:Query",
        "cloudwatch:PutMetricData",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    }
  ]
}
```

## Billing Alerts

```bash
# Create billing alarm ($10 threshold)
aws cloudwatch put-metric-alarm \
  --alarm-name "PayFlow-BillingAlarm-10USD" \
  --alarm-description "Alert when estimated charges exceed $10" \
  --metric-name EstimatedCharges \
  --namespace AWS/Billing \
  --statistic Maximum \
  --period 21600 \
  --threshold 10 \
  --comparison-operator GreaterThanThreshold \
  --dimensions Name=Currency,Value=USD \
  --evaluation-periods 1 \
  --alarm-actions arn:aws:sns:us-east-1:ACCOUNT_ID:billing-alerts
```

## AWS CLI Configuration

```bash
# Install AWS CLI v2
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# Configure credentials
aws configure
# AWS Access Key ID: AKIA...
# AWS Secret Access Key: ...
# Default region: ap-south-1
# Default output format: json

# Verify
aws sts get-caller-identity
```

## Region Selection

| Region | Code | Rationale |
|--------|------|-----------|
| **Mumbai** | `ap-south-1` | Closest to Indian users, lowest latency |
| N. Virginia | `us-east-1` | Most services available (alternative) |

## Security Best Practices

| Practice | Implementation |
|----------|---------------|
| MFA on root | TOTP authenticator app |
| No root access keys | Use IAM users only |
| Least privilege | Service-specific policies |
| Credential rotation | Rotate keys every 90 days |
| Billing alerts | $5, $10, $25 thresholds |
| CloudTrail | Audit all API calls |
| Cost Explorer | Monitor daily spend |
