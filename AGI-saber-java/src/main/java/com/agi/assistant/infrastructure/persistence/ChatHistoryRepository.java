package com.agi.assistant.infrastructure.persistence;

import com.agi.assistant.infrastructure.platform.PostgresConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 聊天历史仓储（对应 Go infrastructure/persistence/chathistory）。
 */
@Repository
public class ChatHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryRepository.class);

    private final PostgresConnector pg;

    public ChatHistoryRepository(PostgresConnector pg) {
        this.pg = pg;
    }

    public static class Row {
        public String role;
        public String content;
        public String createdAt;
    }

    public void save(String role, String content) {
        Connection c = pg.connection();
        if (c == null) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_history (role, content) VALUES (?, ?)")) {
            ps.setString(1, role); ps.setString(2, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("聊天记录保存失败: {}", e.getMessage());
        }
    }

    public List<Row> load(int limit) {
        List<Row> rows = new ArrayList<>();
        Connection c = pg.connection();
        if (c == null) return rows;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT role, content, TO_CHAR(created_at, 'HH24:MI:SS') FROM chat_history " +
                        "ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Row r = new Row();
                    r.role = rs.getString(1);
                    r.content = rs.getString(2);
                    r.createdAt = rs.getString(3);
                    rows.add(r);
                }
            }
        } catch (SQLException e) {
            log.warn("加载聊天记录失败: {}", e.getMessage());
        }
        Collections.reverse(rows);
        return rows;
    }
}
