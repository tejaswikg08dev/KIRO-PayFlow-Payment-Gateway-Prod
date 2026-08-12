#!/bin/bash
# PayFlow - LocalStack Initialization Script
# Creates DynamoDB tables, SNS topics, and SQS queues

echo "Initializing LocalStack resources..."

ENDPOINT="http://localhost:4566"
REGION="us-east-1"

# ============================================
# DynamoDB Tables
# ============================================

echo "Creating DynamoDB tables..."

# Webhook Delivery Records
awslocal dynamodb create-table \
  --table-name webhook_delivery_records \
  --attribute-definitions \
    AttributeName=webhookId,AttributeType=S \
    AttributeName=deliveryTimestamp,AttributeType=N \
    AttributeName=merchantId,AttributeType=S \
  --key-schema \
    AttributeName=webhookId,KeyType=HASH \
    AttributeName=deliveryTimestamp,KeyType=RANGE \
  --global-secondary-indexes \
    '[{"IndexName":"merchant-index","KeySchema":[{"AttributeName":"merchantId","KeyType":"HASH"},{"AttributeName":"deliveryTimestamp","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"},"ProvisionedThroughput":{"ReadCapacityUnits":5,"WriteCapacityUnits":5}}]' \
  --provisioned-throughput ReadCapacityUnits=5,WriteCapacityUnits=5

# Routing Metrics
awslocal dynamodb create-table \
  --table-name routing_metrics \
  --attribute-definitions \
    AttributeName=routeId,AttributeType=S \
    AttributeName=timestamp,AttributeType=N \
  --key-schema \
    AttributeName=routeId,KeyType=HASH \
    AttributeName=timestamp,KeyType=RANGE \
  --provisioned-throughput ReadCapacityUnits=5,WriteCapacityUnits=5

# Event Logs
awslocal dynamodb create-table \
  --table-name event_logs \
  --attribute-definitions \
    AttributeName=eventId,AttributeType=S \
    AttributeName=createdAt,AttributeType=N \
    AttributeName=eventType,AttributeType=S \
  --key-schema \
    AttributeName=eventId,KeyType=HASH \
    AttributeName=createdAt,KeyType=RANGE \
  --global-secondary-indexes \
    '[{"IndexName":"event-type-index","KeySchema":[{"AttributeName":"eventType","KeyType":"HASH"},{"AttributeName":"createdAt","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"},"ProvisionedThroughput":{"ReadCapacityUnits":5,"WriteCapacityUnits":5}}]' \
  --provisioned-throughput ReadCapacityUnits=5,WriteCapacityUnits=5

echo "DynamoDB tables created successfully."

# ============================================
# SNS Topics
# ============================================

echo "Creating SNS topics..."

awslocal sns create-topic --name payment-events
awslocal sns create-topic --name settlement-events
awslocal sns create-topic --name webhook-events
awslocal sns create-topic --name notification-events
awslocal sns create-topic --name merchant-events

echo "SNS topics created successfully."

# ============================================
# SQS Queues
# ============================================

echo "Creating SQS queues..."

awslocal sqs create-queue --queue-name payment-notifications
awslocal sqs create-queue --queue-name webhook-delivery
awslocal sqs create-queue --queue-name settlement-processing
awslocal sqs create-queue --queue-name email-notifications
awslocal sqs create-queue --queue-name sms-notifications

# Dead Letter Queues
awslocal sqs create-queue --queue-name payment-notifications-dlq
awslocal sqs create-queue --queue-name webhook-delivery-dlq

echo "SQS queues created successfully."

# ============================================
# SNS -> SQS Subscriptions
# ============================================

echo "Creating SNS to SQS subscriptions..."

PAYMENT_TOPIC_ARN=$(awslocal sns list-topics --query "Topics[?contains(TopicArn, 'payment-events')].TopicArn" --output text)
WEBHOOK_QUEUE_URL=$(awslocal sqs get-queue-url --queue-name webhook-delivery --query "QueueUrl" --output text)

awslocal sns subscribe \
  --topic-arn "$PAYMENT_TOPIC_ARN" \
  --protocol sqs \
  --notification-endpoint "arn:aws:sqs:us-east-1:000000000000:webhook-delivery"

echo "LocalStack initialization complete!"
