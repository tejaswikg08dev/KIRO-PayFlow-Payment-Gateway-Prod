package com.payflow.webhook.consumer;

import com.payflow.webhook.service.WebhookDeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * AWS SQS-based webhook consumer — stub implementation for the AWS profile.
 * In AWS deployments, this replaces Kafka consumption with SQS polling.
 */
@Slf4j
@Component
@Profile("aws")
public class SqsWebhookConsumer {

    private final WebhookDeliveryService webhookDeliveryService;

    public SqsWebhookConsumer(WebhookDeliveryService webhookDeliveryService) {
        this.webhookDeliveryService = webhookDeliveryService;
    }

    /**
     * Stub: Poll SQS queue for webhook events.
     * In a real implementation, this would use @SqsListener or ReceiveMessageRequest.
     */
    public void pollSqsMessages() {
        log.info("SQS webhook consumer polling (stub) — no messages processed");
        // TODO: Implement SQS integration with AWS SDK v2
        // SqsClient sqsClient = ...
        // ReceiveMessageRequest request = ReceiveMessageRequest.builder()
        //     .queueUrl(queueUrl)
        //     .maxNumberOfMessages(10)
        //     .waitTimeSeconds(20)
        //     .build();
        // List<Message> messages = sqsClient.receiveMessage(request).messages();
        // for (Message msg : messages) {
        //     webhookDeliveryService.deliverFromSqsMessage(msg.body());
        //     sqsClient.deleteMessage(...);
        // }
    }
}
