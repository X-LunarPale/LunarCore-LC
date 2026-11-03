package com.lunarboreal.chat;

import com.lunarboreal.api.database.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** 数据访问：chat_users / chat_mail / chat_log。所有方法都应在异步线程调用（cache 方法除外）。 */
final class ChatStore {

    static final int MAX_PENDING_LOGS = 5000;

    record Mail(long id, UUID recipient, String sender, String message, long time, boolean read) {}

    record LogEntry(long id, long time, String uuid, String name, String channel, String message) {}

    record AiMemory(long id, String text, String vector) {}

    private final Database db;
    private final Logger logger;
    final String users;
    final String mail;
    final String log;
    final String aiMemory;
    private final Map<UUID, ChatUser> cache = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Object[]> pendingLogs = new ArrayBlockingQueue<>(MAX_PENDING_LOGS);
    private final AtomicLong droppedLogs = new AtomicLong();

    ChatStore(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
        this.users = db.table("chat_users");
        this.mail = db.table("chat_mail");
        this.log = db.table("chat_log");
        this.aiMemory = db.table("chat_ai_memory");
    }

    void createTables() throws SQLException {
        db.update("CREATE TABLE IF NOT EXISTS " + users + " (uuid VARCHAR(36) PRIMARY KEY, nick VARCHAR(255), nick_plain VARCHAR(64), "
                + "ignored TEXT, socialspy INT NOT NULL DEFAULT 0, msgdisabled INT NOT NULL DEFAULT 0)");
        db.update("CREATE TABLE IF NOT EXISTS " + mail + " (id " + db.autoIncrementPk() + ", recipient VARCHAR(36) NOT NULL, "
                + "sender VARCHAR(64) NOT NULL, message TEXT NOT NULL, time BIGINT NOT NULL, is_read INT NOT NULL DEFAULT 0)");
        db.update("CREATE TABLE IF NOT EXISTS " + log + " (id " + db.autoIncrementPk() + ", time BIGINT NOT NULL, uuid VARCHAR(36), "
                + "name VARCHAR(64), channel VARCHAR(16) NOT NULL, message TEXT NOT NULL)");
        db.update("CREATE TABLE IF NOT EXISTS " + aiMemory + " (id " + db.autoIncrementPk() + ", player_uuid VARCHAR(36) NOT NULL, "
                + "bot_id VARCHAR(36) NOT NULL, time BIGINT NOT NULL, text TEXT NOT NULL, vector TEXT NOT NULL)");
        index("CREATE INDEX idx_" + mail + "_rcpt ON " + mail + " (recipient)");
        index("CREATE INDEX idx_" + log + "_time ON " + log + " (time)");
        index("CREATE INDEX idx_" + aiMemory + "_player ON " + aiMemory + " (player_uuid, bot_id, time)");
    }

    private void index(String sql) {
        try { db.update(sql); } catch (SQLException ignored) { /* 已存在 */ }
    }

    // ---------------- 用户 ----------------

    ChatUser cached(UUID uuid) {
        return cache.computeIfAbsent(uuid, ChatUser::new);
    }

    Optional<ChatUser> peek(UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    Iterable<ChatUser> allCached() {
        return cache.values();
    }

    void evict(UUID uuid) {
        cache.remove(uuid);
    }

    ChatUser load(UUID uuid) throws SQLException {
        ChatUser u = cached(uuid);
        db.queryOne("SELECT nick, ignored, socialspy, msgdisabled FROM " + users + " WHERE uuid = ?", rs -> {
            u.nick = rs.getString(1);
            u.parseIgnored(rs.getString(2));
            u.socialSpy = rs.getInt(3) != 0;
            u.msgDisabled = rs.getInt(4) != 0;
            return u;
        }, uuid.toString());
        return u;
    }

    void save(ChatUser u, String nickPlain) {
        db.updateAsync(db.upsert(users, List.of("uuid"), List.of("uuid", "nick", "nick_plain", "ignored", "socialspy", "msgdisabled")),
                u.uuid.toString(), u.nick, nickPlain, u.ignoredString(), u.socialSpy ? 1 : 0, u.msgDisabled ? 1 : 0)
                .exceptionally(ex -> { logger.warning("保存聊天数据失败: " + ex.getMessage()); return 0; });
    }

    /** 根据昵称纯文本查找（忽略大小写）。 */
    Optional<UUID> findByNick(String plain) throws SQLException {
        return db.queryOne("SELECT uuid FROM " + users + " WHERE LOWER(nick_plain) = LOWER(?)",
                rs -> UUID.fromString(rs.getString(1)), plain);
    }

    // ---------------- 邮件 ----------------

    void sendMail(UUID to, String sender, String message) throws SQLException {
        db.update("INSERT INTO " + mail + " (recipient, sender, message, time, is_read) VALUES (?,?,?,?,0)",
                to.toString(), sender, message, System.currentTimeMillis());
    }

    List<Mail> mails(UUID to) throws SQLException {
        return db.query("SELECT id, recipient, sender, message, time, is_read FROM " + mail + " WHERE recipient = ? ORDER BY time DESC",
                rs -> new Mail(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getInt(6) != 0), to.toString());
    }

    int unread(UUID to) throws SQLException {
        return db.queryOne("SELECT COUNT(*) FROM " + mail + " WHERE recipient = ? AND is_read = 0",
                rs -> rs.getInt(1), to.toString()).orElse(0);
    }

    void markRead(UUID to) throws SQLException {
        db.update("UPDATE " + mail + " SET is_read = 1 WHERE recipient = ?", to.toString());
    }

    void clearMail(UUID to) throws SQLException {
        db.update("DELETE FROM " + mail + " WHERE recipient = ?", to.toString());
    }

    // ---------------- 聊天记录 ----------------

    void queueLog(UUID uuid, String name, String channel, String message) {
        if (!pendingLogs.offer(new Object[]{System.currentTimeMillis(), uuid == null ? null : uuid.toString(), name, channel, message})) {
            droppedLogs.incrementAndGet();
        }
    }

    /** 批量写入待写日志（异步定时调用 + 禁用时调用）。 */
    synchronized void flushLogs() {
        List<Object[]> batch = new ArrayList<>(pendingLogs.size());
        pendingLogs.drainTo(batch);
        if (batch.isEmpty()) {
            reportDroppedLogs();
            return;
        }
        try {
            db.transaction(conn -> {
                try (var ps = conn.prepareStatement("INSERT INTO " + log + " (time, uuid, name, channel, message) VALUES (?,?,?,?,?)")) {
                    for (Object[] r : batch) {
                        ps.setLong(1, (Long) r[0]);
                        ps.setString(2, (String) r[1]);
                        ps.setString(3, (String) r[2]);
                        ps.setString(4, (String) r[3]);
                        ps.setString(5, (String) r[4]);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
        } catch (SQLException e) {
            logger.warning("写入聊天记录失败（将重试）: " + e.getMessage());
            for (Object[] row : batch) {
                if (!pendingLogs.offer(row)) droppedLogs.incrementAndGet();
            }
        }
        reportDroppedLogs();
    }

    private void reportDroppedLogs() {
        long count = droppedLogs.getAndSet(0);
        if (count > 0) logger.warning("聊天记录缓冲区已满，已丢弃 " + count + " 条记录（上限 " + MAX_PENDING_LOGS + " 条）。");
    }

    void purgeLogs(int keepDays) {
        if (keepDays <= 0) return;
        try {
            db.update("DELETE FROM " + log + " WHERE time < ?", System.currentTimeMillis() - keepDays * 86_400_000L);
        } catch (SQLException e) {
            logger.warning("清理聊天记录失败: " + e.getMessage());
        }
    }

    List<AiMemory> aiMemories(UUID player, String botId, long cutoff) throws SQLException {
        return db.query("SELECT id, text, vector FROM " + aiMemory
                + " WHERE player_uuid = ? AND bot_id = ? AND time >= ? ORDER BY id DESC LIMIT 24",
                rs -> new AiMemory(rs.getLong(1), rs.getString(2), rs.getString(3)), player.toString(), botId, cutoff);
    }

    boolean hasAiMemory(UUID player, String botId, String text) throws SQLException {
        return db.queryOne("SELECT id FROM " + aiMemory + " WHERE player_uuid = ? AND bot_id = ? AND text = ? LIMIT 1",
                rs -> rs.getLong(1), player.toString(), botId, text).isPresent();
    }

    void saveAiMemory(UUID player, String botId, String text, String vector, long cutoff) throws SQLException {
        db.update("DELETE FROM " + aiMemory + " WHERE player_uuid = ? AND bot_id = ? AND time < ?",
                player.toString(), botId, cutoff);
        List<Long> ids = db.query("SELECT id FROM " + aiMemory
                + " WHERE player_uuid = ? AND bot_id = ? ORDER BY id DESC LIMIT 24",
                rs -> rs.getLong(1), player.toString(), botId);
        for (int i = 23; i < ids.size(); i++) db.update("DELETE FROM " + aiMemory + " WHERE id = ?", ids.get(i));
        db.update("INSERT INTO " + aiMemory + " (player_uuid, bot_id, time, text, vector) VALUES (?,?,?,?,?)",
                player.toString(), botId, System.currentTimeMillis(), text, vector);
    }

    int clearAiMemories(UUID player) throws SQLException {
        return player == null ? db.update("DELETE FROM " + aiMemory)
                : db.update("DELETE FROM " + aiMemory + " WHERE player_uuid = ?", player.toString());
    }

    void purgeAiMemories() {
        try { db.update("DELETE FROM " + aiMemory + " WHERE time < ?", System.currentTimeMillis() - 30L * 86_400_000L); }
        catch (SQLException e) { logger.warning("清理 AI 记忆失败: " + e.getClass().getSimpleName()); }
    }

    long countLogs(String q) throws SQLException {
        if (q == null || q.isBlank()) {
            return db.queryOne("SELECT COUNT(*) FROM " + log, rs -> rs.getLong(1)).orElse(0L);
        }
        String like = "%" + q + "%";
        return db.queryOne("SELECT COUNT(*) FROM " + log + " WHERE name LIKE ? OR message LIKE ?", rs -> rs.getLong(1), like, like).orElse(0L);
    }

    List<LogEntry> logs(String q, int offset, int limit) throws SQLException {
        RowMapperHolder m = new RowMapperHolder();
        if (q == null || q.isBlank()) {
            return db.query("SELECT id, time, uuid, name, channel, message FROM " + log + " ORDER BY id DESC LIMIT ? OFFSET ?", m::map, limit, offset);
        }
        String like = "%" + q + "%";
        return db.query("SELECT id, time, uuid, name, channel, message FROM " + log + " WHERE name LIKE ? OR message LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?",
                m::map, like, like, limit, offset);
    }

    long countSince(long since) throws SQLException {
        return db.queryOne("SELECT COUNT(*) FROM " + log + " WHERE time >= ? AND channel = 'public'", rs -> rs.getLong(1), since).orElse(0L);
    }

    long totalMails() throws SQLException {
        return db.queryOne("SELECT COUNT(*) FROM " + mail, rs -> rs.getLong(1)).orElse(0L);
    }

    private static final class RowMapperHolder {
        LogEntry map(java.sql.ResultSet rs) throws SQLException {
            return new LogEntry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
        }
    }
}
