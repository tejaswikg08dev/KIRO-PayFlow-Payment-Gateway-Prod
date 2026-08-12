package com.payflow.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Kafka event payload for webhook delivery.
 * Consumed by webhook-service to deliver notifications to merchant endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEvent {

    private String eventId;
    private String eventType;
    private String merchantId;
    private String webhookUrl;
    private String payload;
    private String secret;
    private Instant timestamp;
}
