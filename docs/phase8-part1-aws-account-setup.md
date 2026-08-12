# Phase 8 · Part 1 — AWS Account Setup

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 8 — AWS Deployment |
| **Part** | 1 — Account Setup & IAM |
| **Previous** | [Phase 7 Part 3 — Frontend Pipeline](phase7-part3-frontend-pipeline.md) |
| **Next** | [Part 2 — Networking & VPC](phase8-part2-networking-vpc.md) |
| **Time** | ~1 hour |
| **Difficulty** | ★★☆☆☆ Beginner |
| **Prerequisites** | Email address, credit/debit card for AWS signup |

---

## Table of Contents

1. [Create AWS Account](#1-create-aws-account)
2. [Set Billing Budget Alert](#2-set-billing-budget-alert)
3. [Create IAM User](#3-create-iam-user)
4. [Generate Access Keys](#4-generate-access-keys)
5. [Install AWS CLI](#5-install-aws-cli)
6. [Verify Setup](#6-verify-setup)
7. [What You Learned](#what-you-learned)
8. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Create AWS Account

### Step-by-Step

1. **Go to** https://aws.amazon.com → Click "Create an AWS Account"
2. **Email**: Enter your email address (this becomes the root account)
3. **Account name**: `PayFlow-Dev` (or your preferred name)
4. **Verify email**: Check inbox, enter verification code
5. **Root password**: Create a strong password (save in password manager!)
6. **Contact info**: Select "Personal" account type, fill in details
7. **Payment**: Enter credit/debit card (won't be charged if you stay in free tier)
8. **Verify phone**: Enter phone number, receive SMS code
9. **Support plan**: Select "Basic Support - Free"
10. **Complete**: Account is created! Sign in to AWS Console

```
⚠️  IMPORTANT: Never use the root account for daily work!
    Root = unlimited power. Create an IAM user (Step 3) instead.
```

---

## 2. Set Billing Budget Alert

Prevent surprise charges by setting a $1 budget alert.

### Steps

1. **Console** → Search "Budgets" → AWS Budgets
2. Click **"Create budget"**
3. Select **"Monthly cost budget"**
4. Budget name: `PayFlow-Monthly-Budget`
5. Budget amount: **$1.00** (or $5.00 for comfort)
6. Configure alerts:
   - **Alert 1**: At 50% ($0.50) → email notification
   - **Alert 2**: At 80% ($0.80) → email notification
   - **Alert 3**: At 100% ($1.00) → email notification
7. Enter your email address for notifications
8. Click **"Create budget"**

```
┌──────────────────────────────────────────┐
│  BILLING ALERT SETTINGS                  │
├──────────────────────────────────────────┤
│  Monthly Budget:     $1.00               │
│  Alert @ 50%:        $0.50 → Email       │
│  Alert @ 80%:        $0.80 → Email       │
│  Alert @ 100%:       $1.00 → Email       │
│                                          │
│  💡 Free tier covers most PayFlow needs  │
└──────────────────────────────────────────┘
```

---

## 3. Create IAM User

### Why Not Root?

| Root Account | IAM User |
|-------------|-----------|
| Unlimited access to everything | Only permissions you grant |
| Can't be restricted | Follows least-privilege principle |
| If compromised, total loss | If compromised, limited damage |
| No audit trail of actions | Full CloudTrail audit logging |

### Steps to Create IAM User

1. **Console** → Search "IAM" → IAM Dashboard
2. Click **"Users"** → **"Create user"**
3. User name: `payflow-admin`
4. Check **"Provide user access to AWS Management Console"**
5. Select **"I want to create an IAM user"**
6. Set a console password
7. Click **"Next: Permissions"**
8. Select **"Attach policies directly"**
9. Attach these policies:
   - `AmazonEC2FullAccess`
   - `AmazonRDSFullAccess`
   - `AmazonS3FullAccess`
   - `AmazonECS_FullAccess`
   - `AmazonVPCFullAccess`
   - `AmazonSQSFullAccess`
   - `AmazonSNSFullAccess`
   - `AmazonDynamoDBFullAccess`
   - `AmazonElasticContainerRegistryPublicFullAccess`
   - `CloudFrontFullAccess`
   - `CloudWatchFullAccess`
10. Click **"Create user"**
11. **Save** the sign-in URL and credentials!

```
┌──────────────────────────────────────────────────────┐
│  IAM User Created Successfully                        │
├──────────────────────────────────────────────────────┤
│  Username:  payflow-admin                            │
│  Console:   https://123456789012.signin.aws.amazon.com │
│  Password:  (saved in password manager)              │
└──────────────────────────────────────────────────────┘
```

---

## 4. Generate Access Keys

Access keys are used for AWS CLI and CI/CD pipelines.

1. **IAM** → **Users** → `payflow-admin`
2. Click **"Security credentials"** tab
3. Scroll to **"Access keys"** → **"Create access key"**
4. Use case: **"Command Line Interface (CLI)"**
5. Acknowledge the recommendation
6. Click **"Create access key"**
7. **SAVE BOTH VALUES** (shown only once!):

```
┌──────────────────────────────────────────────────────┐
│  ⚠️  Save these NOW — you can't see them again!      │
├──────────────────────────────────────────────────────┤
│  Access Key ID:      AKIAIOSFODNN7EXAMPLE           │
│  Secret Access Key:  wJalrXUtnFEMI/K7MDENG/bPxRfi... │
└──────────────────────────────────────────────────────┘
```

---

## 5. Install AWS CLI

### Windows

```bash
# Download and run the installer
msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi

# Or via winget
winget install Amazon.AWSCLI
```

### macOS

```bash
# Using Homebrew
brew install awscli
```

### Linux

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install
```

### Configure CLI

```bash
aws configure
# AWS Access Key ID [None]: AKIAIOSFODNN7EXAMPLE
# AWS Secret Access Key [None]: wJalrXUtnFEMI/K7MDENG/bPxRfi...
# Default region name [None]: ap-south-1
# Default output format [None]: json
```

This creates `~/.aws/credentials` and `~/.aws/config`.

---

## 6. Verify Setup

```bash
# Verify identity
aws sts get-caller-identity

# Expected output:
{
    "UserId": "AIDAIOSFODNN7EXAMPLE",
    "Account": "123456789012",
    "Arn": "arn:aws:iam::123456789012:user/payflow-admin"
}
```

```bash
# Quick tests
aws ec2 describe-regions --query "Regions[0].RegionName"    # Should return a region
aws s3 ls                                                    # Should not error (may be empty)
```

If all commands work without errors, your AWS setup is complete!

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | AWS account | Root email + card needed; always enable MFA |
| 2 | Budget alerts | Set $1 alert to catch unexpected charges early |
| 3 | IAM user | Never use root for daily work; create IAM user |
| 4 | Access keys | CLI credentials; shown once — save immediately |
| 5 | AWS CLI | Command-line tool for all AWS operations |
| 6 | Verification | `aws sts get-caller-identity` confirms setup |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `Unable to locate credentials` | `aws configure` not run | Run `aws configure` and enter keys |
| `InvalidClientTokenId` | Wrong access key ID | Double-check key in `~/.aws/credentials` |
| `SignatureDoesNotMatch` | Wrong secret key | Re-enter secret key via `aws configure` |
| `An error occurred (ExpiredToken)` | Temporary credentials expired | Reconfigure with permanent access keys |
| Budget alert immediately | Previous charges from different services | Check Billing dashboard for charges |
| `aws: command not found` | CLI not installed or not in PATH | Reinstall CLI or add to system PATH |

---

<div align="center">

**[← Phase 7 Part 3: Frontend Pipeline](phase7-part3-frontend-pipeline.md)** | **[Documentation Index](../README.md)** | **[Part 2: Networking & VPC →](phase8-part2-networking-vpc.md)**

</div>
