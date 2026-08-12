package com.payflow.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Kafka event payload for email/SMS notifications.
 * Consumed by notification-service to send communications to customers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    private String eventId;
    private String type;           // EMAIL or SMS
    private String recipient;      // email address or phone number
    private String templateName;   // e.g., "payment-success", "refund-processed"
    private Map<String, String> templateData;
    private Instant timestamp;
}
