# Phase 7 · Part 3 — Frontend CI/CD Pipeline

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 7 — CI/CD Pipeline |
| **Part** | 3 — Frontend Pipeline (S3 + CloudFront) |
| **Previous** | [Part 2 — Backend Pipeline](phase7-part2-backend-pipeline.md) |
| **Next** | [Phase 8 Part 1 — AWS Account Setup](phase8-part1-aws-account-setup.md) |
| **Time** | ~1.5 hours |
| **Difficulty** | ★★☆☆☆ Beginner-Intermediate |
| **Prerequisites** | GitHub Actions basics, React build process, AWS S3/CloudFront |

---

## Table of Contents

1. [Pipeline Overview](#1-pipeline-overview)
2. [Trigger Configuration](#2-trigger-configuration)
3. [Build Job](#3-build-job)
4. [Deploy Job](#4-deploy-job)
5. [Environment Variables](#5-environment-variables)
6. [What You Learned](#what-you-learned)
7. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Pipeline Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                    ci-frontend.yml Pipeline                        │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  TRIGGER: push to main (frontend/** changed)                      │
│                                                                    │
│  ┌──────────────────────┐      ┌──────────────────────────┐      │
│  │       BUILD          │ ───► │        DEPLOY            │      │
│  │                      │      │                          │      │
│  │  Checkout            │      │  AWS credentials         │      │
│  │  Node 20 setup       │      │  S3 sync (upload)        │      │
│  │  npm ci              │      │  CloudFront invalidation │      │
│  │  npm run lint        │      │                          │      │
│  │  npm run build       │      │                          │      │
│  └──────────────────────┘      └──────────────────────────┘      │
│                                                                    │
└──────────────────────────────────────────────────────────────────┘
```

**Why S3 + CloudFront?**
- S3 hosts static files (HTML, CSS, JS) — cheap and scalable
- CloudFront is a CDN — serves files from edge locations worldwide
- Together: fast global delivery, HTTPS, custom domain support

---

## 2. Trigger Configuration

```yaml
# .github/workflows/ci-frontend.yml
name: CI Frontend

on:
  push:
    branches: [main]
    paths:
      - 'frontend/**'              # Only frontend changes
      - '!frontend/**/*.md'        # Ignore docs
  pull_request:
    branches: [main]
    paths:
      - 'frontend/**'

env:
  NODE_VERSION: '20'
  S3_BUCKET: payflow-frontend-prod
  CLOUDFRONT_DISTRIBUTION_ID: E1A2B3C4D5E6F7
```

---

## 3. Build Job

```yaml
jobs:
  build:
    name: Build & Lint
    runs-on: ubuntu-latest

    steps:
      # ─── Step 1: Checkout ──────────────────────────
      - name: Checkout code
        uses: actions/checkout@v4

      # ─── Step 2: Setup Node.js ─────────────────────
      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: 'npm'                    # Cache node_modules
          cache-dependency-path: frontend/package-lock.json

      # ─── Step 3: Install dependencies ──────────────
      - name: Install dependencies
        working-directory: ./frontend
        run: npm ci
        # npm ci = clean install (uses package-lock.json exactly)
        # Faster and more reliable than npm install in CI

      # ─── Step 4: Run linter ────────────────────────
      - name: Lint
        working-directory: ./frontend
        run: npm run lint
        # Catches code style issues, unused imports, etc.

      # ─── Step 5: Type check (if using TypeScript) ──
      - name: Type check
        working-directory: ./frontend
        run: npx tsc --noEmit
        # Ensures no TypeScript errors without emitting files

      # ─── Step 6: Build for production ──────────────
      - name: Build
        working-directory: ./frontend
        env:
          VITE_API_BASE_URL: https://api.payflow.example.com
          VITE_APP_ENV: production
        run: npm run build
        # Creates optimized bundle in frontend/dist/

      # ─── Step 7: Upload build artifact ─────────────
      - name: Upload build artifact
        uses: actions/upload-artifact@v4
        with:
          name: frontend-build
          path: frontend/dist/
          retention-days: 7
```

**Why `npm ci` instead of `npm install`?**

| `npm install` | `npm ci` |
|---------------|----------|
| May update package-lock.json | Uses lock file exactly |
| Slower (resolves versions) | Faster (skips resolution) |
| Good for development | Good for CI (deterministic) |

---

## 4. Deploy Job

```yaml
  deploy:
    name: Deploy to S3 + CloudFront
    runs-on: ubuntu-latest
    needs: build
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'

    steps:
      # ─── Step 1: Download build artifact ───────────
      - name: Download build artifact
        uses: actions/download-artifact@v4
        with:
          name: frontend-build
          path: ./dist

      # ─── Step 2: Configure AWS credentials ─────────
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ap-south-1

      # ─── Step 3: Sync to S3 ────────────────────────
      - name: Deploy to S3
        run: |
          # Sync all files to S3 bucket
          aws s3 sync ./dist s3://${{ env.S3_BUCKET }} \
            --delete \
            --cache-control "public, max-age=31536000, immutable"
          
          # HTML files should NOT be cached (they reference hashed assets)
          aws s3 cp ./dist/index.html s3://${{ env.S3_BUCKET }}/index.html \
            --cache-control "public, max-age=0, must-revalidate"
          
          echo "✅ Files synced to S3"

      # ─── Step 4: Invalidate CloudFront cache ───────
      - name: Invalidate CloudFront
        run: |
          aws cloudfront create-invalidation \
            --distribution-id ${{ env.CLOUDFRONT_DISTRIBUTION_ID }} \
            --paths "/*"
          
          echo "✅ CloudFront cache invalidated"

      # ─── Step 5: Verify deployment ─────────────────
      - name: Verify deployment
        run: |
          sleep 15  # Wait for CloudFront propagation
          HTTP_STATUS=$(curl -o /dev/null -s -w "%{http_code}" https://dashboard.payflow.example.com)
          if [ "$HTTP_STATUS" -ne 200 ]; then
            echo "❌ Deployment verification failed (HTTP $HTTP_STATUS)"
            exit 1
          fi
          echo "✅ Frontend is live!"
```

### Cache Strategy Explained

```
┌─────────────────────────────────────────────────────────────────┐
│  File Type         │  Cache Header                │  Why         │
├────────────────────┼──────────────────────────────┼──────────────┤
│  index.html        │  max-age=0, must-revalidate  │  Always fresh│
│  assets/app.a3f9.js│  max-age=31536000, immutable │  Hash in name│
│  assets/style.b2c1.css│ max-age=31536000, immutable│ Hash in name│
└─────────────────────────────────────────────────────────────────┘
```

- `index.html` always fetched fresh → picks up new asset references
- JS/CSS files have content hashes in filenames → safe to cache forever
- New deploy = new hashed filenames → old cache doesn't matter

---

## 5. Environment Variables

### Build-Time Variables (Vite)

```bash
# In Vite, env vars must be prefixed with VITE_
VITE_API_BASE_URL=https://api.payflow.example.com
VITE_APP_ENV=production
VITE_STRIPE_PUBLIC_KEY=pk_live_xxxxx
```

These are embedded in the JS bundle at build time:

```tsx
// Access in code
const apiUrl = import.meta.env.VITE_API_BASE_URL;
```

### Per-Environment Config

| Variable | Development | Production |
|----------|-------------|------------|
| `VITE_API_BASE_URL` | `http://localhost:8080` | `https://api.payflow.example.com` |
| `VITE_APP_ENV` | `development` | `production` |
| `VITE_ENABLE_MOCKS` | `true` | `false` |

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Frontend CI | Lint + type check + build catch issues before deploy |
| 2 | `npm ci` | Deterministic installs from lock file — fast and reliable |
| 3 | S3 hosting | Static files served directly from object storage |
| 4 | CloudFront | CDN for global, low-latency delivery |
| 5 | Cache headers | Hash-based filenames → aggressive caching for assets |
| 6 | Invalidation | `/*` invalidation forces CloudFront to fetch fresh files |
| 7 | Build-time env | `VITE_*` variables baked into the bundle at build time |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `npm ci` fails with lock file mismatch | package-lock.json out of date | Run `npm install` locally, commit updated lock file |
| Build fails: `VITE_API_BASE_URL is undefined` | Env var not set in CI | Add to `env:` in build step |
| S3 sync: `Access Denied` | IAM policy missing S3 permissions | Add `s3:PutObject`, `s3:DeleteObject` to IAM role |
| CloudFront still shows old content | Invalidation not complete | Wait 1-2 minutes or check invalidation status in AWS console |
| SPA routing returns 404 | CloudFront doesn't handle client-side routes | Configure custom error response: 404 → /index.html with 200 |
| Large bundle size (>1MB) | No code splitting | Use React.lazy() and dynamic imports |

---

<div align="center">

**[← Part 2: Backend Pipeline](phase7-part2-backend-pipeline.md)** | **[Documentation Index](../README.md)** | **[Phase 8 Part 1: AWS Account Setup →](phase8-part1-aws-account-setup.md)**

</div>
