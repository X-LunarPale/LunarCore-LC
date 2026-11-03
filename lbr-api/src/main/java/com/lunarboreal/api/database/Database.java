package com.lunarboreal.api.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 共享数据库（SQLite 或 MySQL/MariaDB，HikariCP 连接池）。
 * <p>
 * 约定：所有表名使用 {@link #table(String)} 加前缀（默认 "lbr_"）。
 * 写可移植 SQL：主键自增请用 {@link #autoIncrementPk()}；文本用 VARCHAR(n)/TEXT；时间存 BIGINT 毫秒。
 * 不要在主线程做查询，使用 async 系列方法。
 */
public interface Database {

    enum Type { SQLITE, MYSQL }

    Type type();

    default boolean isMySQL() { return type() == Type.MYSQL; }

    /** 加前缀后的表名，如 table("economy_accounts") → lbr_economy_accounts。 */
    String table(String name);

    /** SQLite: "INTEGER PRIMARY KEY AUTOINCREMENT"；MySQL: "BIGINT PRIMARY KEY AUTO_INCREMENT"。 */
    String autoIncrementPk();

    /**
     * 生成 "插入或更新" 语句。
     * @param table 已加前缀的表名
     * @param keyColumns 唯一键列
     * @param columns 全部列（包含 key 列），参数顺序与之一致
     */
    String upsert(String table, List<String> keyColumns, List<String> columns);

    Connection connection() throws SQLException;

    int update(String sql, Object... params) throws SQLException;

    /** 插入并返回自增主键。 */
    long insert(String sql, Object... params) throws SQLException;

    <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException;

    <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException;

    /** 在单个事务中执行。 */
    <T> T transaction(SqlFunction<Connection, T> work) throws SQLException;

    <T> CompletableFuture<T> async(SqlFunction<Database, T> work);

    CompletableFuture<Integer> updateAsync(String sql, Object... params);

    <T> CompletableFuture<List<T>> queryAsync(String sql, RowMapper<T> mapper, Object... params);
}
