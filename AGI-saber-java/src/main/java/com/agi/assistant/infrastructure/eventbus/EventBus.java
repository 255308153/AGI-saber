package com.agi.assistant.infrastructure.eventbus;

/**
 * 事件总线发布接口（对应 Go internal/infrastructure/eventbus.Publisher）。
 *
 * 抽象出来便于：
 * <ul>
 *   <li>application 层只依赖接口，不关心 Kafka / 内存 / NoOp 实现</li>
 *   <li>测试时可注入 mock 实现，避免真实 Kafka 连接</li>
 * </ul>
 */
public interface EventBus {
    /** 发布一个事件。eventType 用作 Kafka key，payload 通常是 JSON。 */
    void publish(String eventType, String payload);
}
