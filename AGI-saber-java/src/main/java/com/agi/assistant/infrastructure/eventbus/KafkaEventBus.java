package com.agi.assistant.infrastructure.eventbus;

import com.agi.assistant.infrastructure.platform.KafkaConnector;
import org.springframework.stereotype.Component;

/**
 * EventBus 的默认实现，桥接到 {@link KafkaConnector}。
 *
 * <p>application 层只依赖 {@link EventBus}，不再直接依赖 KafkaConnector 或
 * InfrastructureService 门面。</p>
 */
@Component
public class KafkaEventBus implements EventBus {

    private final KafkaConnector kafka;

    public KafkaEventBus(KafkaConnector kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String eventType, String payload) {
        kafka.publish(eventType, payload);
    }
}
