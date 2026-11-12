package com.lunarboreal.qqauth.binding;

import com.lunarboreal.api.database.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class BindingTestDatabase implements Database {
    private final String url;
    private final ThreadLocal<Connection> tx = new ThreadLocal<>();
    BindingTestDatabase(java.nio.file.Path file) { url = "jdbc:sqlite:" + file; }
    public Type type() { return Type.SQLITE; }
    public String table(String name) { return "test_" + name; }
    public String autoIncrementPk() { return "INTEGER PRIMARY KEY AUTOINCREMENT"; }
    public String upsert(String table, List<String> keys, List<String> cols) { return "INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (?,?) ON CONFLICT(id) DO NOTHING"; }
    public Connection connection() throws SQLException { Connection c = DriverManager.getConnection(url); try (var s = c.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); } return c; }
    public int update(String sql, Object... args) throws SQLException { return execute(c -> { try(var ps=c.prepareStatement(sql)) { bind(ps,args); return ps.executeUpdate(); } }); }
    public long insert(String sql, Object... args) throws SQLException { return update(sql,args); }
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) throws SQLException { return execute(c -> { try (var ps=c.prepareStatement(sql)) { bind(ps,args); try(var rs=ps.executeQuery()) { List<T> out=new ArrayList<>(); while(rs.next()) out.add(mapper.map(rs)); return out; } } }); }
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) throws SQLException { return query(sql,mapper,args).stream().findFirst(); }
    public <T> T transaction(SqlFunction<Connection,T> work) throws SQLException { if(tx.get()!=null) return work.apply(tx.get()); try(var c=connection()) { c.setAutoCommit(false); tx.set(c); try { T out=work.apply(c); c.commit(); return out; } catch(SQLException|RuntimeException e) { c.rollback(); throw e; } finally {tx.remove();} } }
    private <T> T execute(SqlFunction<Connection,T> work) throws SQLException { if(tx.get()!=null) return work.apply(tx.get()); try(var c=connection()) {return work.apply(c);} }
    private static void bind(PreparedStatement ps,Object[] args) throws SQLException { for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); }
    public <T> CompletableFuture<T> async(SqlFunction<Database,T> work) { try{return CompletableFuture.completedFuture(work.apply(this));}catch(SQLException e){return CompletableFuture.failedFuture(e);} }
    public CompletableFuture<Integer> updateAsync(String sql,Object... args) { return async(db->db.update(sql,args)); }
    public <T> CompletableFuture<List<T>> queryAsync(String sql,RowMapper<T> mapper,Object... args) { return async(db->db.query(sql,mapper,args)); }
}
