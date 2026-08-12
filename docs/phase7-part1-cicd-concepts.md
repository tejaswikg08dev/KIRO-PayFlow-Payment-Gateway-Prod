# Phase 7 · Part 1 — CI/CD Concepts & GitHub Actions Fundamentals

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 7 — CI/CD Pipeline |
| **Part** | 1 — CI/CD Concepts |
| **Previous** | [Phase 6 Part 3 — Docker Compose](phase6-part3-docker-compose.md) |
| **Next** | [Part 2 — Backend Pipeline](phase7-part2-backend-pipeline.md) |
| **Time** | ~1.5 hours |
| **Difficulty** | ★★☆☆☆ Beginner |
| **Prerequisites** | Git basics, understanding of build/test/deploy |

---

## Table of Contents

1. [What is CI? What is CD?](#1-what-is-ci-what-is-cd)
2. [Why CI/CD Matters](#2-why-cicd-matters)
3. [GitHub Actions Concepts](#3-github-actions-concepts)
4. [YAML Syntax Basics](#4-yaml-syntax-basics)
5. [Trigger Types](#5-trigger-types)
6. [What You Learned](#what-you-learned)
7. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. What is CI? What is CD?

### Continuous Integration (CI)

**CI = automatically build and test code on every push.**

```
Developer pushes code
        │
        ▼
┌─────────────────────┐
│  CI Pipeline Runs   │
│  1. Checkout code   │
│  2. Install deps    │
│  3. Compile/Build   │
│  4. Run tests       │
│  5. Check coverage  │
│  6. Report status   │
└─────────┬───────────┘
          │
     ┌────┴────┐
     │         │
   ✅ PASS    ❌ FAIL
   Merge OK    Fix needed
```

### Continuous Delivery (CD)

**CD = automatically deploy passing builds to production.**

```
CI passes
    │
    ▼
┌─────────────────────┐
│  CD Pipeline Runs   │
│  1. Build Docker    │
│  2. Push to registry│
│  3. Deploy to AWS   │
│  4. Health check    │
│  5. Notify team     │
└─────────────────────┘
```

### The Full Picture

```
[Code Push] → [Build] → [Test] → [Package] → [Deploy Staging] → [Deploy Prod]
              └────── CI ──────┘  └─────────── CD ───────────────────────────┘
```

---

## 2. Why CI/CD Matters

### Without CI/CD

```
Monday:    Developer A pushes untested code
Tuesday:   Developer B pushes code that breaks A's feature
Wednesday: QA finds 5 bugs manually
Thursday:  "Works on my machine" debates
Friday:    Manual deploy at 11 PM, something breaks, weekend ruined 😰
```

### With CI/CD

```
Monday:    Developer A pushes → CI runs → tests pass → auto-deployed ✅
Tuesday:   Developer B pushes → CI runs → test FAILS → fix in 10 min ✅
Wednesday: Both features deployed, QA validates on staging ✅
Thursday:  Metrics look good, promote to production ✅
Friday:    Team leaves on time 🎉
```

**Key Benefits:**

| Benefit | How |
|---------|-----|
| Catch bugs early | Tests run on every push |
| Deploy confidently | Only passing code reaches production |
| Faster releases | Automation removes manual steps |
| Consistent builds | Same environment every time (no "works on my machine") |
| Team visibility | Everyone sees build status |

---

## 3. GitHub Actions Concepts

GitHub Actions is GitHub's built-in CI/CD platform. Here's the vocabulary:

```
┌─── WORKFLOW (.github/workflows/ci.yml) ──────────────────────────┐
│                                                                    │
│  TRIGGER: on push to main                                         │
│                                                                    │
│  ┌─── JOB: build ─────────────────────────────────────────────┐  │
│  │  RUNNER: ubuntu-latest                                      │  │
│  │                                                             │  │
│  │  STEP 1: actions/checkout@v4        (check out code)       │  │
│  │  STEP 2: actions/setup-java@v4      (install Java 17)      │  │
│  │  STEP 3: run: mvn clean verify      (build + test)         │  │
│  │  STEP 4: Upload coverage artifact                           │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  ┌─── JOB: docker (needs: build) ─────────────────────────────┐  │
│  │  STEP 1: Build Docker image                                 │  │
│  │  STEP 2: Push to ECR                                        │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  ┌─── JOB: deploy (needs: docker) ────────────────────────────┐  │
│  │  STEP 1: SSH to EC2                                         │  │
│  │  STEP 2: Pull new images                                    │  │
│  │  STEP 3: docker compose up                                  │  │
│  └─────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

| Concept | Definition | PayFlow Example |
|---------|-----------|-----------------|
| **Workflow** | YAML file defining automation | `ci-backend.yml` |
| **Trigger** | Event that starts the workflow | Push to `main`, PR to `main` |
| **Job** | Set of steps on one runner | `build`, `docker`, `deploy` |
| **Step** | Single task within a job | `mvn clean verify` |
| **Runner** | VM that executes the job | `ubuntu-latest` |
| **Action** | Reusable step (from marketplace) | `actions/checkout@v4` |
| **Secret** | Encrypted variable | `AWS_ACCESS_KEY_ID` |
| **Artifact** | File passed between jobs | JAR file, coverage report |

---

## 4. YAML Syntax Basics

GitHub Actions workflows use YAML. Here's a quick reference:

```yaml
# Comments start with #

# Key-value pairs
name: CI Pipeline
version: "3.8"

# Nested objects (indent with 2 spaces)
job:
  name: Build
  runs-on: ubuntu-latest

# Lists (dash + space)
steps:
  - name: Checkout
    uses: actions/checkout@v4
  - name: Build
    run: mvn clean package

# Multi-line strings
run: |
  echo "Line 1"
  echo "Line 2"
  mvn clean verify

# Inline list
branches: [main, develop]

# Environment variables
env:
  JAVA_VERSION: "17"
  NODE_VERSION: "20"

# Conditionals
if: github.event_name == 'push'

# Expressions
timeout-minutes: ${{ secrets.TIMEOUT || 30 }}
```

---

## 5. Trigger Types

Workflows can be triggered by various events:

```yaml
on:
  # ─── Push trigger ──────────────────────────
  push:
    branches: [main, develop]       # Only these branches
    paths:
      - 'backend/**'                # Only when backend files change
      - '!backend/**/*.md'          # Except markdown files

  # ─── Pull Request trigger ──────────────────
  pull_request:
    branches: [main]
    types: [opened, synchronize]    # When PR opened or updated

  # ─── Schedule (cron) ───────────────────────
  schedule:
    - cron: '0 2 * * 1'            # Every Monday at 2 AM UTC

  # ─── Manual trigger ────────────────────────
  workflow_dispatch:
    inputs:
      environment:
        description: 'Deploy to'
        required: true
        default: 'staging'
        type: choice
        options: [staging, production]

  # ─── Other workflow completes ──────────────
  workflow_run:
    workflows: ["CI Backend"]
    types: [completed]
```

### PayFlow Trigger Strategy

| Workflow | Trigger | Path Filter |
|---------|---------|-------------|
| `ci-backend.yml` | push, PR to main | `backend/**` |
| `ci-frontend.yml` | push, PR to main | `frontend/**` |
| Deploy | Manual + after CI passes | N/A |

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | CI | Automatically build + test on every push |
| 2 | CD | Automatically deploy passing builds |
| 3 | Benefits | Catch bugs early, deploy confidently, faster releases |
| 4 | Workflow | YAML file in `.github/workflows/` |
| 5 | Job | Group of steps on one runner; jobs can depend on each other |
| 6 | Triggers | push, pull_request, schedule, workflow_dispatch |
| 7 | Path filters | Only run when relevant files change |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| Workflow doesn't trigger | File not in `.github/workflows/` | Ensure correct directory path |
| YAML parse error | Indentation wrong (tabs vs spaces) | Use 2-space indentation, no tabs |
| `permission denied` on secret | Secret name typo | Check exact name in Settings → Secrets |
| Job never runs | `needs` references non-existent job | Verify job names match |
| Workflow runs on ALL pushes | Missing `paths` filter | Add path filter to limit scope |
| Schedule doesn't fire | Cron only on default branch | Ensure workflow is on `main` branch |

---

<div align="center">

**[← Phase 6 Part 3: Docker Compose](phase6-part3-docker-compose.md)** | **[Documentation Index](../README.md)** | **[Part 2: Backend Pipeline →](phase7-part2-backend-pipeline.md)**

</div>
