package com.agi.assistant.infrastructure.platform;

import com.agi.assistant.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PostgreSQL 连接器（对应 Go infrastructure/platform/postgres）。
 *
 * <p>负责：连接 + 启动建表 + 失败降级。其余 SQL 操作由 5 个 Repository
 * 通过 {@link #connection()} 拿到 Connection 后自行编排。</p>
 *
 * <p>失败降级：连接失败时 {@link #connection()} 返回 null，Repository 应自行短路。</p>
 */
@Component
public class PostgresConnector {

    private static final Logger log = LoggerFactory.getLogger(PostgresConnector.class);

    private final AppConfig cfg;
    private volatile Connection conn;
    private volatile String status = "disconnected";

    public PostgresConnector(AppConfig cfg) {
        this.cfg = cfg;
    }

    @PostConstruct
    public void init() {
        try {
            String url = cfg.getPgJdbcUrl();
            conn = DriverManager.getConnection(url, cfg.getPostgres().getUser(), cfg.getPostgres().getPassword());
            status = "connected";
            initSchema();
            log.info("PostgreSQL 已连接: {}", url);
        } catch (Exception e) {
            log.warn("PostgreSQL 连接失败: {} (将使用内存模式)", e.getMessage());
            status = "disconnected";
        }
    }

    private void initSchema() {
        if (conn == null) return;
        String[] ddls = {
                """
                CREATE TABLE IF NOT EXISTS user_preferences (
                    user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
                    updated_at TIMESTAMP DEFAULT NOW(), PRIMARY KEY (user_id, key))""",
                """
                CREATE TABLE IF NOT EXISTS task_snapshots (
                    task_id TEXT PRIMARY KEY, state JSONB NOT NULL, created_at TIMESTAMP DEFAULT NOW())""",
                """
                CREATE TABLE IF NOT EXISTS chat_history (
                    id SERIAL PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP DEFAULT NOW())""",
                """
                CREATE TABLE IF NOT EXISTS long_term_memory (
                    id SERIAL PRIMARY KEY, content TEXT NOT NULL, importance FLOAT NOT NULL DEFAULT 0.5,
                    embedding JSONB, created_at TIMESTAMP DEFAULT NOW(), last_accessed TIMESTAMP DEFAULT NOW())""",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT NOW()",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS category TEXT DEFAULT 'general'",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS tags JSONB",
                "ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS slot_hint TEXT",
                """
                CREATE TABLE IF NOT EXISTS rag_chunks (
                    id BIGSERIAL PRIMARY KEY, doc_hash TEXT NOT NULL, chunk_idx INT NOT NULL,
                    content TEXT NOT NULL, embedding JSONB, created_at TIMESTAMP DEFAULT NOW(),
                    UNIQUE(doc_hash, chunk_idx))"""
        };
        try (Statement stmt = conn.createStatement()) {
            for (String ddl : ddls) stmt.execute(ddl);
            log.info("PostgreSQL 表结构已初始化");
        } catch (SQLException e) {
            log.warn("PG 建表失败: {}", e.getMessage());
        }
    }

    /** @return Connection（可能为 null，表示未连接） */
    public Connection connection() { return conn; }

    public boolean available() { return "connected".equals(status) && conn != null; }

    public String status() { return status; }

    @PreDestroy
    public void close() {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
    }
}
