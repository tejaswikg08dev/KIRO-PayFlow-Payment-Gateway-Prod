# Phase 7 Part 3: Frontend CI/CD Pipeline

## Overview

GitHub Actions workflow for the React frontend: lint, typecheck, build, deploy to S3, and invalidate CloudFront cache.

## Frontend Pipeline

```yaml
# .github/workflows/ci-frontend.yml
name: Frontend CI/CD

on:
  push:
    branches: [main]
    paths: ['frontend/merchant-dashboard/**']
  pull_request:
    branches: [main]
    paths: ['frontend/merchant-dashboard/**']

env:
  NODE_VERSION: '18'
  S3_BUCKET: payflow-merchant-dashboard
  CF_DISTRIBUTION_ID: ${{ secrets.CLOUDFRONT_DISTRIBUTION_ID }}

jobs:
  lint-and-typecheck:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend/merchant-dashboard
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: 'npm'
          cache-dependency-path: frontend/merchant-dashboard/package-lock.json

      - run: npm ci
      - run: npm run lint
      - run: npx tsc --noEmit

  build:
    runs-on: ubuntu-latest
    needs: lint-and-typecheck
    defaults:
      run:
        working-directory: frontend/merchant-dashboard
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: ${{ env.NODE_VERSION }}
          cache: 'npm'
          cache-dependency-path: frontend/merchant-dashboard/package-lock.json

      - run: npm ci
      - name: Build
        run: npm run build
        env:
          VITE_API_URL: https://api.payflow.io/v1

      - name: Upload Build Artifact
        uses: actions/upload-artifact@v4
        with:
          name: frontend-build
          path: frontend/merchant-dashboard/dist/

  deploy:
    runs-on: ubuntu-latest
    needs: build
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    steps:
      - name: Download Build Artifact
        uses: actions/download-artifact@v4
        with:
          name: frontend-build
          path: dist/

      - name: Configure AWS Credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ap-south-1

      - name: Sync to S3
        run: |
          aws s3 sync dist/ s3://${{ env.S3_BUCKET }} \
            --delete \
            --cache-control "public, max-age=31536000" \
            --exclude "index.html" \
            --exclude "*.json"

          # Upload index.html with no-cache
          aws s3 cp dist/index.html s3://${{ env.S3_BUCKET }}/index.html \
            --cache-control "no-cache, no-store, must-revalidate"

      - name: Invalidate CloudFront Cache
        run: |
          aws cloudfront create-invalidation \
            --distribution-id ${{ env.CF_DISTRIBUTION_ID }} \
            --paths "/index.html" "/*.js" "/*.css"

      - name: Verify Deployment
        run: |
          sleep 10
          HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" https://dashboard.payflow.io)
          if [ "$HTTP_STATUS" != "200" ]; then
            echo "❌ Frontend deployment verification failed"
            exit 1
          fi
          echo "✅ Frontend deployed successfully"
```

## S3 Caching Strategy

| File Type | Cache-Control | Rationale |
|-----------|--------------|-----------|
| `index.html` | `no-cache` | Always fetch latest (entry point) |
| `*.js` (hashed) | `max-age=31536000` | Immutable (hash in filename) |
| `*.css` (hashed) | `max-age=31536000` | Immutable (hash in filename) |
| `assets/*` | `max-age=86400` | Images, fonts (1 day) |
| `*.json` | `no-cache` | Config files, manifests |

## Build Optimization

```json
// vite.config.ts build optimization
{
  "build": {
    "rollupOptions": {
      "output": {
        "manualChunks": {
          "vendor": ["react", "react-dom", "react-router-dom"],
          "charts": ["recharts"],
          "query": ["@tanstack/react-query"]
        }
      }
    },
    "sourcemap": true
  }
}
```

## Environment Variables

```bash
# .env.production (committed - non-secret)
VITE_API_URL=https://api.payflow.io/v1
VITE_APP_NAME=PayFlow Dashboard
VITE_CHECKOUT_URL=https://checkout.payflow.io

# .env.local (not committed - local dev)
VITE_API_URL=http://localhost:8080/api/v1
```

## Pipeline Duration

| Stage | Duration |
|-------|----------|
| Lint + Typecheck | ~30s |
| Build | ~45s |
| S3 Sync | ~15s |
| CloudFront Invalidation | ~30s |
| **Total** | **~2 minutes** |

## Hosted Checkout Pipeline

```yaml
# Same pattern for hosted-checkout app
deploy-checkout:
  steps:
    - run: npm ci && npm run build
      working-directory: frontend/hosted-checkout
      env:
        VITE_API_URL: https://api.payflow.io/v1
    - run: aws s3 sync dist/ s3://payflow-checkout --delete
    - run: aws cloudfront create-invalidation --distribution-id $CF_CHECKOUT_ID --paths "/*"
```
