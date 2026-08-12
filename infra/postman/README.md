# PayFlow - Postman Collections

API testing collections for the PayFlow Payment Gateway.

## Collections

| Collection | Description |
|-----------|-------------|
| PayFlow-Identity-Service | Authentication & user management (Register, Login, Refresh, Profile) |
| PayFlow-Merchant-Service | Merchant onboarding, API keys, webhook management |
| PayFlow-Payment-Service | Payment processing (Orders, Authorize, Capture, Refund, Void) |
| PayFlow-Settlement-Service | Settlement operations (Trigger, List, Get) |
| PayFlow-Admin-APIs | Admin operations (Merchant approval, User management, Dashboard) |
| PayFlow-Full-Flow | End-to-end flow: Register → Onboard → Pay → Settle |

## Setup

### 1. Import Collections

1. Open Postman
2. Click **Import** button
3. Select all `.json` files from this directory
4. Collections will appear in your workspace

### 2. Import Environment

1. Click the **Environments** tab in Postman
2. Click **Import**
3. Select the appropriate environment file from `environments/`:
   - **PayFlow-Local** - When running services locally (IDE/terminal)
   - **PayFlow-Docker** - When running via docker-compose
   - **PayFlow-Production** - For production testing

### 3. Select Environment

Click the environment dropdown in the top-right corner and select your environment.

## Usage

### Auto-Authentication

All collections include **pre-request scripts** that automatically:
- Login and obtain JWT tokens
- Refresh expired tokens
- Set collection variables for subsequent requests

No manual token management needed.

### Running Individual Requests

1. Select a collection
2. Navigate to the desired request
3. Click **Send**
4. Tests will run automatically and set variables for dependent requests

### Running Full E2E Flow

1. Select the **PayFlow-Full-Flow** collection
2. Click **Run Collection** (Runner)
3. Select the environment
4. Click **Run PayFlow - Full End-to-End Flow**
5. All 9 steps will execute in sequence

### Running with Newman (CLI)

```bash
# Install Newman
npm install -g newman

# Run a single collection
newman run PayFlow-Identity-Service.postman_collection.json \
  -e environments/PayFlow-Local.postman_environment.json

# Run E2E flow
newman run PayFlow-Full-Flow.postman_collection.json \
  -e environments/PayFlow-Local.postman_environment.json \
  --reporters cli,htmlextra

# Run all collections
for file in PayFlow-*.postman_collection.json; do
  newman run "$file" -e environments/PayFlow-Local.postman_environment.json
done
```

## Test Data

The seed-data script (`infra/scripts/seed-data.sh`) creates:

| Account | Email | Password |
|---------|-------|----------|
| Admin | admin@payflow.dev | Admin@123456 |
| Merchant | merchant@testshop.com | Merchant@123456 |

### Test Card Numbers

| Card Number | Result |
|-------------|--------|
| 4111111111111111 | Success |
| 4000000000000002 | Decline |
| 4000000000009995 | Insufficient Funds |
| 4000000000000069 | Expired Card |

## Variables

Collections use these variables (auto-populated by tests):

- `accessToken` - JWT access token
- `refreshToken` - JWT refresh token
- `merchantId` - Created merchant ID
- `apiKey` - Generated API key
- `orderId` - Created order ID
- `paymentId` - Payment transaction ID
- `settlementId` - Settlement batch ID

## Troubleshooting

1. **401 Unauthorized**: Clear collection variables and re-run login
2. **Connection Refused**: Ensure services are running (`./health-check.sh`)
3. **404 Not Found**: Check the environment `baseUrl` is correct
4. **Variables not set**: Run requests in order (tests set variables for next steps)
