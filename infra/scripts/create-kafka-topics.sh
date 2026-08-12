#!/bin/bash
# PayFlow - Create Kafka Topics
# Usage: ./create-kafka-topics.sh

set -e

KAFKA_CONTAINER="payflow-kafka"
BOOTSTRAP_SERVER="localhost:9092"

echo "================================================"
echo "  PayFlow - Creating Kafka Topics"
echo "================================================"
echo ""

# Define topics: name|partitions|replication-factor
TOPICS=(
    "payment.created|3|1"
    "payment.authorized|3|1"
    "payment.captured|3|1"
    "payment.refunded|3|1"
    "payment.voided|3|1"
    "payment.failed|3|1"
    "settlement.initiated|3|1"
    "settlement.completed|3|1"
    "settlement.failed|3|1"
    "webhook.delivery|6|1"
    "webhook.retry|3|1"
    "notification.email|3|1"
    "notification.sms|3|1"
    "notification.push|3|1"
    "merchant.onboarded|3|1"
    "merchant.updated|3|1"
    "routing.decision|3|1"
    "audit.events|6|1"
)

for TOPIC in "${TOPICS[@]}"; do
    IFS='|' read -r NAME PARTITIONS REPLICATION <<< "$TOPIC"

    echo "Creating topic: $NAME (partitions=$PARTITIONS, replication=$REPLICATION)"

    docker exec "$KAFKA_CONTAINER" kafka-topics \
        --create \
        --if-not-exists \
        --bootstrap-server "$BOOTSTRAP_SERVER" \
        --topic "$NAME" \
        --partitions "$PARTITIONS" \
        --replication-factor "$REPLICATION" \
        2>/dev/null || echo "  Warning: Topic $NAME may already exist"
done

echo ""
echo "All topics created. Listing topics:"
echo ""

docker exec "$KAFKA_CONTAINER" kafka-topics \
    --list \
    --bootstrap-server "$BOOTSTRAP_SERVER"

echo ""
echo "Kafka topics setup complete!"
