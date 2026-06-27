package com.agi.assistant.infrastructure.platform;

import com.agi.assistant.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Kafka 连接器（对应 Go infrastructure/platform/kafka）。
 */
@Component
public class KafkaConnector {

    private static final Logger log = LoggerFactory.getLogger(KafkaConnector.class);

    private final AppConfig cfg;
    private volatile KafkaProducer<String, String> producer;
    private volatile String status = "disconnected";

    public KafkaConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        try {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.getKafka().getBrokers());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    "org.apache.kafka.common.serialization.StringSerializer");
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    "org.apache.kafka.common.serialization.StringSerializer");
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");
            props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");
            KafkaProducer<String, String> p = new KafkaProducer<>(props);
            p.partitionsFor(cfg.getKafka().getTopic());
            producer = p;
            status = "connected";
            log.info("Kafka 已连接: {}", cfg.getKafka().getBrokers());
        } catch (Exception e) {
            log.warn("Kafka 连接失败: {} (事件将输出到日志)", e.getMessage());
            status = "disconnected";
            if (producer != null) {
                try { producer.close(); } catch (Exception ignored) {}
                producer = null;
            }
        }
    }

    public KafkaProducer<String, String> producer() { return producer; }
    public boolean available() { return "connected".equals(status) && producer != null; }
    public String status() { return status; }

    /** 发送事件；未连接时降级到日志。 */
    public void publish(String eventType, String payload) {
        if (available()) {
            try {
                producer.send(new ProducerRecord<>(cfg.getKafka().getTopic(), eventType, payload));
            } catch (Exception e) {
                log.warn("Kafka 写入失败: {}", e.getMessage());
            }
        } else {
            log.info("[Kafka-fallback] {}: {}", eventType, payload);
        }
    }

    @PreDestroy
    public void close() {
        if (producer != null) {
            try { producer.close(); } catch (Exception ignored) {}
        }
    }
}
