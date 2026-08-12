package com.payflow.payment.kafka;

import com.payflow.common.event.PaymentEvent;
import com.payflow.payment.service.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka-based implementation of EventPublisher.
 * Active by default (non-AWS environments).
 * Publishes payment lifecycle events to Kafka topics.
 */
@Component
@Profile("!aws")
@RequiredArgsConstructor
@Slf4j
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @Override
    public void publishPaymentEvent(String topic, PaymentEvent event) {
        log.info("Publishing event to topic [{}]: eventId={}, paymentId={}", 
                topic, event.getEventId(), event.getPaymentId());

        CompletableFuture<SendResult<String, PaymentEvent>> future = 
                kafkaTemplate.send(topic, event.getPaymentId(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event to topic [{}]: eventId={}", 
                        topic, event.getEventId(), ex);
            } else {
                log.debug("Event published to topic [{}] at partition={}, offset={}", 
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
