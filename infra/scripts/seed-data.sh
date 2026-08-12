#!/bin/bash
# PayFlow - Seed Test Data
# Usage: ./seed-data.sh

set -e

BASE_URL="http://localhost:8080/api"
IDENTITY_URL="http://localhost:8081/api"

echo "================================================"
echo "  PayFlow - Seeding Test Data"
echo "================================================"
echo ""

# ============================================
# Register Admin User
# ============================================
echo "1. Registering admin user..."
ADMIN_RESPONSE=$(curl -s -X POST "$IDENTITY_URL/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@payflow.dev",
    "password": "Admin@123456",
    "firstName": "PayFlow",
    "lastName": "Admin",
    "role": "ADMIN"
  }')
echo "   Response: $ADMIN_RESPONSE"

# ============================================
# Login as Admin
# ============================================
echo ""
echo "2. Logging in as admin..."
LOGIN_RESPONSE=$(curl -s -X POST "$IDENTITY_URL/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@payflow.dev",
    "password": "Admin@123456"
  }')
ADMIN_TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "   Token obtained: ${ADMIN_TOKEN:0:20}..."

# ============================================
# Register Test Merchant User
# ============================================
echo ""
echo "3. Registering test merchant user..."
MERCHANT_USER_RESPONSE=$(curl -s -X POST "$IDENTITY_URL/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@testshop.com",
    "password": "Merchant@123456",
    "firstName": "Test",
    "lastName": "Merchant",
    "role": "MERCHANT"
  }')
echo "   Response: $MERCHANT_USER_RESPONSE"

# Login as merchant
MERCHANT_LOGIN=$(curl -s -X POST "$IDENTITY_URL/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "merchant@testshop.com",
    "password": "Merchant@123456"
  }')
MERCHANT_TOKEN=$(echo "$MERCHANT_LOGIN" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

# ============================================
# Onboard Test Merchants
# ============================================
echo ""
echo "4. Onboarding test merchants..."

# Merchant 1 - E-commerce Store
curl -s -X POST "$BASE_URL/v1/merchants" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MERCHANT_TOKEN" \
  -d '{
    "businessName": "TechStore Online",
    "businessType": "E_COMMERCE",
    "registrationNumber": "REG-001-2024",
    "country": "IN",
    "currency": "INR",
    "website": "https://techstore.example.com",
    "callbackUrl": "https://techstore.example.com/webhooks/payflow",
    "contactEmail": "payments@techstore.example.com",
    "contactPhone": "+919876543210"
  }' > /dev/null
echo "   TechStore Online - Created"

# Merchant 2 - SaaS Platform
curl -s -X POST "$BASE_URL/v1/merchants" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MERCHANT_TOKEN" \
  -d '{
    "businessName": "CloudSaaS Platform",
    "businessType": "SAAS",
    "registrationNumber": "REG-002-2024",
    "country": "US",
    "currency": "USD",
    "website": "https://cloudsaas.example.com",
    "callbackUrl": "https://cloudsaas.example.com/webhooks",
    "contactEmail": "billing@cloudsaas.example.com",
    "contactPhone": "+14155551234"
  }' > /dev/null
echo "   CloudSaaS Platform - Created"

# Merchant 3 - Food Delivery
curl -s -X POST "$BASE_URL/v1/merchants" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MERCHANT_TOKEN" \
  -d '{
    "businessName": "QuickBite Delivery",
    "businessType": "FOOD_DELIVERY",
    "registrationNumber": "REG-003-2024",
    "country": "IN",
    "currency": "INR",
    "website": "https://quickbite.example.com",
    "callbackUrl": "https://quickbite.example.com/payment-hooks",
    "contactEmail": "tech@quickbite.example.com",
    "contactPhone": "+919123456789"
  }' > /dev/null
echo "   QuickBite Delivery - Created"

echo ""
echo "================================================"
echo "  Seed Data Complete!"
echo "================================================"
echo ""
echo "  Test Accounts:"
echo "    Admin:    admin@payflow.dev / Admin@123456"
echo "    Merchant: merchant@testshop.com / Merchant@123456"
echo ""
echo "  Test Merchants:"
echo "    1. TechStore Online (E-Commerce, INR)"
echo "    2. CloudSaaS Platform (SaaS, USD)"
echo "    3. QuickBite Delivery (Food Delivery, INR)"
echo ""
