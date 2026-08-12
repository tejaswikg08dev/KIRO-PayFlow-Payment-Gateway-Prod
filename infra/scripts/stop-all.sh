#!/bin/bash
# PayFlow - Stop All Services
# Usage: ./stop-all.sh [--clean]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "================================================"
echo "  PayFlow - Stopping All Services"
echo "================================================"
echo ""

# Stop services from both compose files
docker compose -f "$PROJECT_ROOT/infra/docker/docker-compose.full.yml" down 2>/dev/null || true
docker compose -f "$PROJECT_ROOT/infra/docker/docker-compose.yml" down 2>/dev/null || true

if [ "$1" = "--clean" ]; then
    echo ""
    echo "Cleaning up volumes and images..."
    docker compose -f "$PROJECT_ROOT/infra/docker/docker-compose.full.yml" down -v --rmi local 2>/dev/null || true
    docker compose -f "$PROJECT_ROOT/infra/docker/docker-compose.yml" down -v --rmi local 2>/dev/null || true
    echo "Volumes and local images removed."
fi

echo ""
echo "All services stopped."
