# Phase 8 Part 5: Frontend Hosting

## Overview

Host the React merchant dashboard and hosted checkout on S3 with CloudFront CDN for global distribution and HTTPS.

## S3 Bucket Setup

```bash
# Create bucket for merchant dashboard
aws s3 mb s3://payflow-merchant-dashboard --region ap-south-1

# Create bucket for hosted checkout
aws s3 mb s3://payflow-hosted-checkout --region ap-south-1

# Enable static website hosting
aws s3 website s3://payflow-merchant-dashboard \
  --index-document index.html \
  --error-document index.html  # SPA fallback

# Bucket policy (allow CloudFront OAI access)
aws s3api put-bucket-policy --bucket payflow-merchant-dashboard --policy '{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "AllowCloudFrontOAI",
    "Effect": "Allow",
    "Principal": {"AWS": "arn:aws:iam::cloudfront:user/CloudFront Origin Access Identity EXXXXX"},
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::payflow-merchant-dashboard/*"
  }]
}'
```

## CloudFront Distribution

```bash
aws cloudfront create-distribution --distribution-config '{
  "CallerReference": "payflow-dashboard-dist",
  "Origins": {
    "Quantity": 1,
    "Items": [{
      "Id": "S3-payflow-dashboard",
      "DomainName": "payflow-merchant-dashboard.s3.ap-south-1.amazonaws.com",
      "S3OriginConfig": {
        "OriginAccessIdentity": "origin-access-identity/cloudfront/EXXXXX"
      }
    }]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "S3-payflow-dashboard",
    "ViewerProtocolPolicy": "redirect-to-https",
    "AllowedMethods": {"Quantity": 2, "Items": ["GET", "HEAD"]},
    "CachedMethods": {"Quantity": 2, "Items": ["GET", "HEAD"]},
    "ForwardedValues": {"QueryString": false, "Cookies": {"Forward": "none"}},
    "MinTTL": 0,
    "DefaultTTL": 86400,
    "MaxTTL": 31536000,
    "Compress": true
  },
  "CustomErrorResponses": {
    "Quantity": 1,
    "Items": [{
      "ErrorCode": 404,
      "ResponsePagePath": "/index.html",
      "ResponseCode": "200",
      "ErrorCachingMinTTL": 0
    }]
  },
  "Enabled": true,
  "DefaultRootObject": "index.html",
  "Comment": "PayFlow Merchant Dashboard"
}'
```

## SPA Routing Fix

React Router needs all paths to serve `index.html`:

```
CloudFront Custom Error Responses:
  404 → /index.html (200)
  403 → /index.html (200)

This ensures /dashboard, /transactions, etc. all load the React app.
```

## Deployment Flow

```
npm run build
     │
     ▼
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│   dist/     │────▶│   S3 Bucket  │────▶│  CloudFront  │
│  index.html │     │              │     │  (CDN Edge)  │
│  assets/    │     │  Static Host │     │  HTTPS       │
└─────────────┘     └──────────────┘     └──────────────┘
```

## Upload with Proper Cache Headers

```bash
# Upload hashed assets (long cache)
aws s3 sync dist/assets/ s3://payflow-merchant-dashboard/assets/ \
  --cache-control "public, max-age=31536000, immutable" \
  --content-encoding gzip

# Upload index.html (no cache)
aws s3 cp dist/index.html s3://payflow-merchant-dashboard/index.html \
  --cache-control "no-cache, no-store, must-revalidate" \
  --content-type "text/html"

# Invalidate CloudFront
aws cloudfront create-invalidation \
  --distribution-id E1234567890 \
  --paths "/index.html"
```

## Performance Results

| Metric | Before CDN | After CloudFront |
|--------|-----------|-----------------|
| First Byte (Mumbai) | 200ms | 20ms |
| First Byte (US) | 800ms | 50ms |
| Full Page Load | 3.2s | 1.1s |
| Bundle Size (gzipped) | N/A | ~180KB |

## Custom Domain Setup

```bash
# Request SSL certificate (ACM)
aws acm request-certificate \
  --domain-name dashboard.payflow.io \
  --validation-method DNS

# Add alternate domain to CloudFront
# Update DNS: CNAME dashboard.payflow.io → dxxxxxx.cloudfront.net
```

## Nginx Config (for hosted checkout with custom routing)

```nginx
# For hosted checkout with path-based routing
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    # SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Cache static assets
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # Security headers
    add_header X-Frame-Options DENY;
    add_header X-Content-Type-Options nosniff;
    add_header X-XSS-Protection "1; mode=block";
    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'";
}
```
