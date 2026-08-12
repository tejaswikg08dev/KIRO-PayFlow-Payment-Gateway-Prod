package com.payflow.payment.sqs;

import com.payflow.common.event.PaymentEvent;
import com.payflow.payment.service.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * AWS SQS-based implementation of EventPublisher.
 * Active only when running with the 'aws' profile.
 * Stub implementation — to be completed with AWS SDK SQS integration.
 */
@Component
@Profile("aws")
@Slf4j
public class SqsEventPublisher implements EventPublisher {

    @Override
    public void publishPaymentEvent(String topic, PaymentEvent event) {
        log.info("SQS: Publishing event to queue [{}]: eventId={}, paymentId={}", 
                topic, event.getEventId(), event.getPaymentId());

        // TODO: Implement AWS SQS publishing
        // SqsClient sqsClient = ...
        // String queueUrl = resolveQueueUrl(topic);
        // SendMessageRequest sendRequest = SendMessageRequest.builder()
        //         .queueUrl(queueUrl)
        //         .messageBody(objectMapper.writeValueAsString(event))
        //         .messageGroupId(event.getMerchantId())
        //         .messageDeduplicationId(event.getEventId())
        //         .build();
        // sqsClient.sendMessage(sendRequest);

        log.warn("SQS EventPublisher is a stub — event not actually sent");
    }
}
