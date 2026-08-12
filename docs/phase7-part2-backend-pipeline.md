# Phase 7 Part 2: Backend CI/CD Pipeline

## Overview

Complete GitHub Actions workflow for backend services: build, test, Docker image creation, ECR push, and EC2 deployment.

## Full Backend Workflow

```yaml
# .github/workflows/ci-backend.yml
name: Backend CI/CD Pipeline

on:
  push:
    branches: [main, develop]
    paths: ['backend/**']
  pull_request:
    branches: [main]
    paths: ['backend/**']

env:
  AWS_REGION: ap-south-1
  ECR_REGISTRY: ${{ secrets.AWS_ACCOUNT_ID }}.dkr.ecr.ap-south-1.amazonaws.com
  JAVA_VERSION: '17'

jobs:
  # ═══════════════════════════════════
  # Job 1: Build and Unit Test
  # ═══════════════════════════════════
  build-and-test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [identity-service, merchant-service, payment-service, routing-service, settlement-service, webhook-service, notification-service, bank-simulator, api-gateway]
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java ${{ env.JAVA_VERSION }}
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'corretto'
          cache: 'maven'

      - name: Build and Test ${{ matrix.service }}
        working-directory: backend
        run: mvn clean test -pl ${{ matrix.service }} -am -B

      - name: Upload Test Results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results-${{ matrix.service }}
          path: backend/${{ matrix.service }}/target/surefire-reports/

  # ═══════════════════════════════════
  # Job 2: Integration Tests
  # ═══════════════════════════════════
  integration-tests:
    runs-on: ubuntu-latest
    needs: build-and-test
    services:
      postgres:
        image: postgres:15-alpine
        env:
          POSTGRES_DB: payflow_test
          POSTGRES_USER: test
          POSTGRES_PASSWORD: test
        ports: ['5432:5432']
        options: --health-cmd pg_isready --health-interval 10s
      redis:
        image: redis:7-alpine
        ports: ['6379:6379']
        options: --health-cmd "redis-cli ping" --health-interval 10s
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: 'corretto'
          cache: 'maven'

      - name: Run Integration Tests
        working-directory: backend
        run: mvn verify -Pintegration -pl payment-service -am -B
        env:
          DB_HOST: localhost
          DB_PORT: 5432
          REDIS_HOST: localhost

  # ═══════════════════════════════════
  # Job 3: Docker Build and Push
  # ═══════════════════════════════════
  docker-build-push:
    runs-on: ubuntu-latest
    needs: integration-tests
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    strategy:
      matrix:
        service: [api-gateway, identity-service, merchant-service, payment-service, routing-service, bank-simulator, settlement-service, webhook-service, notification-service]
    steps:
      - uses: actions/checkout@v4

      - name: Configure AWS Credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION }}

      - name: Login to ECR
        uses: aws-actions/amazon-ecr-login@v2

      - name: Set up Java and Build JAR
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'corretto'
          cache: 'maven'

      - name: Build JAR
        working-directory: backend
        run: mvn package -pl ${{ matrix.service }} -am -DskipTests -B

      - name: Build and Push Docker Image
        working-directory: backend/${{ matrix.service }}
        run: |
          IMAGE_TAG=${{ github.sha }}
          docker build -t $ECR_REGISTRY/payflow/${{ matrix.service }}:$IMAGE_TAG .
          docker tag $ECR_REGISTRY/payflow/${{ matrix.service }}:$IMAGE_TAG \
                     $ECR_REGISTRY/payflow/${{ matrix.service }}:latest
          docker push $ECR_REGISTRY/payflow/${{ matrix.service }}:$IMAGE_TAG
          docker push $ECR_REGISTRY/payflow/${{ matrix.service }}:latest

  # ═══════════════════════════════════
  # Job 4: Deploy to EC2
  # ═══════════════════════════════════
  deploy:
    runs-on: ubuntu-latest
    needs: docker-build-push
    environment: production
    steps:
      - name: Deploy to EC2
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ec2-user
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            # Login to ECR
            aws ecr get-login-password --region ap-south-1 | \
              docker login --username AWS --password-stdin ${{ env.ECR_REGISTRY }}

            # Pull latest images
            docker pull ${{ env.ECR_REGISTRY }}/payflow/api-gateway:latest
            docker pull ${{ env.ECR_REGISTRY }}/payflow/payment-service:latest
            docker pull ${{ env.ECR_REGISTRY }}/payflow/identity-service:latest

            # Restart services with new images
            cd /home/ec2-user/payflow
            docker compose down
            docker compose up -d

            # Verify health
            sleep 15
            curl -f http://localhost:8080/actuator/health || exit 1
            echo "✅ Deployment successful"

      - name: Health Check
        run: |
          sleep 30
          HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://${{ secrets.EC2_HOST }}:8080/actuator/health)
          if [ "$HTTP_STATUS" != "200" ]; then
            echo "❌ Health check failed with status $HTTP_STATUS"
            exit 1
          fi
          echo "✅ All services healthy"
```

## Pipeline Duration Targets

| Stage | Target | Actual |
|-------|--------|--------|
| Build + Unit Tests | < 3 min | ~2 min |
| Integration Tests | < 5 min | ~3 min |
| Docker Build + Push | < 5 min | ~4 min |
| Deploy + Health Check | < 3 min | ~2 min |
| **Total Pipeline** | **< 15 min** | **~11 min** |

## Caching Strategy

```yaml
# Maven dependency caching
- uses: actions/cache@v4
  with:
    path: ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: ${{ runner.os }}-maven-

# Docker layer caching
- uses: docker/build-push-action@v5
  with:
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

## Failure Handling

```yaml
# Rollback on deployment failure
- name: Rollback on Failure
  if: failure()
  uses: appleboy/ssh-action@v1
  with:
    host: ${{ secrets.EC2_HOST }}
    username: ec2-user
    key: ${{ secrets.EC2_SSH_KEY }}
    script: |
      docker compose down
      docker tag payflow/payment-service:previous payflow/payment-service:latest
      docker compose up -d
      echo "🔄 Rolled back to previous version"
```
