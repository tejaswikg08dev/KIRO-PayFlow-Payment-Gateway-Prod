# Phase 7: CI/CD Pipeline Overview

## Overview

Continuous Integration and Continuous Deployment for PayFlow using GitHub Actions. Automates testing, building Docker images, pushing to ECR, and deploying to AWS.

## Pipeline Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    CI/CD PIPELINE FLOW                             │
└──────────────────────────────────────────────────────────────────┘

  Push to main/PR
       │
       ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│   LINT &     │──▶│    BUILD     │──▶│    TEST      │
│   FORMAT     │   │   (Maven)    │   │  (Unit +     │
│              │   │              │   │  Integration)│
└──────────────┘   └──────────────┘   └──────┬───────┘
                                              │
                                    ┌─────────▼─────────┐
                                    │   DOCKER BUILD    │
                                    │   (Multi-stage)   │
                                    └─────────┬─────────┘
                                              │
                                    ┌─────────▼─────────┐
                                    │   PUSH TO ECR     │
                                    │   (Container      │
                                    │    Registry)      │
                                    └─────────┬─────────┘
                                              │
                                    ┌─────────▼─────────┐
                                    │   DEPLOY TO EC2   │
                                    │   (docker pull +  │
                                    │    docker run)    │
                                    └───────────────────┘
```

## Pipeline Triggers

| Trigger | Actions | Environment |
|---------|---------|-------------|
| Push to `main` | Full pipeline (build → deploy) | Production |
| Pull Request | Build + Test only | None (validation) |
| Push to `develop` | Build + Test + Deploy | Staging |
| Manual dispatch | Any stage | Configurable |
| Tag `v*` | Build + Deploy + Release | Production |

## Backend Pipeline Summary

```yaml
# .github/workflows/ci-backend.yml
name: Backend CI/CD

on:
  push:
    branches: [main, develop]
    paths: ['backend/**']
  pull_request:
    branches: [main]
    paths: ['backend/**']

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_DB: payflow_test
          POSTGRES_USER: test
          POSTGRES_PASSWORD: test
        ports: ['5432:5432']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'corretto' }
      - run: mvn test -pl payment-service,identity-service,merchant-service

  build-and-push:
    needs: test
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: aws-actions/configure-aws-credentials@v4
      - uses: aws-actions/amazon-ecr-login@v2
      - run: |
          docker build -t payflow/payment-service ./backend/payment-service
          docker push $ECR_REGISTRY/payflow/payment-service:latest

  deploy:
    needs: build-and-push
    runs-on: ubuntu-latest
    steps:
      - run: |
          ssh ec2-user@$EC2_HOST "
            docker pull $ECR_REGISTRY/payflow/payment-service:latest
            docker stop payment-service || true
            docker run -d --name payment-service \
              --env-file /home/ec2-user/.env \
              -p 8082:8082 \
              $ECR_REGISTRY/payflow/payment-service:latest
          "
```

## Frontend Pipeline Summary

```yaml
# .github/workflows/ci-frontend.yml
name: Frontend CI/CD

on:
  push:
    branches: [main]
    paths: ['frontend/**']

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '18' }
      - run: npm ci
      - run: npm run lint
      - run: npm run build
      - uses: aws-actions/configure-aws-credentials@v4
      - run: aws s3 sync dist/ s3://payflow-dashboard --delete
      - run: aws cloudfront create-invalidation --distribution-id $CF_ID --paths "/*"
```

## Deployment Strategies

| Strategy | Risk | Downtime | Complexity |
|----------|------|----------|------------|
| Rolling Update | Low | Zero | Medium |
| Blue/Green | Very Low | Zero | High |
| Canary | Very Low | Zero | High |
| **In-Place** ✓ | Medium | Brief | **Low** |

For PayFlow (single EC2, free tier): In-place deployment with docker stop/start. Acceptable for a portfolio project.

## Secrets Management

| Secret | GitHub Actions | EC2 Runtime |
|--------|---------------|-------------|
| DB Password | `${{ secrets.DB_PASSWORD }}` | `.env` file |
| JWT Secret | `${{ secrets.JWT_SECRET }}` | `.env` file |
| AWS Credentials | OIDC role assumption | Instance profile |
| ECR Registry | Output of login action | Same |

## Pipeline Notifications

```yaml
# Notify on failure
- name: Notify on failure
  if: failure()
  uses: 8398a7/action-slack@v3
  with:
    status: failure
    text: "🚨 PayFlow deployment failed!"
```
