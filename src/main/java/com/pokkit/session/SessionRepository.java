package com.pokkit.session;

import org.springframework.ai.chat.messages.Message;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session 持久化 — 纯 JDBC 操作 SQLite。
 * <p>
 * 管理 sessions 和 messages 两张表，提供 CRUD + 批量操作。
 * 不依赖 Spring JdbcTemplate，完全自包含。
 */
public class SessionRepository implements AutoCloseable {

    private final Connection connection;
    private final MessageSerializer serializer = new MessageSerializer();

    public SessionRepository(String dbPath) {
        try {
            // 确保父目录存在
            Path parent = Path.of(dbPath).getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            // 启用 WAL 模式和外键约束
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }

            initSchema();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database: " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        id         TEXT PRIMARY KEY,
                        title      TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id              INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id      TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                        ordinal         INTEGER NOT NULL,
                        role            TEXT NOT NULL,
                        content         TEXT,
                        tool_calls      TEXT,
                        tool_responses  TEXT,
                        created_at      TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_messages_session
                    ON messages(session_id, ordinal)
                    """);
        }
    }

    // ==================== Session 操作 ====================

    public Session createSession(String title) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String nowStr = now.toString();

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sessions(id, title, created_at, updated_at) VALUES(?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, title);
            ps.setString(3, nowStr);
            ps.setString(4, nowStr);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create session", e);
        }

        return new Session(id, title, now, now);
    }

    public Optional<Session> lastSession() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, title, created_at, updated_at FROM sessions ORDER BY updated_at DESC LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(readSession(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query last session", e);
        }
    }

    public List<Session> listSessions() {
        List<Session> sessions = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, title, created_at, updated_at FROM sessions ORDER BY updated_at DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                sessions.add(readSession(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
        return sessions;
    }

    public void updateTitle(String sessionId, String title) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, title);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update session title", e);
        }
    }

    public void touchSession(String sessionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sessions SET updated_at = ? WHERE id = ?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to touch session", e);
        }
    }

    public void deleteAllSessions() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM sessions");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete all sessions", e);
        }
    }

    // ==================== Message 操作 ====================

    public void saveMessage(String sessionId, int ordinal, Message message) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO messages(session_id, ordinal, role, content, tool_calls, tool_responses, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, sessionId);
            ps.setInt(2, ordinal);
            ps.setString(3, serializer.role(message));
            ps.setString(4, serializer.content(message));
            ps.setString(5, serializer.toolCallsJson(message));
            ps.setString(6, serializer.toolResponsesJson(message));
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save message", e);
        }
    }

    public List<Message> loadMessages(String sessionId) {
        List<Message> messages = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT role, content, tool_calls, tool_responses FROM messages WHERE session_id = ? ORDER BY ordinal")) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                messages.add(serializer.toMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("tool_calls"),
                        rs.getString("tool_responses")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load messages", e);
        }
        return messages;
    }

    public int messageCount(String sessionId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM messages WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count messages", e);
        }
    }

    /**
     * 压缩后重写整个 session 的消息（事务内：先删后插）。
     */
    public void replaceMessages(String sessionId, List<Message> messages) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM messages WHERE session_id = ?")) {
                del.setString(1, sessionId);
                del.executeUpdate();
            }
            for (int i = 0; i < messages.size(); i++) {
                saveMessage(sessionId, i, messages.get(i));
            }
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException("Failed to replace messages", e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ==================== 内部方法 ====================

    private Session readSession(ResultSet rs) throws SQLException {
        return new Session(
                rs.getString("id"),
                rs.getString("title"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // ignore on close
        }
    }
}
