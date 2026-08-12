package com.payflow.webhook.config;

import com.payflow.common.event.PaymentEvent;
import com.payflow.common.event.SettlementEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer configuration with separate container factories for each event type.
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    private Map<String, Object> baseConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }

    @Bean
    public ConsumerFactory<String, PaymentEvent> paymentEventConsumerFactory() {
        Map<String, Object> props = baseConsumerProps();
        JsonDeserializer<PaymentEvent> deserializer = new JsonDeserializer<>(PaymentEvent.class);
        deserializer.addTrustedPackages("com.payflow.common.event");
        deserializer.setUseTypeMapperForKey(false);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> paymentEventKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentEventConsumerFactory());
        factory.setConcurrency(3);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, SettlementEvent> settlementEventConsumerFactory() {
        Map<String, Object> props = baseConsumerProps();
        JsonDeserializer<SettlementEvent> deserializer = new JsonDeserializer<>(SettlementEvent.class);
        deserializer.addTrustedPackages("com.payflow.common.event");
        deserializer.setUseTypeMapperForKey(false);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SettlementEvent> settlementEventKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, SettlementEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(settlementEventConsumerFactory());
        factory.setConcurrency(2);
        return factory;
    }
}
