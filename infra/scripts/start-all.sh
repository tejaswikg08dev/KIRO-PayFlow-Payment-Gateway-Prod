#!/bin/bash
# PayFlow - Start All Services (Infrastructure + Application)
# Usage: ./start-all.sh [--no-build]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BUILD_FLAG="--build"
if [ "$1" = "--no-build" ]; then
    BUILD_FLAG=""
    echo "Skipping image builds..."
fi

echo "================================================"
echo "  PayFlow - Starting Full Stack"
echo "================================================"
echo ""

# Load environment variables if .env exists
if [ -f "$PROJECT_ROOT/infra/docker/.env" ]; then
    echo "Loading environment from .env file..."
    export $(grep -v '^#' "$PROJECT_ROOT/infra/docker/.env" | xargs)
fi

# Start all services
docker compose -f "$PROJECT_ROOT/infra/docker/docker-compose.full.yml" up -d $BUILD_FLAG

echo ""
echo "Waiting for services to initialize..."
sleep 30

# Display status
echo ""
echo "================================================"
echo "  Service URLs"
echo "================================================"
echo ""
echo "  Infrastructure:"
echo "    PostgreSQL:     localhost:5432"
echo "    Redis:          localhost:6379"
echo "    Kafka:          localhost:9092"
echo "    Kafka UI:       http://localhost:8090"
echo "    LocalStack:     http://localhost:4566"
echo ""
echo "  Platform:"
echo "    Service Registry: http://localhost:8761"
echo "    Config Server:    http://localhost:8888"
echo "    API Gateway:      http://localhost:8080"
echo ""
echo "  Business Services:"
echo "    Identity:      http://localhost:8081"
echo "    Merchant:      http://localhost:8082"
echo "    Payment:       http://localhost:8083"
echo "    Routing:       http://localhost:8084"
echo "    Settlement:    http://localhost:8085"
echo "    Webhook:       http://localhost:8086"
echo "    Notification:  http://localhost:8087"
echo "    Bank Sim:      http://localhost:9000"
echo ""
echo "  Frontend:"
echo "    Merchant Portal:  http://localhost:3000"
echo "    Hosted Checkout:  http://localhost:3001"
echo ""
echo "All services started! Run ./health-check.sh to verify."
