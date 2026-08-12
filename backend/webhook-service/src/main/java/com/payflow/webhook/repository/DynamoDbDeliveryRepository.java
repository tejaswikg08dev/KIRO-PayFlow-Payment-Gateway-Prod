package com.payflow.webhook.repository;

import com.payflow.webhook.model.WebhookDeliveryRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Repository for storing webhook delivery attempts in DynamoDB.
 */
@Slf4j
@Repository
public class DynamoDbDeliveryRepository {

    private static final String TABLE_NAME = "webhook_deliveries";
    private final DynamoDbTable<WebhookDeliveryRecord> table;

    public DynamoDbDeliveryRepository(DynamoDbEnhancedClient dynamoDbEnhancedClient) {
        this.table = dynamoDbEnhancedClient.table(TABLE_NAME,
                TableSchema.fromBean(WebhookDeliveryRecord.class));
    }

    /**
     * Save or update a delivery record.
     */
    public void save(WebhookDeliveryRecord record) {
        try {
            table.putItem(record);
            log.debug("Saved delivery record: eventId={}", record.getEventId());
        } catch (Exception e) {
            log.error("Failed to save delivery record: eventId={}, error={}",
                    record.getEventId(), e.getMessage(), e);
        }
    }

    /**
     * Find a delivery record by event ID.
     */
    public Optional<WebhookDeliveryRecord> findByEventId(String eventId) {
        try {
            Key key = Key.builder().partitionValue(eventId).build();
            WebhookDeliveryRecord record = table.getItem(key);
            return Optional.ofNullable(record);
        } catch (Exception e) {
            log.error("Failed to fetch delivery record: eventId={}, error={}",
                    eventId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Delete a delivery record by event ID.
     */
    public void deleteByEventId(String eventId) {
        try {
            Key key = Key.builder().partitionValue(eventId).build();
            table.deleteItem(key);
            log.debug("Deleted delivery record: eventId={}", eventId);
        } catch (Exception e) {
            log.error("Failed to delete delivery record: eventId={}, error={}",
                    eventId, e.getMessage(), e);
        }
    }
}
