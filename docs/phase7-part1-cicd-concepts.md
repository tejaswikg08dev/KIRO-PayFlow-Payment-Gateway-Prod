# Phase 7 Part 1: CI/CD Concepts

## Overview

Foundational CI/CD concepts, deployment strategies, and theory behind automated software delivery pipelines.

## What is CI/CD?

```
┌─────────────────────────────────────────────────────────────┐
│ Continuous Integration (CI)                                   │
│ • Developers merge code frequently (daily)                   │
│ • Automated build + tests run on every merge                 │
│ • Catch bugs early, before they compound                     │
├─────────────────────────────────────────────────────────────┤
│ Continuous Delivery (CD)                                      │
│ • Code is always in a deployable state                       │
│ • Deploy to staging automatically                            │
│ • One-click deploy to production (manual gate)               │
├─────────────────────────────────────────────────────────────┤
│ Continuous Deployment (Full CD)                               │
│ • Every passing commit goes to production automatically      │
│ • No manual approval step                                    │
│ • Requires excellent test coverage + monitoring              │
└─────────────────────────────────────────────────────────────┘
```

## CI/CD Pipeline Stages

| Stage | Purpose | Tools | Duration |
|-------|---------|-------|----------|
| Source | Code commit triggers pipeline | Git, GitHub | Instant |
| Lint | Code quality checks | Checkstyle, ESLint | ~30s |
| Build | Compile code, resolve deps | Maven, npm | ~2min |
| Unit Test | Fast isolated tests | JUnit, Jest | ~1min |
| Integration Test | DB + Kafka tests | Testcontainers | ~3min |
| Security Scan | Vulnerability check | Trivy, OWASP | ~1min |
| Docker Build | Create container image | Docker | ~2min |
| Push | Upload to registry | ECR, Docker Hub | ~1min |
| Deploy Staging | Release to staging env | SSH, ECS | ~2min |
| Smoke Test | Basic health verification | curl, REST Assured | ~30s |
| Deploy Production | Release to prod | Same as staging | ~2min |

## Deployment Strategies Explained

### Rolling Deployment

```
Time 0: [V1] [V1] [V1] [V1]  ← 4 instances running V1
Time 1: [V2] [V1] [V1] [V1]  ← Replace one at a time
Time 2: [V2] [V2] [V1] [V1]
Time 3: [V2] [V2] [V2] [V1]
Time 4: [V2] [V2] [V2] [V2]  ← All running V2
```
- **Pros:** Zero downtime, gradual rollout
- **Cons:** Temporary mixed versions, complex rollback

### Blue-Green Deployment

```
┌─────────────┐     ┌─────────────┐
│  BLUE (V1)  │ ←── │ Load        │ ← Active traffic
│  (current)  │     │ Balancer    │
└─────────────┘     └──────┬──────┘
                           │
┌─────────────┐            │
│  GREEN (V2) │ ←──────────┘  (switch traffic)
│  (new)      │
└─────────────┘
```
- **Pros:** Instant rollback (switch back to blue), zero downtime
- **Cons:** Double infrastructure cost during deployment

### Canary Deployment

```
┌─────────────┐     ┌─────────────┐
│  V1 (95%)   │ ←── │ Load        │
│  [||||||||] │     │ Balancer    │
└─────────────┘     └──────┬──────┘
                           │ 5% traffic
┌─────────────┐            │
│  V2 (5%)    │ ←──────────┘
│  [|]        │
└─────────────┘

Monitor metrics → if healthy → increase to 25% → 50% → 100%
```
- **Pros:** Minimal blast radius, data-driven decisions
- **Cons:** Complex routing rules, monitoring required

## PayFlow's Approach

For a portfolio project on AWS Free Tier:
- **Strategy:** In-place deployment (stop old, start new)
- **Acceptable trade-off:** Brief downtime (~10s) during restart
- **Risk mitigation:** Health checks, automated rollback script

## GitFlow for PayFlow

```
main (production)
  │
  ├── develop (integration)
  │     │
  │     ├── feature/payment-refund
  │     ├── feature/webhook-retry
  │     └── bugfix/order-expiry
  │
  └── hotfix/security-patch (emergency fixes)
```

## Branch Protection Rules

| Rule | Purpose |
|------|---------|
| Require PR review | At least 1 approval before merge |
| Require passing CI | All tests must pass |
| Require up-to-date | Branch must be current with main |
| No force push | Protect commit history |

## Artifact Versioning

```
Image tag format: {service}:{git-sha-short}
Example: payflow/payment-service:a1b2c3d

For releases: payflow/payment-service:v1.2.3
```

## Rollback Procedure

```bash
# If deployment fails:
# 1. Identify last known good image
docker images payflow/payment-service --format "{{.Tag}} {{.CreatedAt}}"

# 2. Roll back to previous version
docker stop payment-service
docker run -d --name payment-service \
  $ECR_REGISTRY/payflow/payment-service:previous-sha

# 3. Investigate failure from logs
docker logs payment-service-failed --tail 100
```
