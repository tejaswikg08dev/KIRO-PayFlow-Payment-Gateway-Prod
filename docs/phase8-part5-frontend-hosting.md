# Phase 8 · Part 5 — Frontend Hosting (S3 + CloudFront)

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 8 — AWS Deployment |
| **Part** | 5 — S3 Static Hosting + CloudFront CDN |
| **Previous** | [Part 4 — Compute & Registry](phase8-part4-compute-registry.md) |
| **Next** | [Part 6 — Load Balancer & Deployment](phase8-part6-load-balancer-deploy.md) |
| **Time** | ~1 hour |
| **Difficulty** | ★★☆☆☆ Beginner-Intermediate |
| **Prerequisites** | React build basics, AWS CLI configured |

---

## Table of Contents

1. [Create S3 Bucket](#1-create-s3-bucket)
2. [Enable Static Website Hosting](#2-enable-static-website-hosting)
3. [Build React App](#3-build-react-app)
4. [Upload to S3](#4-upload-to-s3)
5. [Create CloudFront Distribution](#5-create-cloudfront-distribution)
6. [Configure SPA Routing](#6-configure-spa-routing)
7. [Test in Browser](#7-test-in-browser)
8. [What You Learned](#what-you-learned)
9. [Common Errors & Fixes](#common-errors--fixes)

---

## 1. Create S3 Bucket

```bash
# Create bucket (bucket names are globally unique)
aws s3 mb s3://payflow-frontend-prod --region ap-south-1

# Disable block public access (needed for static hosting)
aws s3api put-public-access-block \
  --bucket payflow-frontend-prod \
  --public-access-block-configuration \
    BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false
```

### Bucket Policy (allow public read)

```bash
aws s3api put-bucket-policy --bucket payflow-frontend-prod --policy '{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PublicReadGetObject",
    "Effect": "Allow",
    "Principal": "*",
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::payflow-frontend-prod/*"
  }]
}'
```

---

## 2. Enable Static Website Hosting

```bash
aws s3 website s3://payflow-frontend-prod \
  --index-document index.html \
  --error-document index.html
```

**Why error-document = index.html?** For SPA routing — when someone visits `/dashboard`, S3 doesn't have a `/dashboard` file. Setting error document to `index.html` lets React Router handle the route.

Website endpoint format:
```
http://payflow-frontend-prod.s3-website.ap-south-1.amazonaws.com
```

---

## 3. Build React App

```bash
# Navigate to frontend directory
cd frontend

# Install dependencies
npm ci

# Build for production (with correct API URL)
VITE_API_BASE_URL=https://api.payflow.example.com npm run build

# Output is in frontend/dist/
ls dist/
# index.html
# assets/
#   index-a3f9b2c1.js
#   index-d4e5f6a7.css
#   vendor-b8c9d0e1.js
```

The build produces optimized, minified files with content hashes in filenames.

---

## 4. Upload to S3

```bash
# Sync entire dist folder to S3
aws s3 sync dist/ s3://payflow-frontend-prod --delete

# Set proper cache headers for assets (immutable — hashed filenames)
aws s3 sync dist/assets/ s3://payflow-frontend-prod/assets/ \
  --cache-control "public, max-age=31536000, immutable"

# Set no-cache for index.html (always fetch latest)
aws s3 cp dist/index.html s3://payflow-frontend-prod/index.html \
  --cache-control "public, max-age=0, must-revalidate" \
  --content-type "text/html"
```

### Verify Upload

```bash
aws s3 ls s3://payflow-frontend-prod/
# 2024-01-15 10:30:00       1234 index.html
# PRE assets/

aws s3 ls s3://payflow-frontend-prod/assets/
# 2024-01-15 10:30:00     245000 index-a3f9b2c1.js
# 2024-01-15 10:30:00      12000 index-d4e5f6a7.css
```

---

## 5. Create CloudFront Distribution

CloudFront is a CDN that caches your files at edge locations globally.

### Using AWS Console

1. **Console** → CloudFront → **"Create distribution"**
2. Settings:

| Setting | Value |
|---------|-------|
| Origin domain | `payflow-frontend-prod.s3-website.ap-south-1.amazonaws.com` |
| Origin protocol | HTTP only (S3 website endpoint) |
| Viewer protocol | Redirect HTTP to HTTPS |
| Allowed methods | GET, HEAD |
| Cache policy | CachingOptimized |
| Price class | Use all edge locations (or just Asia for cost saving) |
| Default root object | `index.html` |

3. Click **"Create distribution"** (takes 5-15 minutes to deploy)

### Using AWS CLI

```bash
aws cloudfront create-distribution \
  --origin-domain-name payflow-frontend-prod.s3-website.ap-south-1.amazonaws.com \
  --default-root-object index.html

# Note: Full CLI command is complex — Console is easier for first-time setup
```

---

## 6. Configure SPA Routing

React Router uses client-side routing. When users navigate to `/dashboard`, CloudFront needs to serve `index.html` (not a 404).

### Custom Error Responses

1. CloudFront → Distribution → **"Error pages"** tab
2. Create custom error response:

| Setting | Value |
|---------|-------|
| HTTP error code | 403 |
| Response page path | `/index.html` |
| HTTP response code | 200 |

3. Create another:

| Setting | Value |
|---------|-------|
| HTTP error code | 404 |
| Response page path | `/index.html` |
| HTTP response code | 200 |

**Result:** Any non-existent path returns `index.html` with status 200 → React Router handles the route.

---

## 7. Test in Browser

### Get CloudFront URL

```bash
aws cloudfront list-distributions \
  --query "DistributionList.Items[0].DomainName" \
  --output text
# Output: d1a2b3c4d5e6f7.cloudfront.net
```

### Verify

1. Open `https://d1a2b3c4d5e6f7.cloudfront.net` — should show login page
2. Navigate to `https://d1a2b3c4d5e6f7.cloudfront.net/dashboard` — should work (SPA routing)
3. Check Network tab — assets should have `x-cache: Hit from cloudfront`

```bash
# CLI verification
curl -I https://d1a2b3c4d5e6f7.cloudfront.net
# HTTP/2 200
# x-cache: Hit from cloudfront
# content-type: text/html
```

---

## What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | S3 bucket | Static file hosting — cheap, scalable, no servers |
| 2 | Static hosting | Set index/error documents for website behavior |
| 3 | React build | `npm run build` produces optimized dist/ folder |
| 4 | S3 sync | `aws s3 sync` uploads only changed files |
| 5 | CloudFront | CDN caches at 400+ edge locations worldwide |
| 6 | SPA routing | Custom error pages (404 → index.html) enable client routing |
| 7 | Cache strategy | Hashed assets cached forever; index.html never cached |

---

## Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `Access Denied` on S3 URL | Bucket policy not set | Add public read policy (see Step 1) |
| 403 on CloudFront | Wrong origin (used S3 REST endpoint) | Use S3 **website** endpoint as origin |
| `/dashboard` returns 404 | SPA routing not configured | Add custom error responses (403/404 → /index.html) |
| Old content after deploy | CloudFront cache not invalidated | Run `aws cloudfront create-invalidation --paths "/*"` |
| CORS errors in console | API and frontend on different domains | Configure CORS on API Gateway |
| Large JS bundle (>500KB) | No code splitting | Use React.lazy() for route-based splitting |

---

<div align="center">

**[← Part 4: Compute & Registry](phase8-part4-compute-registry.md)** | **[Documentation Index](../README.md)** | **[Part 6: Load Balancer & Deploy →](phase8-part6-load-balancer-deploy.md)**

</div>
