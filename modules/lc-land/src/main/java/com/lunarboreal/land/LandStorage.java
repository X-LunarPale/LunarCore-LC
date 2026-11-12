package com.lunarboreal.land;

import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.service.LandService.Flag;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 领地持久化。所有写操作进入单线程队列按顺序异步执行（保证 insert → update → delete 的先后关系），
 * 禁用模块时等待队列清空。
 */
public final class LandStorage {

    private final Database db;
    private final Logger log;
    private final String tLands, tMembers, tFlags;
    private final ExecutorService writer;

    public LandStorage(Database db, Logger log) {
        this.db = db;
        this.log = log;
        this.tLands = db.table("land_lands");
        this.tMembers = db.table("land_members");
        this.tFlags = db.table("land_flags");
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LunarCore-Land-Writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** 建表（在异步线程调用）。 */
    public void createTables() throws SQLException {
        db.update("CREATE TABLE IF NOT EXISTS " + tLands + " (" +
                "id " + db.autoIncrementPk() + ", " +
                "name VARCHAR(64) NOT NULL, owner VARCHAR(36) NOT NULL, owner_name VARCHAR(32), world VARCHAR(64) NOT NULL, " +
                "min_x INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_z INT NOT NULL, created_at BIGINT NOT NULL, " +
                "tp VARCHAR(128), enter_msg TEXT, leave_msg TEXT, sale_price VARCHAR(32), paid VARCHAR(32), " +
                "frozen INT DEFAULT 0, frozen_since BIGINT DEFAULT 0, last_tax BIGINT DEFAULT 0)");
        ensureHeightColumn("min_y", Integer.MIN_VALUE);
        ensureHeightColumn("max_y", Integer.MAX_VALUE);
        db.update("CREATE TABLE IF NOT EXISTS " + tMembers + " (" +
                "land_id BIGINT NOT NULL, uuid VARCHAR(36) NOT NULL, name VARCHAR(32), role VARCHAR(16) NOT NULL, " +
                "PRIMARY KEY (land_id, uuid))");
        db.update("CREATE TABLE IF NOT EXISTS " + tFlags + " (" +
                "land_id BIGINT NOT NULL, scope VARCHAR(8) NOT NULL, flag VARCHAR(32) NOT NULL, value INT NOT NULL, " +
                "PRIMARY KEY (land_id, scope, flag))");
        try {
            db.update("CREATE INDEX idx_" + tLands + "_owner ON " + tLands + " (owner)");
        } catch (SQLException ignored) {
            // 已存在
        }
    }

    /** Existing XZ claims retain full-height protection after migration. */
    private void ensureHeightColumn(String column, int fallback) throws SQLException {
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM " + tLands + " WHERE 1=0")) {
            ps.executeQuery().close();
            return;
        } catch (SQLException missing) {
            // ALTER errors must propagate: never load claims with missing height data.
            db.update("ALTER TABLE " + tLands + " ADD COLUMN " + column + " INT NOT NULL DEFAULT " + fallback);
        }
    }

    /** 全量加载（异步线程调用）。 */
    public List<Land> loadAll() throws SQLException {
        Map<Long, Land> map = new HashMap<>();
        List<Land> lands = db.query("SELECT * FROM " + tLands, rs -> {
            Land l = new Land(rs.getString("name"), UUID.fromString(rs.getString("owner")), rs.getString("owner_name"),
                    rs.getString("world"), rs.getInt("min_x"), rs.getInt("min_z"), rs.getInt("max_x"), rs.getInt("max_z"),
                    rs.getLong("created_at"));
            l.id = rs.getLong("id");
            l.minY = rs.getInt("min_y");
            l.maxY = rs.getInt("max_y");
            l.tp = parseTp(rs.getString("tp"));
            l.enterMessage = rs.getString("enter_msg");
            l.leaveMessage = rs.getString("leave_msg");
            String sp = rs.getString("sale_price");
            l.salePrice = sp == null || sp.isEmpty() ? null : Settings.dec(sp);
            String paid = rs.getString("paid");
            l.paid = paid == null || paid.isEmpty() ? BigDecimal.ZERO : Settings.dec(paid);
            l.frozen = rs.getInt("frozen") != 0;
            l.frozenSince = rs.getLong("frozen_since");
            long lt = rs.getLong("last_tax");
            l.lastTax = lt <= 0 ? l.createdAt : lt;
            return l;
        });
        for (Land l : lands) map.put(l.id, l);
        db.query("SELECT * FROM " + tMembers, rs -> {
            Land l = map.get(rs.getLong("land_id"));
            if (l != null) {
                try {
                    UUID u = UUID.fromString(rs.getString("uuid"));
                    Land.Role role = "MANAGER".equalsIgnoreCase(rs.getString("role")) ? Land.Role.MANAGER : Land.Role.MEMBER;
                    l.members.put(u, new Land.Member(u, rs.getString("name"), role));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return 0;
        });
        db.query("SELECT * FROM " + tFlags, rs -> {
            Land l = map.get(rs.getLong("land_id"));
            if (l != null) {
                try {
                    Flag f = Flag.valueOf(rs.getString("flag").toUpperCase(Locale.ROOT));
                    boolean v = rs.getInt("value") != 0;
                    if ("member".equalsIgnoreCase(rs.getString("scope"))) l.memberFlags.put(f, v);
                    else l.globalFlags.put(f, v);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return 0;
        });
        return lands;
    }

    // ------------------------------------------------------------ 写操作（异步排队）

    public void insert(Land land) {
        List<Land.Member> members = new ArrayList<>(land.members.values());
        Map<Flag, Boolean> g = new HashMap<>(land.globalFlags), m = new HashMap<>(land.memberFlags);
        Object[] snapshot = values(land);
        submit(() -> {
            long id = db.insert("INSERT INTO " + tLands + " (name, owner, owner_name, world, min_x, min_z, max_x, max_z, created_at, " +
                            "tp, enter_msg, leave_msg, sale_price, paid, frozen, frozen_since, last_tax, min_y, max_y) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    snapshot);
            land.id = id;
            writeMembers(id, members);
            writeFlags(id, g, m);
        });
    }

    private static Object[] values(Land land) {
        return new Object[]{land.name, land.owner.toString(), land.ownerName, land.world,
                land.minX, land.minZ, land.maxX, land.maxZ, land.createdAt, tpString(land.tp),
                land.enterMessage, land.leaveMessage, str(land.salePrice), str(land.paid),
                land.frozen ? 1 : 0, land.frozenSince, land.lastTax, land.minY, land.maxY};
    }

    public void save(Land land) {
        Object[] snapshot = values(land);
        submit(() -> {
            if (land.id < 0) throw new SQLException("Cannot update an unpersisted claim: " + snapshot[0]);
            Object[] params = java.util.Arrays.copyOf(snapshot, snapshot.length + 1);
            params[snapshot.length] = land.id;
            db.update("UPDATE " + tLands + " SET name=?, owner=?, owner_name=?, world=?, min_x=?, min_z=?, max_x=?, max_z=?, created_at=?, tp=?, enter_msg=?, " +
                            "leave_msg=?, sale_price=?, paid=?, frozen=?, frozen_since=?, last_tax=?, min_y=?, max_y=? WHERE id=?", params);
        });
    }

    public void saveMembers(Land land) {
        List<Land.Member> members = new ArrayList<>(land.members.values());
        submit(() -> {
            if (land.id >= 0) writeMembers(land.id, members);
        });
    }

    public void saveFlags(Land land) {
        Map<Flag, Boolean> g = new HashMap<>(land.globalFlags), m = new HashMap<>(land.memberFlags);
        submit(() -> {
            if (land.id >= 0) writeFlags(land.id, g, m);
        });
    }

    public void delete(Land land) {
        submit(() -> {
            long id = land.id;
            if (id < 0) return;
            db.transaction(c -> {
                exec(c, "DELETE FROM " + tMembers + " WHERE land_id=?", id);
                exec(c, "DELETE FROM " + tFlags + " WHERE land_id=?", id);
                exec(c, "DELETE FROM " + tLands + " WHERE id=?", id);
                return null;
            });
        });
    }

    /** 等待写队列清空（模块禁用时调用）。 */
    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warning("领地写入队列 15 秒内未完成，可能丢失部分修改");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 内部

    private interface SqlTask {
        void run() throws SQLException;
    }

    private void submit(SqlTask task) {
        if (writer.isShutdown()) {
            // 禁用阶段的兜底：直接执行
            try {
                task.run();
            } catch (SQLException e) {
                log.severe("领地数据写入失败: " + e.getMessage());
            }
            return;
        }
        writer.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.severe("领地数据写入失败: " + e.getMessage());
            }
        });
    }

    private void writeMembers(long id, List<Land.Member> members) throws SQLException {
        db.transaction(c -> {
            exec(c, "DELETE FROM " + tMembers + " WHERE land_id=?", id);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + tMembers + " (land_id, uuid, name, role) VALUES (?,?,?,?)")) {
                for (Land.Member m : members) {
                    ps.setLong(1, id);
                    ps.setString(2, m.uuid().toString());
                    ps.setString(3, m.name());
                    ps.setString(4, m.role().name());
                    ps.addBatch();
                }
                if (!members.isEmpty()) ps.executeBatch();
            }
            return null;
        });
    }

    private void writeFlags(long id, Map<Flag, Boolean> global, Map<Flag, Boolean> member) throws SQLException {
        db.transaction(c -> {
            exec(c, "DELETE FROM " + tFlags + " WHERE land_id=?", id);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + tFlags + " (land_id, scope, flag, value) VALUES (?,?,?,?)")) {
                int n = 0;
                for (var e : global.entrySet()) {
                    ps.setLong(1, id);
                    ps.setString(2, "global");
                    ps.setString(3, e.getKey().name());
                    ps.setInt(4, e.getValue() ? 1 : 0);
                    ps.addBatch();
                    n++;
                }
                for (var e : member.entrySet()) {
                    ps.setLong(1, id);
                    ps.setString(2, "member");
                    ps.setString(3, e.getKey().name());
                    ps.setInt(4, e.getValue() ? 1 : 0);
                    ps.addBatch();
                    n++;
                }
                if (n > 0) ps.executeBatch();
            }
            return null;
        });
    }

    private static void exec(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static String str(BigDecimal d) {
        return d == null ? null : d.toPlainString();
    }

    static String tpString(double[] t) {
        if (t == null) return null;
        return t[0] + "," + t[1] + "," + t[2] + "," + t[3] + "," + t[4];
    }

    static double[] parseTp(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length < 5) return null;
        try {
            double[] d = new double[5];
            for (int i = 0; i < 5; i++) d[i] = Double.parseDouble(p[i]);
            return d;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
