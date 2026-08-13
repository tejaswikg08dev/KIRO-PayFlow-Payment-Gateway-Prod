package com.payflow.settlement.kafka;

import com.payflow.common.event.SettlementEvent;
import com.payflow.common.util.IdGenerator;
import com.payflow.settlement.model.SettlementBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publishes settlement.completed events to Kafka for downstream consumers
 * (webhook-service, notification-service).
 */
@Slf4j
@Component
public class SettlementEventPublisher {

    private static final String TOPIC_SETTLEMENT_COMPLETED = "settlement.completed";

    private final KafkaTemplate<String, SettlementEvent> kafkaTemplate;

    public SettlementEventPublisher(KafkaTemplate<String, SettlementEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publish a settlement completed event for the given batch.
     */
    public void publishSettlementCompleted(SettlementBatch batch) {
        SettlementEvent event = SettlementEvent.builder()
                .eventId(IdGenerator.generateEventId())
                .eventType(TOPIC_SETTLEMENT_COMPLETED)
                .batchId(batch.getId())
                .grossAmount(batch.getTotalGross())
                .netAmount(batch.getTotalNet())
                .mdrAmount(batch.getTotalMdr())
                .gstAmount(batch.getTotalGst())
                .settlementDate(batch.getSettlementDate())
                .status(batch.getStatus().name())
                .timestamp(Instant.now())
                .build();

        kafkaTemplate.send(TOPIC_SETTLEMENT_COMPLETED, batch.getId(), event);
        log.info("Published settlement.completed event: batchId={}, net={}",
                batch.getId(), batch.getTotalNet());
    }
}
