# Phase 7 · Part 2 — Backend CI/CD Pipeline

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 7 — CI/CD Pipeline |
| **Part** | 2 — Backend Pipeline (Build, Docker, Deploy) |
| **Previous** | [Part 1 — CI/CD Concepts](phase7-part1-cicd-concepts.md) |
| **Next** | [Part 3 — Frontend Pipeline](phase7-part3-frontend-pipeline.md) |
| **Time** | ~2.5 hours |
| **Difficulty** | ★★★☆☆ Intermediate |
| **Prerequisites** | GitHub Actions basics, Docker, AWS ECR/EC2 |

---

## Table of Contents

1. [Pipeline Overview](#1-pipeline-overview)
2. [Trigger Configuration](#2-trigger-configuration)
3. [Build Job](#3-build-job)
4. [Docker Job](#4-docker-job)
5. [Deploy Job](#5-deploy-job)
6. [GitHub Secrets Setup](#6-github-secrets-setup)
7. [What You Learned](#what-you-learned)
8. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Pipeline Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                    ci-backend.yml Pipeline                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  TRIGGER: push to main (backend/** changed)                       │
│                                                                    │
│  ┌──────────┐      ┌──────────┐      ┌──────────┐               │
│  │  BUILD   │ ───► │  DOCKER  │ ───► │  DEPLOY  │               │
│  │          │      │          │      │          │               │
│  │ Checkout │      │ Build img│      │ SSH EC2  │               │
│  │ Java 17  │      │ Push ECR │      │ Pull img │               │
│  │ mvn verify      │ (per svc)│      │ Compose  │               │
│  │ Coverage │      │          │      │ Health ✓ │               │
│  └──────────┘      └──────────┘      └──────────┘               │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. Trigger Configuration

```yaml
# .github/workflows/ci-backend.yml
name: CI Backend

on:
  push:
    branches: [main]
    paths:
      - 'backend/**'           # Only when backend code changes
      - 'docker-compose*.yml'  # Or compose files change
      - '!backend/**/*.md'     # Ignore markdown changes
  pull_request:
    branches: [main]
    paths:
      - 'backend/**'

env:
  AWS_REGION: ap-south-1
  ECR_REGISTRY: 123456789012.dkr.ecr.ap-south-1.amazonaws.com
  JAVA_VERSION: '17'
```

**Why path filtering?** PayFlow has frontend + backend. We don't want to rebuild all 11 Java services when only a CSS file changes.

---

## 3. Build Job

The build job compiles all backend services and runs tests.

```yaml
jobs:
  build:
    name: Build & Test
    runs-on: ubuntu-latest
    
    steps:
      # ─── Step 1: Check out repository ──────────────
      - name: Checkout code
        uses: actions/checkout@v4

      # ─── Step 2: Set up Java 17 ────────────────────
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'temurin'
          cache: 'maven'           # Cache ~/.m2 for faster builds

      # ─── Step 3: Build and test all services ────────
      - name: Build with Maven
        working-directory: ./backend
        run: |
          mvn clean verify -B \
            -Dspring.profiles.active=test \
            -Djacoco.skip=false
        # -B = batch mode (no interactive prompts)
        # verify = compile + test + integration-test + check coverage

      # ─── Step 4: Upload test reports ────────────────
      - name: Upload test reports
        if: always()               # Upload even if tests fail
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: backend/**/target/surefire-reports/

      # ─── Step 5: Upload coverage report ─────────────
      - name: Upload JaCoCo coverage
        uses: actions/upload-artifact@v4
        with:
          name: coverage-report
          path: backend/**/target/site/jacoco/

      # ─── Step 6: Comment coverage on PR ─────────────
      - name: Add coverage to PR
        if: github.event_name == 'pull_request'
        uses: madrapps/jacoco-report@v1.6
        with:
          paths: backend/**/target/site/jacoco/jacoco.xml
          token: ${{ secrets.GITHUB_TOKEN }}
          min-coverage-overall: 80
```

---

## 4. Docker Job

After build passes, build Docker images and push to AWS ECR.

```yaml
  docker:
    name: Build & Push Docker Images
    runs-on: ubuntu-latest
    needs: build                    # Only runs after build succeeds
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    
    strategy:
      matrix:
        service:
          - api-gateway
          - identity-service
          - merchant-service
          - payment-service
          - routing-engine
          - bank-simulator
          - settlement-service
          - notification-service
          - webhook-service
          - analytics-service
          - reconciliation-service

    steps:
      # ─── Step 1: Checkout ──────────────────────────
      - name: Checkout code
        uses: actions/checkout@v4

      # ─── Step 2: Configure AWS credentials ─────────
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION }}

      # ─── Step 3: Login to ECR ──────────────────────
      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      # ─── Step 4: Build and push image ──────────────
      - name: Build and push Docker image
        env:
          IMAGE_TAG: ${{ github.sha }}
        run: |
          cd backend/${{ matrix.service }}
          
          # Build the image
          docker build -t ${{ env.ECR_REGISTRY }}/payflow-${{ matrix.service }}:$IMAGE_TAG .
          docker tag ${{ env.ECR_REGISTRY }}/payflow-${{ matrix.service }}:$IMAGE_TAG \
                     ${{ env.ECR_REGISTRY }}/payflow-${{ matrix.service }}:latest
          
          # Push both tags
          docker push ${{ env.ECR_REGISTRY }}/payflow-${{ matrix.service }}:$IMAGE_TAG
          docker push ${{ env.ECR_REGISTRY }}/payflow-${{ matrix.service }}:latest
          
          echo "✅ Pushed payflow-${{ matrix.service }}:$IMAGE_TAG"
```

**Why matrix strategy?** Builds all 11 services in parallel — much faster than sequential.

---

## 5. Deploy Job

After images are pushed, deploy to EC2 via SSH.

```yaml
  deploy:
    name: Deploy to EC2
    runs-on: ubuntu-latest
    needs: docker
    if: github.ref == 'refs/heads/main'
    environment: production         # Requires manual approval (optional)

    steps:
      # ─── Step 1: SSH and deploy ────────────────────
      - name: Deploy via SSH
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ec2-user
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            # Navigate to project directory
            cd /opt/payflow
            
            # Login to ECR
            aws ecr get-login-password --region ap-south-1 | \
              docker login --username AWS --password-stdin ${{ secrets.ECR_REGISTRY }}
            
            # Pull latest images
            docker compose pull
            
            # Restart services with zero downtime (rolling)
            docker compose up -d --remove-orphans
            
            # Wait for health checks
            sleep 30
            
            # Verify all services are healthy
            docker compose ps --format json | jq -e 'all(.Health == "healthy")'
            
            echo "✅ Deployment complete!"

      # ─── Step 2: Verify deployment ─────────────────
      - name: Health check
        run: |
          # Wait for services to stabilize
          sleep 10
          
          # Check API Gateway health
          curl -f http://${{ secrets.EC2_HOST }}:8080/actuator/health || exit 1
          
          echo "✅ Health check passed"

      # ─── Step 3: Notify on failure ─────────────────
      - name: Notify on failure
        if: failure()
        run: |
          echo "❌ Deployment failed! Check logs."
          # Could send Slack/email notification here
```

---

## 6. GitHub Secrets Setup

Navigate to: **Repository → Settings → Secrets and variables → Actions**

| Secret Name | Value | Purpose |
|-------------|-------|---------|
| `AWS_ACCESS_KEY_ID` | `AKIA...` | AWS programmatic access |
| `AWS_SECRET_ACCESS_KEY` | `wJal...` | AWS secret key |
| `ECR_REGISTRY` | `123456789012.dkr.ecr.ap-south-1.amazonaws.com` | ECR URL |
| `EC2_HOST` | `13.235.xx.xx` | EC2 public IP |
| `EC2_SSH_KEY` | `-----BEGIN RSA...` | PEM file contents |

### How to Add a Secret

```bash
# 1. Go to GitHub repo → Settings → Secrets → Actions
# 2. Click "New repository secret"
# 3. Name: AWS_ACCESS_KEY_ID
# 4. Value: paste the key
# 5. Click "Add secret"

# For SSH key, paste the ENTIRE .pem file content including:
# -----BEGIN RSA PRIVATE KEY-----
# ... (key content) ...
# -----END RSA PRIVATE KEY-----
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Pipeline structure | Build → Docker → Deploy (sequential jobs) |
| 2 | Path filtering | Only trigger when relevant files change |
| 3 | Maven in CI | `mvn clean verify` builds, tests, and checks coverage |
| 4 | Matrix strategy | Parallel Docker builds for all 11 services |
| 5 | ECR push | Tag with git SHA + latest for traceability |
| 6 | SSH deploy | Pull images on EC2, docker compose up |
| 7 | Secrets | Store credentials securely in GitHub Secrets |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `mvn: command not found` | Java not set up | Add `actions/setup-java@v4` step before build |
| Tests pass locally but fail in CI | Different env (timezone, locale) | Use `SPRING_PROFILES_ACTIVE=test` |
| Docker push: `no basic auth` | ECR login expired or not configured | Add `aws-actions/amazon-ecr-login@v2` step |
| SSH connection refused | Security group doesn't allow SSH from GitHub | Add GitHub Actions IP ranges to SG |
| Matrix job timeout | One service takes too long to build | Add `timeout-minutes: 15` per job |
| `docker compose` not found on EC2 | Old Docker version | Install Docker Compose V2 plugin on EC2 |
| Health check fails after deploy | Service still starting | Increase sleep time or add retry loop |

---

<div align="center">

**[← Part 1: CI/CD Concepts](phase7-part1-cicd-concepts.md)** | **[Documentation Index](../README.md)** | **[Part 3: Frontend Pipeline →](phase7-part3-frontend-pipeline.md)**

</div>
