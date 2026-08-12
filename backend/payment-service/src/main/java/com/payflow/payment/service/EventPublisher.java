package com.payflow.payment.service;

import com.payflow.common.event.PaymentEvent;

/**
 * Interface for publishing payment lifecycle events.
 * Implementations can use Kafka, SQS, or any messaging system.
 */
public interface EventPublisher {

    /**
     * Publishes a payment event to the appropriate topic/queue.
     *
     * @param topic the topic name (e.g., payment.authorized, payment.captured)
     * @param event the payment event payload
     */
    void publishPaymentEvent(String topic, PaymentEvent event);
}
