package com.lunarboreal.permissions;

import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.api.web.HttpException;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** LuckPerms WebUI bridge. Permission calculation and storage belong to LuckPerms. */
public final class PermissionsModule extends LcModule {
    private volatile PermissionBridge bridge;
    private volatile boolean ready;
    private volatile boolean closing;
    private volatile String connection = "尚未连接";
    private volatile String migration = "未执行";
    private String version = "";

    @Override public void onEnable() {
        PermissionWeb.register(this);
        connect();
    }

    @Override public void onReload() {
        reloadConfig();
        connect();
    }

    @Override public void onDisable() {
        closing = true;
        ready = false;
        bridge = null;
        connection = "已断开";
    }

    private synchronized void connect() {
        if (closing || connection.equals("正在连接")) return;
        ready = false;
        var plugin = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (plugin == null || !plugin.isEnabled()) {
            bridge = null;
            connection = "未安装或未启用 LuckPerms";
            logger().warning(connection + "，请安装官方 LuckPerms 后重启服务器");
            return;
        }
        version = plugin.getPluginMeta().getVersion();
        try {
            PermissionBridge next = LuckPermsBridge.connect();
            bridge = next;
            connection = "正在连接";
            boolean migrate = config().getBoolean("migrate-legacy-data", true);
            runAsync(() -> {
                try {
                    String result = next.initialize(dataFolder().toPath(), migrate);
                    if (closing || bridge != next) return;
                    migration = result;
                    ready = true;
                    connection = "已连接";
                    logger().info("已连接 LuckPerms " + version + "；" + result);
                } catch (Exception ex) {
                    if (closing || bridge != next) return;
                    connection = "连接失败：" + ex.getMessage();
                    logger().warning(connection);
                }
            });
        } catch (LinkageError | RuntimeException ex) {
            bridge = null;
            connection = "LuckPerms API 不可用";
            logger().warning(connection + "：" + ex.getMessage());
        }
    }

    PermissionBridge bridge() {
        PermissionBridge current = bridge;
        if (!ready || current == null) throw new HttpException(503, connection);
        return current;
    }

    String connection() { return connection; }
    String migration() { return migration; }
    String backendVersion() { return version; }

    List<String> knownNodes() {
        var nodes = new TreeSet<String>();
        for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
            nodes.add(permission.getName().toLowerCase(Locale.ROOT));
            permission.getChildren().keySet().forEach(node -> nodes.add(node.toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(nodes);
    }
}
