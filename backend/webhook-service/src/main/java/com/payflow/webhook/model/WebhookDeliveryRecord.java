package com.payflow.webhook.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

/**
 * DynamoDB entity representing a webhook delivery attempt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class WebhookDeliveryRecord {

    private String eventId;
    private String merchantId;
    private String webhookUrl;
    private int attempts;
    private int lastStatusCode;
    private Instant lastAttemptAt;
    private Instant nextRetryAt;
    private boolean completed;

    @DynamoDbPartitionKey
    public String getEventId() {
        return eventId;
    }
}
