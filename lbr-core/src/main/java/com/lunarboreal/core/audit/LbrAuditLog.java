package com.lunarboreal.core.audit;

import com.lunarboreal.api.audit.AuditLog;
import com.lunarboreal.core.LunarCorePlugin;
import com.lunarboreal.core.database.SqlDatabase;

import java.sql.SQLException;
import java.util.List;

/** 审计日志（表 lbr_audit），写入走数据库异步线程池。 */
public final class LbrAuditLog implements AuditLog {

    private final LunarCorePlugin plugin;
    private final SqlDatabase db;
    private final String table;

    public LbrAuditLog(LunarCorePlugin plugin, SqlDatabase db) {
        this.plugin = plugin;
        this.db = db;
        this.table = db.table("audit");
    }

    public void init() throws SQLException {
        db.update("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id " + db.autoIncrementPk() + ","
                + "time BIGINT NOT NULL,"
                + "module VARCHAR(64) NOT NULL,"
                + "actor VARCHAR(64) NOT NULL,"
                + "action VARCHAR(64) NOT NULL,"
                + "detail TEXT)");
        db.createIndexQuietly(table + "_time", table, "time");
        db.createIndexQuietly(table + "_module", table, "module");
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    @Override
    public void log(String module, String actor, String action, String detail) {
        long now = System.currentTimeMillis();
        String m = cut(module == null ? "core" : module, 64);
        String a = cut(actor == null ? "console" : actor, 64);
        String act = cut(action, 64);
        plugin.debug("[审计] " + m + " | " + a + " | " + act + " | " + detail);
        db.updateAsync("INSERT INTO " + table + " (time, module, actor, action, detail) VALUES (?, ?, ?, ?, ?)",
                now, m, a, act, detail == null ? "" : detail);
    }

    @Override
    public List<Entry> recent(String module, int offset, int limit) {
        int off = Math.max(0, offset);
        int lim = Math.max(1, Math.min(limit, 1000));
        try {
            if (module == null || module.isBlank()) {
                return db.query("SELECT id, time, module, actor, action, detail FROM " + table + " ORDER BY id DESC LIMIT ? OFFSET ?",
                        rs -> new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), lim, off);
            }
            return db.query("SELECT id, time, module, actor, action, detail FROM " + table + " WHERE module = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                    rs -> new Entry(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), module, lim, off);
        } catch (SQLException e) {
            plugin.getLogger().warning("读取审计日志失败: " + e.getMessage());
            return List.of();
        }
    }
}
