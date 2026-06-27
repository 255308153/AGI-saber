package com.agi.assistant.infrastructure.persistence;

import com.agi.assistant.infrastructure.platform.PostgresConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 任务快照仓储（对应 Go infrastructure/persistence/snapshot）。
 */
@Repository
public class SnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);

    private final PostgresConnector pg;

    public SnapshotRepository(PostgresConnector pg) {
        this.pg = pg;
    }

    public void save(String taskId, String stateJson) {
        Connection c = pg.connection();
        if (c == null) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO task_snapshots (task_id, state) VALUES (?, ?::jsonb) " +
                        "ON CONFLICT (task_id) DO UPDATE SET state = ?::jsonb, created_at = NOW()")) {
            ps.setString(1, taskId);
            ps.setString(2, stateJson);
            ps.setString(3, stateJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("快照保存失败: {}", e.getMessage());
        }
    }
}
