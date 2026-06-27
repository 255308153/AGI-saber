package com.agi.assistant.infrastructure.platform;

import com.agi.assistant.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Neo4j 连接器（对应 Go infrastructure/platform/neo4j）。
 *
 * <p>当前 Java 实现中 Neo4j Driver 由 {@code service.graph.Neo4jStore} / {@code KGStore}
 * 内部管理。本 Connector 只对外暴露状态，便于 {@code /api/status} 端点统一汇报。
 * 后续可把 Neo4jStore 的 Driver 创建迁过来。</p>
 */
@Component
public class Neo4jConnector {

    private static final Logger log = LoggerFactory.getLogger(Neo4jConnector.class);

    private final AppConfig cfg;
    private volatile String status = "managed-by-kgstore";

    public Neo4jConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    /** 由 KGStore 在启动后回调，告知是否可用。 */
    public void reportStatus(boolean available) {
        status = available ? "connected" : "disconnected";
    }

    public String status() { return status; }
}
