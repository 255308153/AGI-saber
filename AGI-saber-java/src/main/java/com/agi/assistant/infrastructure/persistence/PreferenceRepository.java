package com.agi.assistant.infrastructure.persistence;

import com.agi.assistant.infrastructure.platform.PostgresConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 偏好仓储（对应 Go infrastructure/persistence/preference）。
 */
@Repository
public class PreferenceRepository {

    private static final Logger log = LoggerFactory.getLogger(PreferenceRepository.class);
    private final PostgresConnector pg;

    public PreferenceRepository(PostgresConnector pg) {
        this.pg = pg;
    }

    public void save(String userId, String key, String value) {
        Connection c = pg.connection();
        if (c == null) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO user_preferences (user_id, key, value) VALUES (?, ?, ?) " +
                        "ON CONFLICT (user_id, key) DO UPDATE SET value = ?, updated_at = NOW()")) {
            ps.setString(1, userId); ps.setString(2, key); ps.setString(3, value); ps.setString(4, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("偏好保存失败: {}", e.getMessage());
        }
    }

    public Map<String, String> loadAll(String userId) {
        Map<String, String> result = new LinkedHashMap<>();
        Connection c = pg.connection();
        if (c == null) return result;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT key, value FROM user_preferences WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            log.warn("加载偏好失败: {}", e.getMessage());
        }
        return result;
    }
}
