#!/bin/bash
# PayFlow - Health Check Script
# Usage: ./health-check.sh

set -e

echo "================================================"
echo "  PayFlow - Service Health Check"
echo "================================================"
echo ""

SERVICES=(
    "Service Registry|http://localhost:8761/actuator/health"
    "Config Server|http://localhost:8888/actuator/health"
    "API Gateway|http://localhost:8080/actuator/health"
    "Identity Service|http://localhost:8081/actuator/health"
    "Merchant Service|http://localhost:8082/actuator/health"
    "Payment Service|http://localhost:8083/actuator/health"
    "Routing Service|http://localhost:8084/actuator/health"
    "Settlement Service|http://localhost:8085/actuator/health"
    "Webhook Service|http://localhost:8086/actuator/health"
    "Notification Service|http://localhost:8087/actuator/health"
    "Bank Simulator|http://localhost:9000/actuator/health"
)

HEALTHY=0
UNHEALTHY=0
TOTAL=${#SERVICES[@]}

printf "%-22s %-12s %s\n" "SERVICE" "STATUS" "DETAILS"
printf "%-22s %-12s %s\n" "-------" "------" "-------"

for SERVICE in "${SERVICES[@]}"; do
    IFS='|' read -r NAME URL <<< "$SERVICE"

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 5 "$URL" 2>/dev/null || echo "000")

    if [ "$RESPONSE" = "200" ]; then
        STATUS="\033[0;32mUP\033[0m"
        DETAIL="HTTP $RESPONSE"
        HEALTHY=$((HEALTHY + 1))
    elif [ "$RESPONSE" = "000" ]; then
        STATUS="\033[0;31mDOWN\033[0m"
        DETAIL="Connection refused"
        UNHEALTHY=$((UNHEALTHY + 1))
    else
        STATUS="\033[0;33mDEGRADED\033[0m"
        DETAIL="HTTP $RESPONSE"
        UNHEALTHY=$((UNHEALTHY + 1))
    fi

    printf "%-22s %-22b %s\n" "$NAME" "$STATUS" "$DETAIL"
done

echo ""
echo "================================================"
echo "  Summary: $HEALTHY/$TOTAL healthy, $UNHEALTHY/$TOTAL unhealthy"
echo "================================================"

# Check infrastructure
echo ""
echo "Infrastructure:"
printf "  %-15s %s\n" "PostgreSQL:" "$(docker inspect --format='{{.State.Health.Status}}' payflow-postgres 2>/dev/null || echo 'not running')"
printf "  %-15s %s\n" "Redis:" "$(docker inspect --format='{{.State.Health.Status}}' payflow-redis 2>/dev/null || echo 'not running')"
printf "  %-15s %s\n" "Kafka:" "$(docker inspect --format='{{.State.Health.Status}}' payflow-kafka 2>/dev/null || echo 'not running')"
printf "  %-15s %s\n" "LocalStack:" "$(docker inspect --format='{{.State.Health.Status}}' payflow-localstack 2>/dev/null || echo 'not running')"

# Exit with error if any service is unhealthy
if [ $UNHEALTHY -gt 0 ]; then
    exit 1
fi
