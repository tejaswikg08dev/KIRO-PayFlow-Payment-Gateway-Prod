package com.payflow.webhook.consumer;

import com.payflow.common.event.PaymentEvent;
import com.payflow.common.event.SettlementEvent;
import com.payflow.webhook.service.WebhookDeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer that listens to payment and settlement events
 * and triggers webhook delivery to merchant endpoints.
 */
@Slf4j
@Component
public class KafkaWebhookConsumer {

    private final WebhookDeliveryService webhookDeliveryService;

    public KafkaWebhookConsumer(WebhookDeliveryService webhookDeliveryService) {
        this.webhookDeliveryService = webhookDeliveryService;
    }

    @KafkaListener(
            topics = {"payment.authorized", "payment.captured", "payment.failed", "payment.refunded"},
            groupId = "webhook-consumers",
            containerFactory = "paymentEventKafkaListenerContainerFactory"
    )
    public void consumePaymentEvent(PaymentEvent event) {
        log.info("Received payment event: type={}, paymentId={}, merchantId={}",
                event.getEventType(), event.getPaymentId(), event.getMerchantId());

        try {
            webhookDeliveryService.deliverPaymentWebhook(event);
        } catch (Exception e) {
            log.error("Failed to deliver payment webhook: eventId={}, error={}",
                    event.getEventId(), e.getMessage(), e);
        }
    }

    @KafkaListener(
            topics = "settlement.completed",
            groupId = "webhook-consumers",
            containerFactory = "settlementEventKafkaListenerContainerFactory"
    )
    public void consumeSettlementEvent(SettlementEvent event) {
        log.info("Received settlement event: type={}, batchId={}, merchantId={}",
                event.getEventType(), event.getBatchId(), event.getMerchantId());

        try {
            webhookDeliveryService.deliverSettlementWebhook(event);
        } catch (Exception e) {
            log.error("Failed to deliver settlement webhook: eventId={}, error={}",
                    event.getEventId(), e.getMessage(), e);
        }
    }
}
