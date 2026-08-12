package com.payflow.notification.service;

import com.payflow.common.event.NotificationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

/**
 * Service that sends SMS messages via AWS SNS.
 */
@Slf4j
@Service
public class SmsService {

    private final SnsClient snsClient;

    public SmsService(SnsClient snsClient) {
        this.snsClient = snsClient;
    }

    /**
     * Send an SMS notification to the recipient phone number.
     */
    public void sendSms(NotificationEvent event) {
        String message = buildSmsMessage(event);

        try {
            PublishRequest request = PublishRequest.builder()
                    .phoneNumber(event.getRecipient())
                    .message(message)
                    .build();

            PublishResponse response = snsClient.publish(request);
            log.info("SMS sent via SNS: messageId={}, recipient={}",
                    response.messageId(), event.getRecipient());

        } catch (SnsException e) {
            log.error("AWS SNS error: statusCode={}, message={}",
                    e.statusCode(), e.getMessage());
            throw new RuntimeException("Failed to send SMS via SNS", e);
        }
    }

    private String buildSmsMessage(NotificationEvent event) {
        String templateName = event.getTemplateName();
        var data = event.getTemplateData();

        return switch (templateName) {
            case "payment-success" -> String.format(
                    "PayFlow: Payment of %s %s received successfully. Order: %s",
                    data.getOrDefault("currency", "INR"),
                    data.getOrDefault("amount", "0"),
                    data.getOrDefault("orderId", "N/A"));
            case "payment-failed" -> String.format(
                    "PayFlow: Payment of %s %s failed. Reason: %s",
                    data.getOrDefault("currency", "INR"),
                    data.getOrDefault("amount", "0"),
                    data.getOrDefault("reason", "Unknown"));
            case "refund-processed" -> String.format(
                    "PayFlow: Refund of %s %s processed. Will credit in 5-7 business days.",
                    data.getOrDefault("currency", "INR"),
                    data.getOrDefault("amount", "0"));
            case "settlement-completed" -> String.format(
                    "PayFlow: Settlement of %s %s completed. Payout initiated.",
                    data.getOrDefault("currency", "INR"),
                    data.getOrDefault("netAmount", "0"));
            default -> "PayFlow: You have a new notification. Check your dashboard for details.";
        };
    }
}
