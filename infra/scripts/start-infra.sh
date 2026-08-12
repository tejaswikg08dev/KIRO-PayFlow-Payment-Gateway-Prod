#!/bin/bash
# PayFlow - Start Local Infrastructure Only
# Usage: ./start-infra.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "================================================"
echo "  PayFlow - Starting Local Infrastructure"
echo "================================================"
echo ""

# Start infrastructure services
docker compose -f "$PROJECT_ROOT/infra/docker/docker-compose.yml" up -d

echo ""
echo "Waiting for services to be healthy..."
sleep 10

# Check health status
echo ""
echo "Service Status:"
echo "  PostgreSQL: $(docker inspect --format='{{.State.Health.Status}}' payflow-postgres 2>/dev/null || echo 'not running')"
echo "  Redis:      $(docker inspect --format='{{.State.Health.Status}}' payflow-redis 2>/dev/null || echo 'not running')"
echo "  Zookeeper:  $(docker inspect --format='{{.State.Health.Status}}' payflow-zookeeper 2>/dev/null || echo 'not running')"
echo "  Kafka:      $(docker inspect --format='{{.State.Health.Status}}' payflow-kafka 2>/dev/null || echo 'not running')"
echo "  LocalStack: $(docker inspect --format='{{.State.Health.Status}}' payflow-localstack 2>/dev/null || echo 'not running')"
echo ""
echo "Infrastructure URLs:"
echo "  PostgreSQL:  localhost:5432"
echo "  Redis:       localhost:6379"
echo "  Kafka:       localhost:9092"
echo "  Kafka UI:    http://localhost:8090"
echo "  LocalStack:  http://localhost:4566"
echo ""
echo "Infrastructure started successfully!"
